package art.arcane.volmlib.util.mantle.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

public class IOWorkerAtomicWriteTest {
    private static final String NAME = "p.0.0.ttp";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 5_000L)
    public void writerFailurePreservesTheTargetAndDiscardsStaging() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        byte[] original = {1, 2, 3, 4};
        Files.write(root.resolve(NAME), original);
        try (IOWorkerSupport support = new IOWorkerSupport(root.toFile())) {
            IOWorkerRuntimeSupport runtime = new IOWorkerRuntimeSupport(support, IOWorkerCodecSupport.identity());
            assertThrows(IOException.class, () -> runtime.writeAtomically(NAME, "plate", ".tmp", original, (value, output) -> {
                output.writeInt(27);
                throw new IOException("Writer failed");
            }));
            assertArrayEquals(original, Files.readAllBytes(root.resolve(NAME)));
            assertStagingEmpty(root);
        }
    }

    @Test(timeout = 5_000L)
    public void codecFinishFailurePreservesTheTargetAndDiscardsStaging() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        byte[] original = {1, 2, 3, 4};
        Files.write(root.resolve(NAME), original);
        IOWorkerCodecSupport codec = new IOWorkerCodecSupport() {
            @Override
            public InputStream decode(InputStream input) {
                return input;
            }

            @Override
            public OutputStream encode(OutputStream output) {
                return new FilterOutputStream(output) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        throw new IOException("Codec finish failed");
                    }
                };
            }
        };
        try (IOWorkerSupport support = new IOWorkerSupport(root.toFile())) {
            IOWorkerRuntimeSupport runtime = new IOWorkerRuntimeSupport(support, codec);
            assertThrows(IOException.class, () -> runtime.writeAtomically(NAME, "plate", ".tmp", original,
                    (value, output) -> output.write(value)));
            assertArrayEquals(original, Files.readAllBytes(root.resolve(NAME)));
            assertStagingEmpty(root);
        }
    }

    @Test(timeout = 5_000L)
    public void replacementUsesANewFileAndInvalidatesEveryWorkersCachedChannel() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        byte[] original = {1, 2, 3, 4};
        byte[] replacement = {7, 8};
        Path target = root.resolve(NAME);
        Files.write(target, original);
        Path originalLink = Files.createLink(root.resolve("original-link"), target);
        try (IOWorkerSupport first = new IOWorkerSupport(root.toFile());
             IOWorkerSupport second = new IOWorkerSupport(root.toFile())) {
            IOWorkerRuntimeSupport writer = new IOWorkerRuntimeSupport(first, IOWorkerCodecSupport.identity());
            IOWorkerRuntimeSupport reader = new IOWorkerRuntimeSupport(second, IOWorkerCodecSupport.identity());
            assertArrayEquals(original, read(writer));
            assertArrayEquals(original, read(reader));

            write(writer, replacement);

            assertArrayEquals(original, Files.readAllBytes(originalLink));
            assertArrayEquals(replacement, read(writer));
            assertArrayEquals(replacement, read(reader));
            reader.write(NAME, new byte[]{9}, (value, output) -> output.write(value));
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(target));
            assertArrayEquals(original, Files.readAllBytes(originalLink));
            assertStagingEmpty(root);
        }
    }

    @Test(timeout = 5_000L)
    public void unsupportedAtomicMoveFailsWithoutReplacingTheTarget() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        Path target = root.resolve(NAME);
        byte[] original = {1, 2, 3, 4};
        Files.write(target, original);
        Path staged = Files.write(root.resolve("staged"), new byte[]{7, 8});
        try (IOWorkerSupport support = new IOWorkerSupport(root.toFile());
             MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
                    .thenThrow(new AtomicMoveNotSupportedException(staged.toString(), target.toString(), "Unsupported"));
            assertThrows(AtomicMoveNotSupportedException.class, () -> support.replaceAtomically(NAME, staged));
        }
        assertArrayEquals(original, Files.readAllBytes(target));
        assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(staged));
    }

    @Test(timeout = 5_000L)
    public void replacementWaitsForReadersHoldingTheChannel() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        byte[] original = {1, 2, 3, 4};
        Files.write(root.resolve(NAME), original);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch replacing = new CountDownLatch(1);
        try (IOWorkerSupport first = new IOWorkerSupport(root.toFile());
             IOWorkerSupport second = new IOWorkerSupport(root.toFile())) {
            Future<byte[]> heldRead = executor.submit(() -> first.withChannel(NAME, channel -> {
                reading.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Reader release timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return channel.read().readAllBytes();
            }));
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            Future<?> replacement = executor.submit(() -> {
                replacing.countDown();
                write(new IOWorkerRuntimeSupport(second, IOWorkerCodecSupport.identity()), new byte[]{7, 8});
                return null;
            });
            assertTrue(replacing.await(3, TimeUnit.SECONDS));
            assertFalse(replacement.isDone());
            release.countDown();
            assertArrayEquals(original, heldRead.get(3, TimeUnit.SECONDS));
            replacement.get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(root.resolve(NAME)));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10_000L)
    public void uncachedReadersSeeOnlyCompleteOldOrNewFiles() throws Exception {
        Path root = temporaryFolder.newFolder().toPath();
        Path target = root.resolve(NAME);
        byte[] first = new byte[128 * 1024];
        byte[] second = new byte[64 * 1024];
        Arrays.fill(first, (byte) 1);
        Arrays.fill(second, (byte) 2);
        Files.write(target, first);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean reading = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        Future<?> reader = executor.submit(() -> {
            do {
                byte[] observed = Files.readAllBytes(target);
                assertTrue("Observed a partial replacement", Arrays.equals(first, observed) || Arrays.equals(second, observed));
                started.countDown();
            } while (reading.get());
            return null;
        });
        try (IOWorkerSupport support = new IOWorkerSupport(root.toFile())) {
            IOWorkerRuntimeSupport runtime = new IOWorkerRuntimeSupport(support, IOWorkerCodecSupport.identity());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                write(runtime, (i & 1) == 0 ? second : first);
            }
            reading.set(false);
            reader.get(3, TimeUnit.SECONDS);
        } finally {
            reading.set(false);
            executor.shutdownNow();
        }
    }

    private static byte[] read(IOWorkerRuntimeSupport runtime) throws IOException {
        return runtime.read(NAME, (name, input) -> input.readAllBytes());
    }

    private static void write(IOWorkerRuntimeSupport runtime, byte[] value) throws IOException {
        runtime.writeAtomically(NAME, "plate", ".tmp", value, (bytes, output) -> output.write(bytes));
    }

    private static void assertStagingEmpty(Path root) throws IOException {
        try (Stream<Path> staging = Files.list(root.resolve(".tmp"))) {
            assertEquals(0L, staging.count());
        }
    }
}
