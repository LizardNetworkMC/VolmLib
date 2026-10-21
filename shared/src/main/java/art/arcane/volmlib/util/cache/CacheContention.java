package art.arcane.volmlib.util.cache;

import java.util.concurrent.atomic.LongAdder;

public final class CacheContention {
    public static final boolean COUNTING = Boolean.getBoolean("volmlib.cache.countContention");

    private static final LongAdder DUPLICATE_COMPUTATIONS = new LongAdder();

    private CacheContention() {
    }

    public static long duplicateComputations() {
        return DUPLICATE_COMPUTATIONS.sum();
    }

    static void duplicateComputation() {
        DUPLICATE_COMPUTATIONS.increment();
    }
}
