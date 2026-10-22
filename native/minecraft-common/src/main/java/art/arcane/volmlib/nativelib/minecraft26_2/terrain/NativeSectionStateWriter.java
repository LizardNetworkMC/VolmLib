package art.arcane.volmlib.nativelib.minecraft26_2.terrain;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Arrays;

public final class NativeSectionStateWriter {
    private static final int SECTION_CELLS = 4096;
    private static final int UNWRITTEN = -1;
    private static final int AIR_VALUE = 0;
    private static final int LINEAR_BITS = 4;
    private static final int MAX_PALETTE_BITS = 8;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ThreadLocal<NativeSectionStateWriter> CURRENT = ThreadLocal.withInitial(NativeSectionStateWriter::new);

    private final BlockState[] cells = new BlockState[SECTION_CELLS];
    private final int[] values = new int[SECTION_CELLS];
    private final Reference2IntOpenHashMap<BlockState> valueIds = new Reference2IntOpenHashMap<>();
    private final int[] order = new int[1 << MAX_PALETTE_BITS];
    private BlockState[] states = new BlockState[64];
    private int[] positions = new int[64];
    private int valueCount;
    private int orderSize;
    private FriendlyByteBuf buffer;

    private NativeSectionStateWriter() {
        valueIds.defaultReturnValue(UNWRITTEN);
    }

    public static NativeSectionStateWriter current() {
        return CURRENT.get();
    }

    public BlockState[] cells() {
        return cells;
    }

    public boolean writeFresh(LevelChunkSection section) {
        PalettedContainer<BlockState> container = section.getStates();
        if (container.maybeHas(state -> state != AIR)) {
            return false;
        }

        int bits = simulatePaletteGrowth();
        if (bits < 0) {
            return false;
        }
        if (bits > 0) {
            container.read(encode(bits));
            section.recalcBlockCounts();
        }
        return true;
    }

    private int simulatePaletteGrowth() {
        valueIds.clear();
        valueCount = 0;
        value(AIR);
        orderSize = 0;
        append(AIR_VALUE);
        int bits = 0;
        int capacity = 1;
        BlockState lastState = null;
        int value = UNWRITTEN;
        for (int index = 0; index < SECTION_CELLS; index++) {
            BlockState state = cells[index];
            if (state == null) {
                values[index] = UNWRITTEN;
                continue;
            }
            if (state != lastState) {
                value = value(state);
                lastState = state;
            }
            values[index] = value;
            if (positions[value] >= 0) {
                continue;
            }
            if (orderSize < capacity) {
                append(value);
                continue;
            }
            int grown = bits == 0 ? LINEAR_BITS : bits + 1;
            if (grown > MAX_PALETTE_BITS) {
                return UNWRITTEN;
            }
            reorderByFirstAppearance(index);
            append(value);
            bits = grown;
            capacity = 1 << grown;
        }
        return bits;
    }

    private void reorderByFirstAppearance(int end) {
        for (int position = 0; position < orderSize; position++) {
            positions[order[position]] = UNWRITTEN;
        }
        orderSize = 0;
        for (int index = 0; index < end; index++) {
            int value = values[index] == UNWRITTEN ? AIR_VALUE : values[index];
            if (positions[value] < 0) {
                append(value);
            }
        }
        if (positions[AIR_VALUE] < 0) {
            append(AIR_VALUE);
        }
    }

    private FriendlyByteBuf encode(int bits) {
        if (buffer == null) {
            buffer = new FriendlyByteBuf(Unpooled.buffer(8 + 5 * order.length + SECTION_CELLS));
        }
        buffer.clear();
        buffer.writeByte(bits);
        buffer.writeVarInt(orderSize);
        for (int position = 0; position < orderSize; position++) {
            buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(states[order[position]]));
        }
        int valuesPerLong = 64 / bits;
        for (int start = 0; start < SECTION_CELLS; start += valuesPerLong) {
            int end = Math.min(SECTION_CELLS, start + valuesPerLong);
            long packed = 0L;
            for (int index = start; index < end; index++) {
                int value = values[index] == UNWRITTEN ? AIR_VALUE : values[index];
                packed |= (long) positions[value] << ((index - start) * bits);
            }
            buffer.writeLong(packed);
        }
        return buffer;
    }

    private int value(BlockState state) {
        int value = valueIds.getInt(state);
        if (value != UNWRITTEN) {
            return value;
        }
        value = valueCount++;
        if (value == states.length) {
            states = Arrays.copyOf(states, value << 1);
            positions = Arrays.copyOf(positions, value << 1);
        }
        states[value] = state;
        positions[value] = UNWRITTEN;
        valueIds.put(state, value);
        return value;
    }

    private void append(int value) {
        positions[value] = orderSize;
        order[orderSize++] = value;
    }
}
