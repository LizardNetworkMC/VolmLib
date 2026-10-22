package art.arcane.volmlib.nativelib.v26_2_R1.terrain;

import art.arcane.volmlib.nativelib.terrain.NativeBlockState;
import art.arcane.volmlib.nativelib.terrain.NativeSectionVolume;
import ca.spottedleaf.moonrise.common.list.ShortList;
import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Copies a section volume into chunk sections. A section that still holds only plain air is rebuilt in one pass: the
 * chunk palette ids are mapped to a section palette in the per-block visiting order (z, y, x), packed into the
 * container's bit storage and installed as the container's data directly (through the network section layout when the
 * container carries anti-xray preset values); its block counts are then written from the cell ids already in hand,
 * with the ticking positions in the order {@link LevelChunkSection#recalcBlockCounts} lists them (falling back to
 * that recount when the section's count fields cannot be reached). Any other section takes the per-block path. Block
 * entity changes are replayed afterwards in the per-block visiting order (z, y, x).
 */
final class ChunkDataSectionWriter {
    private static final int SECTION_CELLS = 4096;
    private static final int MAX_LOCAL_PALETTE = 256;
    private static final int UNWRITTEN = -1;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Strategy<BlockState> BLOCK_STATES = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
    private static final SectionCounts SECTION_COUNTS = SectionCounts.resolve();
    private static final VarHandle PRESET_VALUES = resolvePresetValues();
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final ChunkAccess access;
    private final NativeSectionVolume data;
    private final int minY;
    private final int baseX;
    private final int baseZ;

    ChunkDataSectionWriter(ChunkAccess access, NativeSectionVolume data, int minY) {
        this.access = access;
        this.data = data;
        this.minY = minY;
        this.baseX = access.getPos().getMinBlockX();
        this.baseZ = access.getPos().getMinBlockZ();
    }

    boolean write(int yStart, int yEnd) {
        int accessMinY = access.getMinY();
        if (((minY - accessMinY) & 15) != 0) {
            return false;
        }

        Scratch scratch = Scratch.borrow();
        try {
            return write(scratch, accessMinY, yStart, yEnd);
        } finally {
            scratch.release();
        }
    }

    private boolean write(Scratch scratch, int accessMinY, int yStart, int yEnd) {
        scratch.beginChunk(data.palette());
        int sectionOffset = (minY - accessMinY) >> 4;
        int sectionCount = data.sectionCount();
        for (int bufferSection = 0; bufferSection < sectionCount; bufferSection++) {
            short[] cells = data.section(bufferSection);
            if (cells == null) {
                continue;
            }
            int bufferBaseY = bufferSection << 4;
            int fromRow = Math.max(0, yStart - bufferBaseY);
            int toRow = Math.min(16, yEnd - bufferBaseY);
            if (fromRow >= toRow) {
                continue;
            }
            int sectionIndex = bufferSection + sectionOffset;
            int sectionBlockY = accessMinY + (sectionIndex << 4);
            if (!writeSection(scratch, access.getSection(sectionIndex), cells, sectionBlockY, fromRow, toRow)) {
                return false;
            }
        }

        applyBlockEntities(scratch);
        return true;
    }

    private boolean writeSection(Scratch scratch, LevelChunkSection section, short[] cells, int sectionBlockY,
                                 int fromRow, int toRow) {
        scratch.beginSection();
        int[] ids = scratch.ids;
        int[] localIds = scratch.localByChunk;
        Arrays.fill(ids, 0, fromRow << 8, UNWRITTEN);
        Arrays.fill(ids, toRow << 8, SECTION_CELLS, UNWRITTEN);
        boolean written = false;
        boolean unwritten = fromRow > 0 || toRow < 16;
        int lastChunkId = 0;
        int lastId = UNWRITTEN;
        for (int z = 0; z < 16; z++) {
            for (int row = fromRow; row < toRow; row++) {
                int rowIndex = (row << 8) | (z << 4);
                for (int x = 0; x < 16; x++) {
                    int chunkId = cells[rowIndex | x] & 0xFFFF;
                    if (chunkId == 0) {
                        ids[rowIndex | x] = UNWRITTEN;
                        unwritten = true;
                        continue;
                    }
                    if (chunkId != lastChunkId) {
                        int id = localIds[chunkId];
                        if (id == UNWRITTEN) {
                            BlockState state = scratch.resolve(chunkId);
                            if (state == null) {
                                return false;
                            }
                            id = scratch.paletteId(state);
                            localIds[chunkId] = id;
                        }
                        lastId = id;
                        lastChunkId = chunkId;
                    }
                    ids[rowIndex | x] = lastId;
                    written = true;
                }
            }
        }

        if (!written) {
            return true;
        }

        int airId = unwritten ? scratch.paletteId(AIR) : UNWRITTEN;
        if (scratch.paletteSize > MAX_LOCAL_PALETTE || section.getStates().maybeHas(state -> state != AIR)) {
            writeCells(scratch, section, sectionBlockY);
            return true;
        }

        if (scratch.paletteSize == 1 && scratch.palette[0] == AIR) {
            return true;
        }

        loadSection(scratch, section, airId);
        recordBlockEntities(scratch, sectionBlockY);
        return true;
    }

    private static BlockState resolve(NativeBlockState platformState) {
        BlockData blockData = (BlockData) platformState.placementHandle();
        return blockData instanceof CraftBlockData craftBlockData ? craftBlockData.getState() : null;
    }

    private static void loadSection(Scratch scratch, LevelChunkSection section, int airId) {
        int paletteSize = scratch.paletteSize;
        int bits = paletteSize == 1 ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        long[] packed = bits == 0 ? null : pack(scratch.ids, bits, airId);
        PalettedContainer<BlockState> states = section.getStates();
        if (PRESET_VALUES == null || (Object[]) PRESET_VALUES.get(states) != null) {
            readNetworkLayout(scratch, states, bits, packed);
        } else {
            List<BlockState> entries = Arrays.asList(Arrays.copyOf(scratch.palette, paletteSize));
            Optional<LongStream> storage = packed == null ? Optional.empty() : Optional.of(LongStream.of(packed));
            states.data = PalettedContainer.unpack(BLOCK_STATES, new PalettedContainerRO.PackedData<>(entries, storage), AIR, null)
                    .getOrThrow().data;
        }
        if (SECTION_COUNTS == null) {
            section.recalcBlockCounts();
        } else {
            writeBlockCounts(scratch, section, airId, bits);
        }
    }

    private static long[] pack(int[] ids, int bits, int airId) {
        int valuesPerLong = 64 / bits;
        long[] packed = new long[(SECTION_CELLS + valuesPerLong - 1) / valuesPerLong];
        int slot = 0;
        for (int start = 0; start < SECTION_CELLS; start += valuesPerLong) {
            int end = Math.min(SECTION_CELLS, start + valuesPerLong);
            long value = 0L;
            for (int index = start; index < end; index++) {
                int id = ids[index];
                value |= (long) (id == UNWRITTEN ? airId : id) << ((index - start) * bits);
            }
            packed[slot++] = value;
        }
        return packed;
    }

    private static void readNetworkLayout(Scratch scratch, PalettedContainer<BlockState> states, int bits, long[] packed) {
        FriendlyByteBuf buffer = scratch.buffer();
        buffer.clear();
        buffer.writeByte(bits);
        if (bits == 0) {
            buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(scratch.palette[0]));
        } else {
            buffer.writeVarInt(scratch.paletteSize);
            for (int id = 0; id < scratch.paletteSize; id++) {
                buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(scratch.palette[id]));
            }
            for (long value : packed) {
                buffer.writeLong(value);
            }
        }
        states.read(buffer);
    }

    /**
     * The counts {@link LevelChunkSection#recalcBlockCounts} derives from the loaded storage, taken from the cell ids
     * instead. The recount visits palette ids in the iteration order of a map keyed in first-appearance order and
     * lists each id's positions in index order; only the ticking list depends on that order, so the map is rebuilt
     * the same way only when more than one ticking id has to be ordered.
     */
    private static void writeBlockCounts(Scratch scratch, LevelChunkSection section, int airId, int bits) {
        int paletteSize = scratch.paletteSize;
        int[] ids = scratch.ids;
        int[] cellCounts = scratch.cellCounts;
        int[] appearance = scratch.appearance;
        Arrays.fill(cellCounts, 0, paletteSize, 0);
        int distinct = 0;
        for (int index = 0; index < SECTION_CELLS; index++) {
            int id = ids[index] == UNWRITTEN ? airId : ids[index];
            if (cellCounts[id]++ == 0) {
                appearance[distinct++] = id;
            }
        }

        int nonEmpty = 0;
        int fluids = 0;
        int ticking = 0;
        int tickingFluids = 0;
        int specialColliding = 0;
        int tickingIds = 0;
        int tickingId = UNWRITTEN;
        for (int id = 0; id < paletteSize; id++) {
            int count = cellCounts[id];
            BlockState state = scratch.palette[id];
            if (count == 0 || state.isAir()) {
                continue;
            }
            if (CollisionUtil.isSpecialCollidingBlock(state)) {
                specialColliding += count;
            }
            nonEmpty += count;
            if (state.isRandomlyTicking()) {
                ticking += count;
                tickingIds++;
                tickingId = id;
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                fluids += count;
                if (fluid.isRandomlyTicking()) {
                    tickingFluids += count;
                }
            }
        }
        SECTION_COUNTS.write(section, (short) nonEmpty, (short) fluids, (short) ticking, (short) tickingFluids,
                (short) specialColliding);

        ShortList tickingBlocks = section.moonrise$getTickingBlockList();
        tickingBlocks.clear();
        if (tickingIds == 0) {
            return;
        }
        tickingBlocks.setMinCapacity(ticking);
        if (tickingIds == 1) {
            addPositions(ids, tickingBlocks, tickingId);
            return;
        }
        Int2ObjectOpenHashMap<BlockState> recountOrder = new Int2ObjectOpenHashMap<>(bits <= 6 ? 1 << bits : 64);
        for (int index = 0; index < distinct; index++) {
            recountOrder.put(appearance[index], scratch.palette[appearance[index]]);
        }
        ObjectIterator<Int2ObjectMap.Entry<BlockState>> entries = recountOrder.int2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            Int2ObjectMap.Entry<BlockState> entry = entries.next();
            if (!entry.getValue().isAir() && entry.getValue().isRandomlyTicking()) {
                addPositions(ids, tickingBlocks, entry.getIntKey());
            }
        }
    }

    private static void addPositions(int[] ids, ShortList list, int id) {
        for (int index = 0; index < SECTION_CELLS; index++) {
            if (ids[index] == id) {
                list.add((short) index);
            }
        }
    }

    private void recordBlockEntities(Scratch scratch, int sectionBlockY) {
        int paletteSize = scratch.paletteSize;
        boolean[] blockEntityIds = null;
        for (int id = 0; id < paletteSize; id++) {
            if (scratch.palette[id].hasBlockEntity()) {
                if (blockEntityIds == null) {
                    blockEntityIds = new boolean[paletteSize];
                }
                blockEntityIds[id] = true;
            }
        }
        if (blockEntityIds == null) {
            return;
        }
        int[] ids = scratch.ids;
        for (int index = 0; index < SECTION_CELLS; index++) {
            int id = ids[index];
            if (id != UNWRITTEN && blockEntityIds[id]) {
                recordBlockEntity(scratch, index & 15, sectionBlockY + (index >> 8), (index >> 4) & 15, false);
            }
        }
    }

    private void writeCells(Scratch scratch, LevelChunkSection section, int sectionBlockY) {
        int[] ids = scratch.ids;
        for (int index = 0; index < SECTION_CELLS; index++) {
            int id = ids[index];
            if (id == UNWRITTEN) {
                continue;
            }
            BlockState state = scratch.palette[id];
            int x = index & 15;
            int y = index >> 8;
            int z = (index >> 4) & 15;
            if (state.isAir() && section.getBlockState(x, y, z) == state) {
                continue;
            }
            BlockState oldState = section.setBlockState(x, y, z, state, false);
            if (state.hasBlockEntity()) {
                recordBlockEntity(scratch, x, sectionBlockY + y, z, false);
            } else if (oldState != null && oldState.hasBlockEntity()) {
                recordBlockEntity(scratch, x, sectionBlockY + y, z, true);
            }
        }
    }

    private void recordBlockEntity(Scratch scratch, int x, int blockY, int z, boolean remove) {
        long order = ((long) z << 40) | ((long) (blockY - access.getMinY()) << 8) | ((long) x << 1) | (remove ? 1L : 0L);
        scratch.blockEntityCells.add(order);
    }

    private void applyBlockEntities(Scratch scratch) {
        if (scratch.blockEntityCells.isEmpty()) {
            return;
        }

        long[] cells = scratch.blockEntityCells.toLongArray();
        Arrays.sort(cells);
        int accessMinY = access.getMinY();
        for (long cell : cells) {
            int z = (int) (cell >>> 40);
            int blockY = (int) ((cell >>> 8) & 0xFFFFFFFFL) + accessMinY;
            int x = (int) ((cell >>> 1) & 15);
            BlockPos pos = new BlockPos(baseX + x, blockY, baseZ + z);
            if ((cell & 1L) != 0L) {
                access.removeBlockEntity(pos);
                continue;
            }
            BlockState state = access.getSection(access.getSectionIndex(blockY)).getBlockState(x, blockY & 15, z);
            BlockEntity entity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
            if (entity == null) {
                access.removeBlockEntity(pos);
            } else {
                access.setBlockEntity(entity);
            }
        }
    }

    private static VarHandle resolvePresetValues() {
        try {
            return MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup())
                    .findVarHandle(PalettedContainer.class, "presetValues", Object[].class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static final class Scratch {
        private final int[] ids = new int[SECTION_CELLS];
        private final BlockState[] palette = new BlockState[SECTION_CELLS + 1];
        private final Reference2IntOpenHashMap<BlockState> paletteIds = new Reference2IntOpenHashMap<>();
        private final LongArrayList blockEntityCells = new LongArrayList();
        private final int[] cellCounts = new int[MAX_LOCAL_PALETTE + 1];
        private final int[] appearance = new int[MAX_LOCAL_PALETTE + 1];
        private static final NativeBlockState[] NO_STATES = new NativeBlockState[0];

        private NativeBlockState[] chunkPalette = NO_STATES;
        private BlockState[] resolved = new BlockState[64];
        private int[] localByChunk = new int[64];
        private int chunkPaletteSize;
        private int paletteSize;
        private FriendlyByteBuf buffer;
        private boolean borrowed;

        private Scratch() {
            paletteIds.defaultReturnValue(UNWRITTEN);
        }

        private static Scratch borrow() {
            Scratch scratch = SCRATCH.get();
            if (scratch.borrowed) {
                scratch = new Scratch();
            }
            scratch.borrowed = true;
            return scratch;
        }

        private void release() {
            chunkPalette = NO_STATES;
            blockEntityCells.clear();
            borrowed = false;
        }

        private void beginChunk(NativeBlockState[] palette) {
            chunkPalette = palette;
            chunkPaletteSize = palette.length;
            if (resolved.length < chunkPaletteSize) {
                int capacity = Integer.highestOneBit(chunkPaletteSize - 1) << 1;
                resolved = new BlockState[capacity];
                localByChunk = new int[capacity];
            } else {
                Arrays.fill(resolved, 0, chunkPaletteSize, null);
            }
            blockEntityCells.clear();
        }

        private void beginSection() {
            paletteIds.clear();
            paletteSize = 0;
            Arrays.fill(localByChunk, 0, chunkPaletteSize, UNWRITTEN);
        }

        private BlockState resolve(int chunkId) {
            BlockState state = resolved[chunkId];
            if (state == null) {
                state = ChunkDataSectionWriter.resolve(chunkPalette[chunkId]);
                resolved[chunkId] = state;
            }
            return state;
        }

        private int paletteId(BlockState state) {
            int id = paletteIds.getInt(state);
            if (id == UNWRITTEN) {
                id = paletteSize++;
                paletteIds.put(state, id);
                palette[id] = state;
            }
            return id;
        }

        private FriendlyByteBuf buffer() {
            if (buffer == null) {
                buffer = new FriendlyByteBuf(Unpooled.buffer(8 + 5 * (MAX_LOCAL_PALETTE + 1) + SECTION_CELLS));
            }
            return buffer;
        }
    }

    /** Write access to the section's private count fields; null when this server build does not have them. */
    private record SectionCounts(VarHandle nonEmpty, VarHandle fluids, VarHandle ticking, VarHandle tickingFluids,
                                 VarHandle specialColliding) {
        private static SectionCounts resolve() {
            try {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(LevelChunkSection.class, MethodHandles.lookup());
                return new SectionCounts(
                        lookup.findVarHandle(LevelChunkSection.class, "nonEmptyBlockCount", short.class),
                        lookup.findVarHandle(LevelChunkSection.class, "fluidCount", short.class),
                        lookup.findVarHandle(LevelChunkSection.class, "tickingBlockCount", short.class),
                        lookup.findVarHandle(LevelChunkSection.class, "tickingFluidCount", short.class),
                        lookup.findVarHandle(LevelChunkSection.class, "specialCollidingBlocks", short.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        private void write(LevelChunkSection section, short nonEmptyCount, short fluidCount, short tickingCount,
                           short tickingFluidCount, short specialCollidingCount) {
            nonEmpty.set(section, nonEmptyCount);
            fluids.set(section, fluidCount);
            ticking.set(section, tickingCount);
            tickingFluids.set(section, tickingFluidCount);
            specialColliding.set(section, specialCollidingCount);
        }
    }
}
