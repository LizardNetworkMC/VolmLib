package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import art.arcane.volmlib.nativelib.terrain.NativeBlockState;
import art.arcane.volmlib.nativelib.terrain.NativeBlockVolume;
import art.arcane.volmlib.nativelib.terrain.NativeSectionVolume;
import ca.spottedleaf.moonrise.common.list.ShortList;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ChunkDataSectionWriterTest {
    private static final int SECTIONS = 6;
    private static final int ACCESS_MIN_Y = -32;
    private static final int HEIGHT = SECTIONS * 16;

    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void lowDiversityVolumesMatchThePerBlockWrite() {
        List<BlockState> pool = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(),
                Blocks.SCULK_SENSOR.defaultBlockState(),
                Blocks.MOVING_PISTON.defaultBlockState());
        for (long seed = 1; seed <= 12; seed++) {
            assertEquivalent(seed, random -> pool.get(random), pool.size(), 0.25D, false);
        }
    }

    @Test
    public void wideAndOversizedPalettesMatchThePerBlockWrite() {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        for (int span : new int[]{17, 40, 200, 256, 257, 900}) {
            int offset = 1000;
            assertEquivalent(span, index -> Block.BLOCK_STATE_REGISTRY.byId((offset + index) % registrySize), span, 0.1D, false);
        }
    }

    @Test
    public void uniformAndEmptySectionsMatchThePerBlockWrite() {
        assertEquivalent(7, index -> Blocks.STONE.defaultBlockState(), 1, 0.0D, false);
        assertEquivalent(8, index -> Blocks.AIR.defaultBlockState(), 1, 0.0D, false);
        assertEquivalent(9, index -> Blocks.STONE.defaultBlockState(), 1, 1.0D, false);
    }

    @Test
    public void prepopulatedSectionsMatchThePerBlockWrite() {
        List<BlockState> pool = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(),
                Blocks.WATER.defaultBlockState());
        for (long seed = 20; seed <= 24; seed++) {
            assertEquivalent(seed, random -> pool.get(random), pool.size(), 0.4D, true);
        }
    }

    @Test
    public void explicitCaveAirClearsVerticalRunsAcrossSectionBoundaries() {
        NativeBlockState dirt = handle(Blocks.DIRT.defaultBlockState());
        NativeBlockState coal = handle(Blocks.COAL_ORE.defaultBlockState());
        NativeBlockState caveAir = handle(Blocks.CAVE_AIR.defaultBlockState());
        NativeBlockState[] cells = new NativeBlockState[16 * HEIGHT * 16];
        for (int y = 0; y < HEIGHT; y++) {
            int blockY = y + ACCESS_MIN_Y;
            cells[index(14, y, 6)] = blockY >= 33 && blockY <= 54 ? caveAir : dirt;
            cells[index(0, y, 5)] = blockY >= 34 && blockY <= 55 ? caveAir : coal;
        }
        NativeSectionVolume volume = sections(cells, false);

        for (boolean prepopulate : new boolean[]{false, true}) {
            FakeChunk chunk = new FakeChunk();
            if (prepopulate) {
                for (int y = 0; y < HEIGHT; y++) {
                    chunk.section(y).setBlockState(14, y & 15, 6, Blocks.DIRT.defaultBlockState(), false);
                    chunk.section(y).setBlockState(0, y & 15, 5, Blocks.COAL_ORE.defaultBlockState(), false);
                }
            }

            assertTrue(new ChunkDataSectionWriter(chunk.access, volume, ACCESS_MIN_Y).write(0, HEIGHT));

            for (int y = 0; y < HEIGHT; y++) {
                int blockY = y + ACCESS_MIN_Y;
                BlockState first = blockY >= 33 && blockY <= 54
                        ? Blocks.CAVE_AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                BlockState second = blockY >= 34 && blockY <= 55
                        ? Blocks.CAVE_AIR.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
                assertSame("first column at " + blockY, first, chunk.section(y).getBlockState(14, y & 15, 6));
                assertSame("second column at " + blockY, second, chunk.section(y).getBlockState(0, y & 15, 5));
            }
        }
    }

    @Test
    public void installedContainersMatchTheNetworkLayoutLoad() throws ReflectiveOperationException {
        Field presetValues = ChunkDataSectionWriter.class.getDeclaredField("PRESET_VALUES");
        presetValues.setAccessible(true);
        assertNotNull(presetValues.get(null));
        assertContainersMatchNetworkLoad(null);
    }

    @Test
    public void antiXrayPresetContainersMatchTheNetworkLayoutLoad() {
        assertContainersMatchNetworkLoad(new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
                Blocks.NETHERRACK.defaultBlockState(), Blocks.END_STONE.defaultBlockState()});
    }

    private static void assertContainersMatchNetworkLoad(BlockState[] presetValues) {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        for (int span : new int[]{1, 2, 3, 16, 17, 40, 200, 256, 300}) {
            for (long seed = 1; seed <= 3; seed++) {
                for (double nullChance : new double[]{0.0D, 0.3D}) {
                    int offset = 2000 + span;
                    NativeSectionVolume volume = volume(span * 31L + seed,
                            index -> Block.BLOCK_STATE_REGISTRY.byId((offset + index) % registrySize), span, nullChance);
                    FakeChunk direct = new FakeChunk(presetValues);
                    FakeChunk network = new FakeChunk(presetValues);
                    assertTrue(new ChunkDataSectionWriter(direct.access, volume, ACCESS_MIN_Y).write(0, HEIGHT));
                    networkLoad(network, volume);
                    for (int sectionIndex = 0; sectionIndex < SECTIONS; sectionIndex++) {
                        String label = "span " + span + " seed " + seed + " null " + nullChance + " section " + sectionIndex;
                        PalettedContainer<BlockState> expected = network.sections[sectionIndex].getStates();
                        PalettedContainer<BlockState> actual = direct.sections[sectionIndex].getStates();
                        assertEquals(label, expected.data.configuration(), actual.data.configuration());
                        assertArrayEquals(label, networkBytes(expected), networkBytes(actual));
                        assertEquals(label, expected.data.palette().getSize(), actual.data.palette().getSize());
                    }
                }
            }
        }
    }

    private static void networkLoad(FakeChunk chunk, NativeSectionVolume volume) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int sectionIndex = 0; sectionIndex < SECTIONS; sectionIndex++) {
            Map<BlockState, Integer> ids = new IdentityHashMap<>();
            List<BlockState> palette = new ArrayList<>();
            int[] cells = new int[4096];
            boolean written = false;
            boolean unwritten = false;
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int index = (y << 8) | (z << 4) | x;
                        NativeBlockState platformState = volume.getStoredRaw(x, (sectionIndex << 4) + y, z);
                        if (platformState == null) {
                            cells[index] = -1;
                            unwritten = true;
                            continue;
                        }
                        BlockState state = ((CraftBlockData) platformState.placementHandle()).getState();
                        cells[index] = ids.computeIfAbsent(state, key -> {
                            palette.add(key);
                            return palette.size() - 1;
                        });
                        written = true;
                    }
                }
            }
            if (!written) {
                continue;
            }
            int airId = unwritten ? ids.computeIfAbsent(air, key -> {
                palette.add(key);
                return palette.size() - 1;
            }) : -1;
            LevelChunkSection section = chunk.sections[sectionIndex];
            if (palette.size() > 256) {
                for (int index = 0; index < 4096; index++) {
                    if (cells[index] < 0) {
                        continue;
                    }
                    BlockState state = palette.get(cells[index]);
                    int x = index & 15;
                    int y = index >> 8;
                    int z = (index >> 4) & 15;
                    if (state.isAir() && section.getBlockState(x, y, z) == state) {
                        continue;
                    }
                    section.setBlockState(x, y, z, state, false);
                }
                continue;
            }
            if (palette.size() == 1 && palette.get(0) == air) {
                continue;
            }
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            if (palette.size() == 1) {
                buffer.writeByte(0);
                buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(palette.get(0)));
            } else {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                buffer.writeByte(bits);
                buffer.writeVarInt(palette.size());
                for (BlockState state : palette) {
                    buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(state));
                }
                int valuesPerLong = 64 / bits;
                for (int start = 0; start < 4096; start += valuesPerLong) {
                    long packed = 0L;
                    for (int index = start; index < Math.min(4096, start + valuesPerLong); index++) {
                        packed |= (long) (cells[index] < 0 ? airId : cells[index]) << ((index - start) * bits);
                    }
                    buffer.writeLong(packed);
                }
            }
            section.getStates().read(buffer);
        }
    }

    private static byte[] networkBytes(PalettedContainer<BlockState> container) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer, null, 0);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    @Test
    public void writtenBlockCountsMatchTheRecount() throws ReflectiveOperationException {
        Field sectionCounts = ChunkDataSectionWriter.class.getDeclaredField("SECTION_COUNTS");
        sectionCounts.setAccessible(true);
        assertNotNull(sectionCounts.get(null));
        List<BlockState> ticking = new ArrayList<>();
        List<BlockState> still = new ArrayList<>();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            (state.isRandomlyTicking() ? ticking : still).add(state);
        }
        Random pick = new Random(5L);
        for (int span : new int[]{1, 2, 3, 6, 17, 33, 65, 130, 255}) {
            List<BlockState> pool = new ArrayList<>();
            pool.add(Blocks.AIR.defaultBlockState());
            for (int index = 1; index < span; index++) {
                List<BlockState> source = pick.nextInt(3) == 0 ? still : ticking;
                pool.add(source.get(pick.nextInt(source.size())));
            }
            for (long seed = 1; seed <= 2; seed++) {
                assertCountsMatchRecount(span * 100L + seed, pool::get, pool.size(), 0.2D);
                assertCountsMatchRecount(span * 100L + seed + 50L, pool::get, pool.size(), 0.0D);
            }
        }
    }

    private static void assertCountsMatchRecount(long seed, IntFunction<BlockState> palette, int paletteSize, double nullChance)
            throws ReflectiveOperationException {
        FakeChunk chunk = new FakeChunk();
        assertTrue(new ChunkDataSectionWriter(chunk.access, volume(seed, palette, paletteSize, nullChance), ACCESS_MIN_Y)
                .write(0, HEIGHT));
        for (int sectionIndex = 0; sectionIndex < SECTIONS; sectionIndex++) {
            LevelChunkSection section = chunk.sections[sectionIndex];
            List<Short> written = countSnapshot(section);
            section.recalcBlockCounts();
            assertEquals("seed " + seed + " section " + sectionIndex, countSnapshot(section), written);
        }
    }

    private static List<Short> countSnapshot(LevelChunkSection section) throws ReflectiveOperationException {
        List<Short> snapshot = new ArrayList<>();
        for (String name : new String[]{"nonEmptyBlockCount", "fluidCount", "tickingBlockCount", "tickingFluidCount",
                "specialCollidingBlocks"}) {
            Field field = LevelChunkSection.class.getDeclaredField(name);
            field.setAccessible(true);
            snapshot.add(field.getShort(section));
        }
        ShortList ticking = section.moonrise$getTickingBlockList();
        for (int index = 0; index < ticking.size(); index++) {
            snapshot.add(ticking.getRaw(index));
        }
        return snapshot;
    }

    private static NativeSectionVolume volume(long seed, IntFunction<BlockState> palette, int paletteSize, double nullChance) {
        Random random = new Random(seed);
        NativeBlockState[] cells = new NativeBlockState[16 * HEIGHT * 16];
        Map<BlockState, NativeBlockState> handles = new IdentityHashMap<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = random.nextInt(8) == 0 ? -1 : random.nextInt(HEIGHT);
                for (int y = 0; y <= top; y++) {
                    if (random.nextDouble() < nullChance) {
                        continue;
                    }
                    BlockState state = palette.apply(random.nextInt(paletteSize));
                    cells[index(x, y, z)] = handles.computeIfAbsent(state, ChunkDataSectionWriterTest::handle);
                }
                if (top >= 0 && cells[index(x, top, z)] == null) {
                    cells[index(x, top, z)] = handles.computeIfAbsent(Blocks.STONE.defaultBlockState(), ChunkDataSectionWriterTest::handle);
                }
            }
        }
        return sections(cells, (seed & 1L) == 0L);
    }

    private static NativeSectionVolume sections(NativeBlockState[] cells, boolean reversePalette) {
        Map<NativeBlockState, Integer> ids = new IdentityHashMap<>();
        List<NativeBlockState> distinct = new ArrayList<>();
        for (NativeBlockState state : cells) {
            if (state != null && ids.putIfAbsent(state, distinct.size() + 1) == null) {
                distinct.add(state);
            }
        }
        if (reversePalette) {
            Collections.reverse(distinct);
            for (int index = 0; index < distinct.size(); index++) {
                ids.put(distinct.get(index), index + 1);
            }
        }
        NativeBlockState[] palette = new NativeBlockState[distinct.size() + 1];
        for (int index = 0; index < distinct.size(); index++) {
            palette[index + 1] = distinct.get(index);
        }
        short[][] sections = new short[SECTIONS][];
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    NativeBlockState state = cells[index(x, y, z)];
                    if (state == null) {
                        continue;
                    }
                    if (sections[y >> 4] == null) {
                        sections[y >> 4] = new short[4096];
                    }
                    sections[y >> 4][((y & 15) << 8) | (z << 4) | x] = (short) (int) ids.get(state);
                }
            }
        }
        return new NativeSectionVolume() {
            @Override
            public int sectionCount() {
                return SECTIONS;
            }

            @Override
            public short[] section(int sectionIndex) {
                return sections[sectionIndex];
            }

            @Override
            public NativeBlockState[] palette() {
                return palette.clone();
            }

            @Override
            public NativeBlockState getStoredRaw(int x, int y, int z) {
                return cells[index(x, y, z)];
            }
        };
    }

    private static void assertEquivalent(long seed, IntFunction<BlockState> palette, int paletteSize, double nullChance, boolean prepopulate) {
        NativeSectionVolume volume = volume(seed, palette, paletteSize, nullChance);
        int minY = ACCESS_MIN_Y;

        FakeChunk legacy = new FakeChunk();
        FakeChunk bulk = new FakeChunk();
        if (prepopulate) {
            Random fill = new Random(seed * 31L);
            for (int i = 0; i < 600; i++) {
                int x = fill.nextInt(16);
                int y = fill.nextInt(HEIGHT);
                int z = fill.nextInt(16);
                BlockState state = fill.nextBoolean() ? Blocks.BARREL.defaultBlockState() : Blocks.CAVE_AIR.defaultBlockState();
                legacy.section(y).setBlockState(x, y & 15, z, state, false);
                bulk.section(y).setBlockState(x, y & 15, z, state, false);
            }
        }

        legacyWrite(legacy.access, volume, minY, 0, HEIGHT);
        assertTrue(new ChunkDataSectionWriter(bulk.access, volume, minY).write(0, HEIGHT));

        for (int sectionIndex = 0; sectionIndex < SECTIONS; sectionIndex++) {
            LevelChunkSection expected = legacy.sections[sectionIndex];
            LevelChunkSection actual = bulk.sections[sectionIndex];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertSame("seed " + seed + " section " + sectionIndex + " cell " + x + "," + y + "," + z,
                                expected.getBlockState(x, y, z), actual.getBlockState(x, y, z));
                    }
                }
            }
            assertEquals(expected.hasOnlyAir(), actual.hasOnlyAir());
            assertEquals(expected.hasFluid(), actual.hasFluid());
            assertEquals(expected.isRandomlyTickingBlocks(), actual.isRandomlyTickingBlocks());
            assertEquals(expected.isRandomlyTickingFluids(), actual.isRandomlyTickingFluids());
            assertEquals(expected.moonrise$hasSpecialCollidingBlocks(), actual.moonrise$hasSpecialCollidingBlocks());
            assertEquals(tickingSet(expected), tickingSet(actual));
        }
        assertEquals(legacy.blockEntityLog, bulk.blockEntityLog);
    }

    private static Set<Short> tickingSet(LevelChunkSection section) {
        Set<Short> set = new HashSet<>();
        for (int i = 0; i < section.moonrise$getTickingBlockList().size(); i++) {
            set.add(section.moonrise$getTickingBlockList().getRaw(i));
        }
        return set;
    }

    private static int index(int x, int y, int z) {
        return (z * 16 * HEIGHT) + (y << 4) + x;
    }

    private static NativeBlockState handle(BlockState state) {
        CraftBlockData data = mock(CraftBlockData.class, withSettings().stubOnly());
        when(data.getState()).thenReturn(state);
        NativeBlockState nativeState = mock(NativeBlockState.class, withSettings().stubOnly());
        when(nativeState.placementHandle()).thenReturn(data);
        return nativeState;
    }

    private static void legacyWrite(ChunkAccess access, NativeBlockVolume data, int minY, int yStart, int yEnd) {
        int accessMinY = access.getMinY();
        int baseX = access.getPos().getMinBlockX();
        int baseZ = access.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int y = yStart; y < yEnd; y++) {
                int blockY = y + minY;
                LevelChunkSection section = access.getSection((blockY - accessMinY) >> 4);
                int sectionY = blockY & 15;
                for (int x = 0; x < 16; x++) {
                    NativeBlockState platformState = data.getStoredRaw(x, y, z);
                    if (platformState == null) {
                        continue;
                    }
                    BlockState state = ((CraftBlockData) platformState.placementHandle()).getState();
                    if (state.isAir() && section.getBlockState(x, sectionY, z) == state) {
                        continue;
                    }
                    BlockState oldState = section.setBlockState(x, sectionY, z, state, false);
                    if (state.hasBlockEntity()) {
                        BlockPos pos = new BlockPos(baseX + x, blockY, baseZ + z);
                        BlockEntity entity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                        if (entity == null) {
                            access.removeBlockEntity(pos);
                        } else {
                            access.setBlockEntity(entity);
                        }
                    } else if (oldState != null && oldState.hasBlockEntity()) {
                        access.removeBlockEntity(new BlockPos(baseX + x, blockY, baseZ + z));
                    }
                }
            }
        }
    }

    private static final class FakeChunk {
        private final LevelChunkSection[] sections = new LevelChunkSection[SECTIONS];
        private final List<String> blockEntityLog = new ArrayList<>();
        private final ChunkAccess access = mock(ChunkAccess.class, withSettings().stubOnly());

        private FakeChunk() {
            this(null);
        }

        @SuppressWarnings("unchecked")
        private FakeChunk(BlockState[] presetValues) {
            for (int i = 0; i < SECTIONS; i++) {
                PalettedContainer<BlockState> states = new PalettedContainer<>(
                        Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), presetValues);
                sections[i] = new LevelChunkSection(states, (PalettedContainer<Holder<Biome>>) mock(PalettedContainer.class));
            }
            when(access.getMinY()).thenReturn(ACCESS_MIN_Y);
            when(access.getHeight()).thenReturn(HEIGHT);
            when(access.getPos()).thenReturn(new ChunkPos(3, -7));
            when(access.getSection(anyInt())).thenAnswer(invocation -> sections[(int) invocation.getArgument(0)]);
            when(access.getSectionIndex(anyInt())).thenAnswer(invocation -> ((int) invocation.getArgument(0) - ACCESS_MIN_Y) >> 4);
            doAnswer(invocation -> {
                BlockEntity entity = invocation.getArgument(0);
                blockEntityLog.add("set " + entity.getBlockPos() + " " + entity.getType());
                return null;
            }).when(access).setBlockEntity(any(BlockEntity.class));
            doAnswer(invocation -> {
                blockEntityLog.add("remove " + invocation.getArgument(0));
                return null;
            }).when(access).removeBlockEntity(any(BlockPos.class));
        }

        private LevelChunkSection section(int y) {
            return sections[y >> 4];
        }
    }
}
