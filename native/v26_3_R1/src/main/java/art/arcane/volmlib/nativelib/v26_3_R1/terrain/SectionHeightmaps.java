package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;

final class SectionHeightmaps {
    static final int UNSET = Integer.MIN_VALUE;
    private static final int COLUMNS = 256;
    private static final int MEMO_STATES = 32;

    private SectionHeightmaps() {
    }

    static int[][] scan(ChunkAccess chunk, List<Heightmap.Types> types) {
        int count = types.size();
        int[][] heights = new int[count][COLUMNS];
        for (int[] column : heights) {
            Arrays.fill(column, UNSET);
        }
        int[] remaining = new int[COLUMNS];
        Arrays.fill(remaining, (1 << count) - 1);
        int open = COLUMNS;
        StateMasks masks = new StateMasks(types);
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = sections.length - 1; sectionIndex >= 0 && open > 0; sectionIndex--) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
            PalettedContainer<BlockState> states = section.getStates();
            for (int column = 0; column < COLUMNS; column++) {
                int pending = remaining[column];
                if (pending == 0) {
                    continue;
                }
                int x = column & 15;
                int z = column >> 4;
                for (int y = 15; y >= 0; y--) {
                    int hit = masks.of(states.get(x, y, z)) & pending;
                    if (hit == 0) {
                        continue;
                    }
                    pending &= ~hit;
                    for (int bits = hit; bits != 0; bits &= bits - 1) {
                        heights[Integer.numberOfTrailingZeros(bits)][column] = baseY + y + 1;
                    }
                    if (pending == 0) {
                        break;
                    }
                }
                remaining[column] = pending;
                if (pending == 0) {
                    open--;
                }
            }
        }
        return heights;
    }

    static void write(ChunkAccess chunk, Heightmap.Types type, int[] heights, IntBinaryOperator fallback) {
        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(type);
        int minY = chunk.getMinY();
        SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(chunk.getHeight() + 1), COLUMNS);
        for (int column = 0; column < COLUMNS; column++) {
            int x = column & 15;
            int z = column >> 4;
            int height = heights[column];
            if (height == UNSET) {
                height = fallback == null ? heightmap.getFirstAvailable(x, z) : fallback.applyAsInt(x, z);
            }
            storage.set(column, height - minY);
        }
        long[] packed = storage.getRaw();
        if (packed.length != heightmap.getRawData().length) {
            throw new IllegalStateException("Iris cannot prime " + type + " for chunk " + chunk.getPos()
                    + ": packed " + packed.length + " words for a heightmap storing " + heightmap.getRawData().length);
        }
        heightmap.setRawData(chunk, type, packed);
    }

    private static final class StateMasks {
        private final List<Predicate<BlockState>> predicates;
        private final BlockState[] states = new BlockState[MEMO_STATES];
        private final int[] masks = new int[MEMO_STATES];
        private int size;
        private BlockState lastState;
        private int lastMask;

        private StateMasks(List<Heightmap.Types> types) {
            List<Predicate<BlockState>> resolved = new ArrayList<>(types.size());
            for (Heightmap.Types type : types) {
                resolved.add(type.isOpaque());
            }
            predicates = List.copyOf(resolved);
        }

        private int of(BlockState state) {
            if (state == lastState) {
                return lastMask;
            }
            int mask = lookup(state);
            lastState = state;
            lastMask = mask;
            return mask;
        }

        private int lookup(BlockState state) {
            for (int index = 0; index < size; index++) {
                if (states[index] == state) {
                    return masks[index];
                }
            }
            int mask = 0;
            if (!state.is(Blocks.AIR)) {
                for (int index = 0; index < predicates.size(); index++) {
                    if (predicates.get(index).test(state)) {
                        mask |= 1 << index;
                    }
                }
            }
            if (size < MEMO_STATES) {
                states[size] = state;
                masks[size++] = mask;
            }
            return mask;
        }
    }
}
