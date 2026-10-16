package art.arcane.volmlib.nativelib.terrain;

import java.util.Objects;

public final class NativeBlockChangeSet {
    private static final int SECTION_WORDS = 64;
    private static final NativeBlockChangeSet DISABLED = new NativeBlockChangeSet(new Bounds(0, 0, -1, -1, 0, 0));

    private final Bounds bounds;
    private final int chunkWidth;
    private final int chunkDepth;
    private final int sectionCount;
    private final long[][] sections;
    private int size;

    private NativeBlockChangeSet(Bounds bounds) {
        this.bounds = bounds;
        this.chunkWidth = Math.max(0, bounds.maxChunkX() - bounds.minChunkX() + 1);
        this.chunkDepth = Math.max(0, bounds.maxChunkZ() - bounds.minChunkZ() + 1);
        this.sectionCount = Math.max(0, (bounds.height() + 15) >> 4);
        this.sections = new long[chunkWidth * chunkDepth * sectionCount][];
    }

    public static NativeBlockChangeSet create(Bounds bounds) {
        return new NativeBlockChangeSet(Objects.requireNonNull(bounds, "Block change tracking requires bounds"));
    }

    public static NativeBlockChangeSet disabled() {
        return DISABLED;
    }

    public Bounds bounds() {
        return bounds;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void add(int x, int y, int z) {
        int index = sectionIndex(x >> 4, z >> 4, y);
        if (index < 0) {
            return;
        }
        long[] words = sections[index];
        if (words == null) {
            words = new long[SECTION_WORDS];
            sections[index] = words;
        }
        int cell = cellIndex(x, y, z);
        long mask = 1L << cell;
        if ((words[cell >>> 6] & mask) != 0L) {
            return;
        }
        words[cell >>> 6] |= mask;
        size++;
    }

    public boolean contains(int x, int y, int z) {
        int index = sectionIndex(x >> 4, z >> 4, y);
        if (index < 0 || sections[index] == null) {
            return false;
        }
        int cell = cellIndex(x, y, z);
        return (sections[index][cell >>> 6] & (1L << cell)) != 0L;
    }

    public void forEachChunk(ChunkVisitor visitor) {
        if (size == 0) {
            return;
        }
        for (int offsetZ = 0; offsetZ < chunkDepth; offsetZ++) {
            for (int offsetX = 0; offsetX < chunkWidth; offsetX++) {
                if (hasChunkChanges(offsetX, offsetZ)) {
                    visitor.visit(bounds.minChunkX() + offsetX, bounds.minChunkZ() + offsetZ);
                }
            }
        }
    }

    public void forEachPosition(int chunkX, int chunkZ, PositionVisitor visitor) {
        int offsetX = chunkX - bounds.minChunkX();
        int offsetZ = chunkZ - bounds.minChunkZ();
        if (offsetX < 0 || offsetX >= chunkWidth || offsetZ < 0 || offsetZ >= chunkDepth) {
            return;
        }
        int firstSection = (offsetZ * chunkWidth + offsetX) * sectionCount;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        for (int section = 0; section < sectionCount; section++) {
            long[] words = sections[firstSection + section];
            if (words == null) {
                continue;
            }
            int sectionMinY = bounds.minY() + (section << 4);
            for (int word = 0; word < SECTION_WORDS; word++) {
                long remaining = words[word];
                while (remaining != 0L) {
                    int cell = (word << 6) | Long.numberOfTrailingZeros(remaining);
                    visitor.visit(minX | (cell & 15), sectionMinY + (cell >>> 8), minZ | ((cell >>> 4) & 15));
                    remaining &= remaining - 1L;
                }
            }
        }
    }

    private boolean hasChunkChanges(int offsetX, int offsetZ) {
        int firstSection = (offsetZ * chunkWidth + offsetX) * sectionCount;
        for (int section = 0; section < sectionCount; section++) {
            if (sections[firstSection + section] != null) {
                return true;
            }
        }
        return false;
    }

    private int sectionIndex(int chunkX, int chunkZ, int y) {
        int offsetX = chunkX - bounds.minChunkX();
        int offsetZ = chunkZ - bounds.minChunkZ();
        int offsetY = y - bounds.minY();
        if (offsetX < 0 || offsetX >= chunkWidth || offsetZ < 0 || offsetZ >= chunkDepth
                || offsetY < 0 || offsetY >= bounds.height()) {
            return -1;
        }
        return (offsetZ * chunkWidth + offsetX) * sectionCount + (offsetY >> 4);
    }

    private int cellIndex(int x, int y, int z) {
        return (((y - bounds.minY()) & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public record Bounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, int minY, int height) {
    }

    @FunctionalInterface
    public interface ChunkVisitor {
        void visit(int chunkX, int chunkZ);
    }

    @FunctionalInterface
    public interface PositionVisitor {
        void visit(int x, int y, int z);
    }
}
