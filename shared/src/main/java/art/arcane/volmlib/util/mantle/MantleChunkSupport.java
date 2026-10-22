package art.arcane.volmlib.util.mantle;

import art.arcane.volmlib.util.io.CountingDataInputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

public abstract class MantleChunkSupport<M> extends FlaggedChunk {
    private static final int LOCKED = Integer.MIN_VALUE;
    private static final int SPINS_BEFORE_YIELD = 64;
    private static final int YIELDS_BEFORE_PARK = 128;
    private static final long MAX_PARK_NANOS = 1_000_000L;
    private static final VarHandle USES;

    static {
        try {
            USES = MethodHandles.lookup().findVarHandle(MantleChunkSupport.class, "uses", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicReferenceArray<M> sections;
    private final int x;
    private final int z;
    private volatile int uses;
    private volatile boolean closed;

    protected MantleChunkSupport(int sectionHeight, int x, int z) {
        super();
        this.sections = new AtomicReferenceArray<>(sectionHeight);
        this.x = x;
        this.z = z;
    }

    protected MantleChunkSupport(int version, int sectionHeight, CountingDataInputStream din) throws IOException {
        this(sectionHeight, din.readByte(), din.readByte());

        // Unsigned on purpose: worlds taller than 2048 blocks have >= 128 sections, and a
        // signed read made the count negative — zero sections read, stream mispositioned,
        // and every later chunk in the plate desynchronized.
        int sectionCount = din.readUnsignedByte();
        readFlags(version, din);

        for (int i = 0; i < sectionCount; i++) {
            onBeforeReadSection(i);

            long size = din.readInt();
            if (size < 0L) {
                throw new IOException("Negative mantle section length: " + size);
            }
            if (size == 0L) {
                continue;
            }

            long start = din.count();
            if (i >= sectionHeight) {
                din.skipTo(start + size);
                continue;
            }

            try {
                sections.set(i, readSection(din));
            } catch (IOException exception) {
                long end = start + size;
                onReadSectionFailure(i, start, end, din, exception);
                throw exception;
            }

            if (din.count() != start + size) {
                throw new IOException("Chunk section read size mismatch!");
            }
        }
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public int sectionCount() {
        return sections.length();
    }

    public void close() {
        closed = true;
        int attempt = 0;
        while (!USES.compareAndSet(this, 0, LOCKED)) {
            backOff(attempt++);
        }
        uses = 0;
    }

    /**
     * Locks out every use when nobody uses the chunk, so no use can start until {@link #unlockUses}.
     */
    public boolean tryLockUses() {
        return USES.compareAndSet(this, 0, LOCKED);
    }

    /**
     * Lifts the lock {@link #tryLockUses} took, closing the chunk first when it is sealed for a save.
     */
    public void unlockUses(boolean seal) {
        if (seal) {
            closed = true;
        }
        uses = 0;
    }

    public void reopen() {
        if (!closed) {
            return;
        }
        if (uses != 0) {
            throw new IllegalStateException("Cannot reopen a chunk while it is in use");
        }
        closed = false;
    }

    public boolean inUse() {
        return uses != 0;
    }

    public MantleChunkSupport<M> use() {
        if (closed) {
            throw new MantleClosedException("Chunk is closed!");
        }

        int attempt = 0;
        int current = uses;
        while (true) {
            if (current >= 0) {
                int witness = (int) USES.compareAndExchange(this, current, current + 1);
                if (witness == current) {
                    break;
                }
                current = witness;
                continue;
            }
            backOff(attempt++);
            current = uses;
        }
        if (closed) {
            release();
            throw new MantleClosedException("Chunk is closed!");
        }

        return this;
    }

    public void release() {
        USES.getAndAdd(this, -1);
    }

    public void copyFrom(MantleChunkSupport<M> chunk) {
        use();
        try {
            super.copyFrom(chunk, () -> {
                for (int i = 0; i < sections.length(); i++) {
                    sections.set(i, chunk.get(i));
                }
            });
        } finally {
            release();
        }
    }

    public boolean exists(int section) {
        return get(section) != null;
    }

    public M get(int section) {
        return sections.get(section);
    }

    public void clear() {
        requireOpen();
        for (int i = 0; i < sections.length(); i++) {
            delete(i);
        }
    }

    public void delete(int section) {
        requireOpen();
        sections.set(section, null);
    }

    public M getOrCreate(int section) {
        requireOpen();
        M sectionData = get(section);
        if (sectionData != null) {
            return sectionData;
        }

        M instance = createSection();
        M value = sections.compareAndExchange(section, null, instance);
        return value == null ? instance : value;
    }

    public void write(DataOutputStream dos) throws IOException {
        close();
        dos.writeByte(x);
        dos.writeByte(z);
        if (sections.length() > 255) {
            throw new IOException("Section count " + sections.length() + " exceeds the single-byte mantle chunk header");
        }
        dos.writeByte(sections.length());
        writeFlags(dos);

        ByteArrayOutputStream bytes = null;
        DataOutputStream sub = null;
        for (int i = 0; i < sections.length(); i++) {
            trimIndex(i);
            if (exists(i)) {
                try {
                    M section = get(i);
                    if (section == null) {
                        dos.writeInt(0);
                        continue;
                    }
                    if (bytes == null) {
                        bytes = new ByteArrayOutputStream(8192);
                        sub = new DataOutputStream(bytes);
                    }
                    writeSection(section, sub);
                    dos.writeInt(bytes.size());
                    bytes.writeTo(dos);
                } finally {
                    if (bytes != null) {
                        bytes.reset();
                    }
                }
            } else {
                dos.writeInt(0);
            }
        }
    }

    public void trimSections() {
        for (int i = 0; i < sections.length(); i++) {
            if (exists(i)) {
                trimIndex(i);
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    protected void trimIndex(int index) {
        if (!exists(index)) {
            return;
        }

        M section = get(index);
        if (section == null) {
            return;
        }

        if (isSectionEmpty(section)) {
            sections.set(index, null);
            return;
        }

        trimSection(section);
        if (isSectionEmpty(section)) {
            sections.set(index, null);
        }
    }

    protected void onBeforeReadSection(int index) {
    }

    protected void onReadSectionFailure(int index, long start, long end, CountingDataInputStream din, IOException error) {
    }

    protected abstract M createSection();

    protected abstract M readSection(CountingDataInputStream din) throws IOException;

    protected abstract void writeSection(M section, DataOutputStream dos) throws IOException;

    protected abstract void trimSection(M section);

    protected abstract boolean isSectionEmpty(M section);

    private void requireOpen() {
        if (closed) {
            throw new MantleClosedException("Chunk is closed!");
        }
    }

    private static void backOff(int attempt) {
        if (attempt < SPINS_BEFORE_YIELD) {
            Thread.onSpinWait();
        } else if (attempt < SPINS_BEFORE_YIELD + YIELDS_BEFORE_PARK) {
            Thread.yield();
        } else {
            LockSupport.parkNanos(Math.min(MAX_PARK_NANOS, 1_000L << Math.min(20, attempt - SPINS_BEFORE_YIELD - YIELDS_BEFORE_PARK)));
        }
    }
}
