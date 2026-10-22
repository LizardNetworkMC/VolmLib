package art.arcane.volmlib.util.noise.program;

import java.util.concurrent.atomic.AtomicLong;

final class SignatureHasher {
    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime());

    private long hash = 0x6A09E667F3BCC908L;

    static long unique() {
        return finish(UNIQUE.getAndAdd(0x9E3779B97F4A7C15L) ^ 0x3C6EF372FE94F82BL);
    }

    SignatureHasher add(long value) {
        hash = Long.rotateLeft(hash ^ finish(value + 0x9E3779B97F4A7C15L), 27) * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L;
        return this;
    }

    SignatureHasher add(double value) {
        return add(Double.doubleToRawLongBits(value));
    }

    SignatureHasher add(boolean value) {
        return add(value ? 0x5BE0CD19137E2179L : 0x1F83D9ABFB41BD6BL);
    }

    long value() {
        return finish(hash);
    }

    private static long finish(long value) {
        long mixed = value;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        mixed *= 0xC4CEB9FE1A85EC53L;
        mixed ^= mixed >>> 33;
        return mixed;
    }
}
