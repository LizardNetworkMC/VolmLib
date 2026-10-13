package art.arcane.volmlib.util.cache;

import art.arcane.volmlib.util.function.IntIntToDoubleFunction;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Objects;

public class WorldCache2DDouble {
    private static final int RECENT_KEY_SLOTS = 64;
    private static final int MAXIMUM_REFRESH_INTERVAL = 64;

    private final ConcurrentLinkedHashMap<Long, ChunkCache2DDouble> chunks;
    private final int refreshInterval;
    private final IntIntToDoubleFunction resolver;
    private final ThreadLocal<RecentChunk> recent = ThreadLocal.withInitial(RecentChunk::new);

    public WorldCache2DDouble(IntIntToDoubleFunction resolver, int size) {
        this.resolver = resolver;
        this.refreshInterval = Math.max(1, Math.min(MAXIMUM_REFRESH_INTERVAL, size / 4));
        this.chunks = new ConcurrentLinkedHashMap.Builder<Long, ChunkCache2DDouble>()
                .initialCapacity(size)
                .maximumWeightedCapacity(size)
                .concurrencyLevel(Math.max(32, Runtime.getRuntime().availableProcessors() * 4))
                .build();
    }

    public double get(int x, int z) {
        long key = CacheKey.key(x >> 4, z >> 4);
        ChunkCache2DDouble chunk = chunkFor(key);
        return chunk.get(x, z, resolver);
    }

    public void getBatch(long[] coordinates, int count, double[] target, BatchResolver batchResolver) {
        Objects.checkFromIndexSize(0, count, coordinates.length);
        Objects.checkFromIndexSize(0, count, target.length);
        if (count > 4096) {
            throw new IllegalArgumentException("Height batch exceeds 4096 samples");
        }
        Objects.requireNonNull(batchResolver, "batchResolver");
        long[] missing = new long[count];
        int[] slots = new int[count];
        ChunkCache2DDouble[] owners = new ChunkCache2DDouble[count];
        Long2IntOpenHashMap indices = new Long2IntOpenHashMap(count);
        indices.defaultReturnValue(-1);
        int size = 0;
        for (int index = 0; index < count; index++) {
            long coordinate = coordinates[index];
            int x = CacheKey.keyX(coordinate);
            int z = CacheKey.keyZ(coordinate);
            ChunkCache2DDouble chunk = chunkFor(CacheKey.key(x >> 4, z >> 4));
            if (chunk.copyIfReady(x, z, target, index)) {
                slots[index] = -1;
                continue;
            }
            int slot = indices.get(coordinate);
            if (slot < 0) {
                slot = size++;
                indices.put(coordinate, slot);
                missing[slot] = coordinate;
                owners[slot] = chunk;
            }
            slots[index] = slot;
        }
        if (size == 0) {
            return;
        }
        double[] sampled = new double[size];
        batchResolver.resolve(missing, size, sampled);
        for (int index = 0; index < size; index++) {
            sampled[index] = owners[index].publish(CacheKey.keyX(missing[index]),
                    CacheKey.keyZ(missing[index]), sampled[index]);
        }
        for (int index = 0; index < count; index++) {
            if (slots[index] >= 0) {
                target[index] = sampled[slots[index]];
            }
        }
    }

    public void fillChunk(int chunkX, int chunkZ, Object[] target) {
        if (target == null || target.length != 256) {
            throw new IllegalArgumentException("Expected a 16x16 target array.");
        }

        long key = CacheKey.key(chunkX, chunkZ);
        ChunkCache2DDouble chunk = chunkFor(key);
        int worldX = chunkX << 4;
        int worldZ = chunkZ << 4;
        chunk.fill(worldX, worldZ, target, resolver);
    }

    public void fillChunk(int chunkX, int chunkZ, double[] target) {
        if (target == null || target.length != 256) {
            throw new IllegalArgumentException("Expected a 16x16 target array.");
        }

        long key = CacheKey.key(chunkX, chunkZ);
        ChunkCache2DDouble chunk = chunkFor(key);
        int worldX = chunkX << 4;
        int worldZ = chunkZ << 4;
        chunk.fill(worldX, worldZ, target, resolver);
    }

    public long getSize() {
        return chunks.size() * 256L;
    }

    public long getMaxSize() {
        return chunks.capacity() * 256L;
    }

    public void setMaximumChunks(int maximumChunks) {
        if (maximumChunks <= 0) {
            throw new IllegalArgumentException("maximumChunks must be positive.");
        }
        chunks.setCapacity(maximumChunks);
    }

    private ChunkCache2DDouble chunkFor(long key) {
        RecentChunk recent = this.recent.get();
        if (recent.chunk != null && recent.key == key) {
            return recent.chunk;
        }
        long mixedKey = CacheKey.mix(key);
        ChunkCache2DDouble chunk = recent.refresh(mixedKey, refreshInterval)
                ? chunks.get(mixedKey) : chunks.getQuietly(mixedKey);
        if (chunk == null) {
            chunk = chunks.computeIfAbsent(mixedKey, ignored -> new ChunkCache2DDouble());
        }
        recent.key = key;
        recent.chunk = chunk;
        return chunk;
    }

    @FunctionalInterface
    public interface BatchResolver {
        void resolve(long[] coordinates, int count, double[] target);
    }

    private static final class RecentChunk {
        private final long[] keys = new long[RECENT_KEY_SLOTS];
        private final long[] refreshedAt = new long[RECENT_KEY_SLOTS];
        private long key;
        private ChunkCache2DDouble chunk;
        private long accesses;

        private boolean refresh(long key, int interval) {
            long current = ++accesses;
            int slot = (int) key & (RECENT_KEY_SLOTS - 1);
            if (refreshedAt[slot] != 0L && keys[slot] == key && current - refreshedAt[slot] < interval) {
                return false;
            }
            keys[slot] = key;
            refreshedAt[slot] = current;
            return true;
        }
    }
}
