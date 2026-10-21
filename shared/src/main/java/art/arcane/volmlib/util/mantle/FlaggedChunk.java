package art.arcane.volmlib.util.mantle;

import art.arcane.volmlib.util.data.Varint;
import art.arcane.volmlib.util.mantle.flag.MantleFlag;
import art.arcane.volmlib.util.parallel.AtomicBooleanArray;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public abstract class FlaggedChunk {
    private static final int FLAG_COUNT = MantleFlag.MAX_ORDINAL + 1;
    private static final int CLAIM_WORDS = (FLAG_COUNT + Long.SIZE - 1) >>> 6;
    private static final VarHandle CLAIMS = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle CLAIM_MONITOR;

    static {
        try {
            CLAIM_MONITOR = MethodHandles.lookup().findVarHandle(FlaggedChunk.class, "claimMonitor", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicBooleanArray flags = new AtomicBooleanArray(FLAG_COUNT);
    private final long[] claims = new long[CLAIM_WORDS];
    private volatile Object claimMonitor;
    private volatile int claimWaiters;

    protected FlaggedChunk() {
    }

    public abstract boolean isClosed();

    protected void copyFrom(FlaggedChunk other, Runnable action) {
        claimAll();
        try {
            action.run();
            for (int i = 0; i < flags.length(); i++) {
                flags.set(i, other.flags.get(i));
            }
        } finally {
            releaseAll();
        }
    }

    public boolean isFlagged(MantleFlag flag) {
        return flags.get(flag.ordinal());
    }

    public void flag(MantleFlag flag, boolean value) {
        if (isClosed()) {
            throw new MantleClosedException("Chunk is closed!");
        }
        flags.set(flag.ordinal(), value);
    }

    public boolean isClaimed(MantleFlag flag) {
        return claimed(flag.ordinal());
    }

    /**
     * Claims the right to raise {@code flag} without waiting. The caller that gets {@code true} runs the flag's work
     * and then calls {@link #completeClaim} or, when the work failed, {@link #abandonClaim}. Returns {@code false}
     * when the flag is already raised or another caller holds the claim.
     */
    public boolean tryClaim(MantleFlag flag) {
        if (isClosed()) {
            throw new MantleClosedException("Chunk is closed!");
        }
        int index = flag.ordinal();
        if (flags.get(index) || !claim(index)) {
            return false;
        }
        if (flags.get(index)) {
            release(index);
            return false;
        }
        return true;
    }

    public void completeClaim(MantleFlag flag) {
        int index = flag.ordinal();
        try {
            if (flags.getAndSet(index, true)) {
                throw new IllegalStateException("Flag " + flag.name() + " was already set after task ran!");
            }
        } finally {
            release(index);
        }
    }

    public void abandonClaim(MantleFlag flag) {
        release(flag.ordinal());
    }

    public void raiseFlagSuspend(MantleFlag flag, Runnable task) {
        if (isClosed()) {
            throw new MantleClosedException("Chunk is closed!");
        }
        int index = flag.ordinal();
        while (!flags.get(index)) {
            if (!claim(index)) {
                awaitClaim(index);
                continue;
            }
            boolean raised = false;
            try {
                if (flags.get(index)) {
                    return;
                }
                task.run();
                raised = true;
            } finally {
                if (raised) {
                    completeClaim(flag);
                } else {
                    release(index);
                }
            }
            return;
        }
    }

    public void raiseFlagUnchecked(MantleFlag flag, Runnable task) {
        if (isClosed()) {
            throw new MantleClosedException("Chunk is closed!");
        }
        int index = flag.ordinal();
        if (flags.compareAndSet(index, false, true)) {
            try {
                task.run();
            } catch (Throwable throwable) {
                flags.set(index, false);
                throw throwable;
            }
        }
    }

    protected void readFlags(int version, DataInput din) throws IOException {
        int length = version < 0 ? 16 : Varint.readUnsignedVarInt(din);

        if (version >= 1) {
            int i = 0;
            while (i < length) {
                byte raw = din.readByte();
                int j = 0;
                while (j < Byte.SIZE && i < flags.length()) {
                    flags.set(i, (raw & (1 << j)) != 0);
                    j++;
                    i++;
                }
            }
        } else {
            for (int i = 0; i < length; i++) {
                flags.set(i, din.readBoolean());
            }
        }
    }

    protected void writeFlags(DataOutput dos) throws IOException {
        Varint.writeUnsignedVarInt(flags.length(), dos);
        int count = flags.length();
        int i = 0;
        while (i < count) {
            int packed = 0;
            for (int j = 0; j < Byte.SIZE; j++) {
                if (i >= count) {
                    break;
                }
                if (flags.get(i)) {
                    packed |= 1 << j;
                }
                i++;
            }
            dos.write(packed);
        }
    }

    private boolean claimed(int index) {
        return ((long) CLAIMS.getVolatile(claims, index >>> 6) & (1L << index)) != 0L;
    }

    private boolean claim(int index) {
        long bit = 1L << index;
        int word = index >>> 6;
        long current = (long) CLAIMS.getVolatile(claims, word);
        while ((current & bit) == 0L) {
            long witness = (long) CLAIMS.compareAndExchange(claims, word, current, current | bit);
            if (witness == current) {
                return true;
            }
            current = witness;
        }
        return false;
    }

    private void release(int index) {
        CLAIMS.getAndBitwiseAnd(claims, index >>> 6, ~(1L << index));
        signalClaimWaiters();
    }

    private void claimAll() {
        for (int word = 0; word < CLAIM_WORDS; word++) {
            while (!CLAIMS.compareAndSet(claims, word, 0L, -1L)) {
                awaitWord(word);
            }
        }
    }

    private void releaseAll() {
        for (int word = 0; word < CLAIM_WORDS; word++) {
            CLAIMS.setVolatile(claims, word, 0L);
        }
        signalClaimWaiters();
    }

    private void signalClaimWaiters() {
        if (claimWaiters == 0) {
            return;
        }
        Object monitor = claimMonitor;
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void awaitClaim(int index) {
        Object monitor = claimMonitor();
        boolean interrupted = false;
        synchronized (monitor) {
            claimWaiters++;
            try {
                while (claimed(index) && !flags.get(index)) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            } finally {
                claimWaiters--;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitWord(int word) {
        Object monitor = claimMonitor();
        boolean interrupted = false;
        synchronized (monitor) {
            claimWaiters++;
            try {
                while ((long) CLAIMS.getVolatile(claims, word) != 0L) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            } finally {
                claimWaiters--;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Object claimMonitor() {
        Object monitor = claimMonitor;
        if (monitor != null) {
            return monitor;
        }
        Object created = new Object();
        Object witness = CLAIM_MONITOR.compareAndExchange(this, (Object) null, created);
        return witness == null ? created : witness;
    }
}
