/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.volmlib.util.mantle.io;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

public class IOWorkerSupport implements Closeable {
    private static final boolean WINDOWS = File.separatorChar == '\\';
    private static final int MOVE_ATTEMPTS = 50;

    // No SYNC: it fsyncs every individual write (8KB buffers -> hundreds of fsyncs per plate).
    // Durability is provided by DelegateStream.Output.close(), which force(true)s the channel
    // once the whole plate has been written.
    private static final Set<OpenOption> OPTIONS = Set.of(
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE
    );
    private static final ConcurrentMap<String, ChannelGate> GLOBAL_CHANNEL_GATES = new ConcurrentHashMap<>();

    private final Path root;
    private final File tmp;
    private final int maxCacheSize;
    private final AcquireListener acquireListener;
    private final LinkedHashMap<String, Holder> cache = new LinkedHashMap<>(16, 0.75f, true);

    public IOWorkerSupport(File root) {
        this(root, 128, null);
    }

    public IOWorkerSupport(File root, int maxCacheSize, AcquireListener acquireListener) {
        this.root = root.toPath();
        this.tmp = new File(root, ".tmp");
        this.maxCacheSize = Math.max(maxCacheSize, 1);
        this.acquireListener = acquireListener;
    }

    public File createTempFile(String prefix, String suffix) throws IOException {
        tmp.mkdirs();
        return File.createTempFile(prefix, suffix, tmp);
    }

    public <T> T withChannel(String name, ChannelFunction<T> action) throws IOException {
        try (SynchronizedChannel channel = getChannel(name)) {
            return action.apply(channel);
        }
    }

    public void withChannel(String name, ChannelConsumer action) throws IOException {
        withChannel(name, channel -> {
            action.accept(channel);
            return null;
        });
    }

    public void replaceAtomically(String name, Path staged) throws IOException {
        Path target = root.resolve(name);
        ChannelGate gate = gateFor(target);
        gate.lock.acquireUninterruptibly();
        try {
            for (Holder holder : new ArrayList<>(gate.holders)) {
                holder.closeLocked();
            }
            moveAtomically(staged, target);
            if (!WINDOWS) {
                try (FileChannel directory = FileChannel.open(target.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                    directory.force(true);
                }
            }
        } finally {
            gate.lock.release();
        }
    }

    @Override
    public void close() throws IOException {
        List<Holder> holders;
        synchronized (cache) {
            holders = new ArrayList<>(cache.values());
            cache.clear();
        }
        IOException failure = null;
        for (Holder holder : holders) {
            try {
                holder.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static ChannelGate gateFor(Path filePath) {
        String gateKey = filePath.toAbsolutePath().normalize().toString();
        return GLOBAL_CHANNEL_GATES.computeIfAbsent(gateKey, unused -> new ChannelGate());
    }

    private static void moveAtomically(Path staged, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException error) {
                if (!WINDOWS || attempt >= MOVE_ATTEMPTS) {
                    throw error;
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException("Interrupted while replacing " + target);
                    failure.initCause(interrupted);
                    failure.addSuppressed(error);
                    throw failure;
                }
            }
        }
    }

    private Holder createHolder(String name) throws IOException {
        Path filePath = root.resolve(name);
        ChannelGate gate = gateFor(filePath);
        gate.lock.acquireUninterruptibly();
        try {
            Holder holder = new Holder(FileChannel.open(filePath, OPTIONS), gate);
            gate.holders.add(holder);
            return holder;
        } finally {
            gate.lock.release();
        }
    }

    private SynchronizedChannel getChannel(String name) throws IOException {
        long startNanos = System.nanoTime();
        try {
            while (true) {
                Holder holder;
                Holder evicted = null;
                synchronized (cache) {
                    holder = cache.get(name);
                    if (holder == null && cache.size() >= maxCacheSize) {
                        Iterator<Map.Entry<String, Holder>> iterator = cache.entrySet().iterator();
                        if (iterator.hasNext()) {
                            Map.Entry<String, Holder> eldest = iterator.next();
                            iterator.remove();
                            evicted = eldest.getValue();
                        }
                    }
                }

                if (evicted != null) {
                    evicted.close();
                }

                if (holder == null) {
                    Holder created = createHolder(name);
                    boolean createdUsed = false;
                    try {
                        synchronized (cache) {
                            Holder existing = cache.get(name);
                            if (existing == null) {
                                cache.put(name, created);
                                holder = created;
                                createdUsed = true;
                            } else {
                                holder = existing;
                            }
                        }

                        if (!createdUsed) {
                            created.close();
                        }
                    } catch (IOException e) {
                        if (!createdUsed) {
                            try {
                                created.close();
                            } catch (IOException ignored) {
                            }
                        }
                        throw e;
                    }
                }

                SynchronizedChannel channel = holder.acquire();
                if (channel != null) {
                    return channel;
                }

                synchronized (cache) {
                    if (cache.get(name) == holder) {
                        cache.remove(name);
                    }
                }
            }
        } finally {
            if (acquireListener != null) {
                acquireListener.onAcquire(name, (System.nanoTime() - startNanos) / 1_000_000L);
            }
        }
    }

    static final class ChannelGate {
        final Semaphore lock = new Semaphore(1);
        final Set<Holder> holders = new HashSet<>();
    }

    @FunctionalInterface
    public interface ChannelFunction<T> {
        T apply(SynchronizedChannel channel) throws IOException;
    }

    @FunctionalInterface
    public interface ChannelConsumer {
        void accept(SynchronizedChannel channel) throws IOException;
    }

    @FunctionalInterface
    public interface AcquireListener {
        void onAcquire(String name, long millis);
    }
}
