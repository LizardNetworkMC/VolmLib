package art.arcane.volmlib.util.cache;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class WorldCache2DDoubleTest {
    @Test
    public void batchesOnlyUniqueMissesAndPreservesSpecialDoubleValues() {
        AtomicInteger scalarCalls = new AtomicInteger();
        AtomicInteger bulkCalls = new AtomicInteger();
        WorldCache2DDouble cache = new WorldCache2DDouble((x, z) -> {
            scalarCalls.incrementAndGet();
            return -0D;
        }, 16);
        long[] coordinates = {CacheKey.key(0, 0), CacheKey.key(-1, 4), CacheKey.key(-1, 4),
                CacheKey.key(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        cache.get(0, 0);
        double[] values = new double[coordinates.length];
        WorldCache2DDouble.BatchResolver resolver = (missing, count, target) -> {
            bulkCalls.incrementAndGet();
            assertEquals(2, count);
            target[0] = Double.longBitsToDouble(0x7ff8000000000023L);
            target[1] = Double.NEGATIVE_INFINITY;
        };
        cache.getBatch(coordinates, coordinates.length, values, resolver);
        cache.getBatch(coordinates, coordinates.length, values, resolver);
        assertEquals(1, scalarCalls.get());
        assertEquals(1, bulkCalls.get());
        assertEquals(Double.doubleToRawLongBits(-0D), Double.doubleToRawLongBits(values[0]));
        assertEquals(0x7ff8000000000023L, Double.doubleToRawLongBits(values[1]));
        assertEquals(Double.doubleToRawLongBits(values[1]), Double.doubleToRawLongBits(values[2]));
        assertEquals(Double.NEGATIVE_INFINITY, values[3], 0D);
    }

    @Test
    public void failedBatchesPublishNothingAndCanRetry() {
        WorldCache2DDouble cache = new WorldCache2DDouble((x, z) -> 99D, 16);
        long[] coordinates = {CacheKey.key(2, 3), CacheKey.key(-7, -9)};
        double[] values = new double[2];
        assertThrows(IllegalStateException.class, () -> cache.getBatch(coordinates, 2, values,
                (missing, count, target) -> {
                    target[0] = 42D;
                    throw new IllegalStateException("unavailable");
                }));
        cache.getBatch(coordinates, 2, values, (missing, count, target) -> {
            assertEquals(2, count);
            target[0] = 1D;
            target[1] = 2D;
        });
        assertEquals(1D, cache.get(2, 3), 0D);
        assertEquals(2D, cache.get(-7, -9), 0D);
        assertThrows(IllegalArgumentException.class,
                () -> cache.getBatch(new long[4097], 4097, new double[4097], (missing, count, target) -> {}));
    }

    @Test
    public void interleavedFillsAndReadsRetainSignedChunkIdentity() {
        AtomicInteger calls = new AtomicInteger();
        WorldCache2DDouble cache = new WorldCache2DDouble((x, z) -> {
            calls.incrementAndGet();
            return x * 0.5D - z * 0.25D;
        }, 16);
        int[][] chunks = {{0, 0}, {-1, 1}, {1, -1}, {134217727, -134217728}, {-134217728, 134217727}};
        Object[] boxed = new Object[256];
        double[] values = new double[256];
        for (int round = 0; round < 2; round++) {
            for (int[] chunk : chunks) {
                int x = chunk[0] << 4;
                int z = chunk[1] << 4;
                assertEquals(x * 0.5D - z * 0.25D, cache.get(x, z), 0D);
                cache.fillChunk(chunk[0], chunk[1], boxed);
                cache.fillChunk(chunk[0], chunk[1], values);
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        double expected = (x + localX) * 0.5D - (z + localZ) * 0.25D;
                        assertEquals(expected, (Double) boxed[localZ * 16 + localX], 0D);
                        assertEquals(expected, values[localZ * 16 + localX], 0D);
                        assertEquals(expected, cache.get(x + localX, z + localZ), 0D);
                    }
                }
            }
        }
        assertEquals(chunks.length * 256, calls.get());
    }

    @Test
    public void getCachesResolvedValuesPerCoordinate() {
        AtomicInteger calls = new AtomicInteger();
        WorldCache2DDouble cache = new WorldCache2DDouble((x, z) -> {
            calls.incrementAndGet();
            return (x * 31D) + z;
        }, 16);

        double first = cache.get(12, -7);
        double second = cache.get(12, -7);

        assertEquals(first, second, 0D);
        assertEquals(1, calls.get());
    }

    @Test
    public void evictedChunksAreNotRetainedOutsideTheDeclaredCapacity() {
        AtomicInteger calls = new AtomicInteger();
        WorldCache2DDouble cache = new WorldCache2DDouble((x, z) -> calls.incrementAndGet(), 1);

        assertEquals(1D, cache.get(0, 0), 0D);
        assertEquals(2D, cache.get(16, 0), 0D);
        assertEquals(3D, cache.get(0, 0), 0D);
        assertEquals(3, calls.get());
        assertEquals(256L, cache.getSize());
    }

    @Test
    public void maximumChunkCapacityCanGrowWithoutDiscardingCachedValues() {
        AtomicInteger calls = new AtomicInteger();
        WorldCache2DDouble cache = new WorldCache2DDouble((x, z) -> calls.incrementAndGet(), 1);

        assertEquals(1D, cache.get(0, 0), 0D);
        cache.setMaximumChunks(4);
        assertEquals(1D, cache.get(0, 0), 0D);

        assertEquals(1, calls.get());
        assertEquals(1_024L, cache.getMaxSize());
        assertThrows(IllegalArgumentException.class, () -> cache.setMaximumChunks(0));
    }

}
