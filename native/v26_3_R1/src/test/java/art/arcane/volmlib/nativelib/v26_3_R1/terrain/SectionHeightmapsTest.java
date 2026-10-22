package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.function.IntBinaryOperator;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Mockito.mock;

public class SectionHeightmapsTest {
    private static final int SECTIONS = 8;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = SECTIONS * 16;
    private static final List<Heightmap.Types> FINAL = List.of(
            Heightmap.Types.OCEAN_FLOOR,
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void sectionScanMatchesVanillaPriming() {
        for (long seed = 1; seed <= 40; seed++) {
            ProtoChunk vanilla = chunk(seed);
            ProtoChunk scanned = chunk(seed);
            presetHeightmaps(vanilla, seed);
            presetHeightmaps(scanned, seed);
            Heightmap.primeHeightmaps(vanilla, EnumSet.copyOf(FINAL));
            int[][] heights = SectionHeightmaps.scan(scanned, FINAL);
            for (int index = 0; index < FINAL.size(); index++) {
                SectionHeightmaps.write(scanned, FINAL.get(index), heights[index], null);
            }
            for (Heightmap.Types type : FINAL) {
                assertArrayEquals("seed " + seed + " " + type,
                        vanilla.getOrCreateHeightmapUnprimed(type).getRawData(),
                        scanned.getOrCreateHeightmapUnprimed(type).getRawData());
            }
        }
    }

    @Test
    public void fallbackFillsOnlyColumnsNoBlockSatisfies() {
        IntBinaryOperator terrainTop = (x, z) -> MIN_Y + 1 + ((x * 7 + z * 3) % HEIGHT);
        for (long seed = 1; seed <= 40; seed++) {
            ProtoChunk vanilla = chunk(seed);
            ProtoChunk scanned = chunk(seed);
            Heightmap motion = vanilla.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
            SimpleBitStorage preset = new SimpleBitStorage(Mth.ceillog2(HEIGHT + 1), 256);
            for (int column = 0; column < 256; column++) {
                preset.set(column, terrainTop.applyAsInt(column & 15, column >> 4) - MIN_Y);
            }
            motion.setRawData(vanilla, Heightmap.Types.MOTION_BLOCKING, preset.getRaw());
            Heightmap.primeHeightmaps(vanilla, EnumSet.of(Heightmap.Types.MOTION_BLOCKING));
            int[][] heights = SectionHeightmaps.scan(scanned, List.of(Heightmap.Types.MOTION_BLOCKING));
            SectionHeightmaps.write(scanned, Heightmap.Types.MOTION_BLOCKING, heights[0], terrainTop);
            assertArrayEquals("seed " + seed, motion.getRawData(),
                    scanned.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING).getRawData());
        }
    }

    private static List<BlockState> pool() {
        return List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.GLASS.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.SNOW.defaultBlockState(),
                Blocks.KELP.defaultBlockState());
    }

    @SuppressWarnings("unchecked")
    private static ProtoChunk chunk(long seed) {
        List<BlockState> pool = pool();
        Random random = new Random(seed);
        LevelChunkSection[] sections = new LevelChunkSection[SECTIONS];
        double sectionFill = random.nextDouble();
        for (int index = 0; index < SECTIONS; index++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(
                    Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), null);
            LevelChunkSection section = new LevelChunkSection(states, (PalettedContainer<Holder<Biome>>) mock(PalettedContainer.class));
            if (random.nextDouble() < sectionFill) {
                double density = random.nextDouble() * random.nextDouble();
                for (int cell = 0; cell < 4096; cell++) {
                    if (random.nextDouble() < density) {
                        section.setBlockState(cell & 15, cell >> 8, (cell >> 4) & 15, pool.get(random.nextInt(pool.size())), false);
                    }
                }
            }
            sections[index] = section;
        }
        return new ProtoChunk(new ChunkPos(2, -5), UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(), LevelHeightAccessor.create(MIN_Y, HEIGHT),
                mock(PalettedContainerFactory.class), (BlendingData) null);
    }

    private static void presetHeightmaps(ProtoChunk chunk, long seed) {
        Random random = new Random(seed * 31L);
        for (Heightmap.Types type : FINAL) {
            SimpleBitStorage storage = new SimpleBitStorage(Mth.ceillog2(HEIGHT + 1), 256);
            for (int column = 0; column < 256; column++) {
                storage.set(column, random.nextInt(HEIGHT + 1));
            }
            chunk.getOrCreateHeightmapUnprimed(type).setRawData(chunk, type, storage.getRaw());
        }
    }
}
