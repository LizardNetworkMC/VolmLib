package art.arcane.volmlib.nativelib.minecraft26_2.terrain;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

public class NativeSectionStateWriterTest {
    @BeforeClass
    public static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void lowDiversitySectionsMatchIncrementalWrites() throws ReflectiveOperationException {
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
                Blocks.SCULK_SENSOR.defaultBlockState());
        for (long seed = 1; seed <= 16; seed++) {
            for (double nullChance : new double[]{0.0D, 0.35D, 0.97D}) {
                assertEquivalent(seed, pool::get, pool.size(), nullChance);
            }
        }
    }

    @Test
    public void growingPalettesMatchIncrementalWrites() throws ReflectiveOperationException {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        for (int span : new int[]{2, 15, 16, 17, 31, 32, 33, 64, 65, 129, 255, 256, 257, 400}) {
            for (long seed = 1; seed <= 3; seed++) {
                int offset = 500 + span * 7;
                for (double nullChance : new double[]{0.0D, 0.2D}) {
                    assertEquivalent(span * 13L + seed, index -> Block.BLOCK_STATE_REGISTRY.byId((offset + index) % registrySize),
                            span, nullChance);
                }
            }
        }
    }

    @Test
    public void uniformAndAirOnlySectionsMatchIncrementalWrites() throws ReflectiveOperationException {
        assertEquivalent(3, index -> Blocks.STONE.defaultBlockState(), 1, 0.0D);
        assertEquivalent(4, index -> Blocks.AIR.defaultBlockState(), 1, 0.0D);
        assertEquivalent(5, index -> Blocks.AIR.defaultBlockState(), 1, 0.5D);
        assertEquivalent(6, index -> Blocks.WATER.defaultBlockState(), 1, 0.9D);
    }

    @Test
    public void sectionsHoldingAnythingButAirAreLeftToTheCaller() {
        LevelChunkSection section = section();
        section.setBlockState(1, 2, 3, Blocks.STONE.defaultBlockState(), false);
        NativeSectionStateWriter writer = NativeSectionStateWriter.current();
        Arrays.fill(writer.cells(), Blocks.DIRT.defaultBlockState());

        assertFalse(writer.writeFresh(section));
        assertSame(Blocks.AIR.defaultBlockState(), section.getBlockState(0, 0, 0));
    }

    private static void assertEquivalent(long seed, IntFunction<BlockState> palette, int paletteSize, double nullChance)
            throws ReflectiveOperationException {
        Random random = new Random(seed);
        BlockState[] cells = new BlockState[4096];
        for (int index = 0; index < 4096; index++) {
            if (random.nextDouble() >= nullChance) {
                cells[index] = palette.apply(random.nextInt(paletteSize));
            }
        }
        LevelChunkSection incremental = section();
        for (int index = 0; index < 4096; index++) {
            if (cells[index] != null) {
                incremental.setBlockState(index & 15, index >> 8, (index >> 4) & 15, cells[index], false);
            }
        }
        LevelChunkSection bulk = section();
        NativeSectionStateWriter writer = NativeSectionStateWriter.current();
        System.arraycopy(cells, 0, writer.cells(), 0, 4096);
        boolean installed = writer.writeFresh(bulk);
        if (!installed) {
            for (int index = 0; index < 4096; index++) {
                if (cells[index] != null) {
                    bulk.setBlockState(index & 15, index >> 8, (index >> 4) & 15, cells[index], false);
                }
            }
        }
        String label = "seed " + seed + " span " + paletteSize + " null " + nullChance + " installed " + installed;
        assertTrue(label, installed || paletteSize > 200);
        assertArrayEquals(label, networkBytes(incremental.getStates()), networkBytes(bulk.getStates()));
        assertEquals(label, counts(incremental), counts(bulk));
        assertEquals(label, tickingSet(incremental), tickingSet(bulk));
        for (int index = 0; index < 4096; index++) {
            assertSame(label, incremental.getBlockState(index & 15, index >> 8, (index >> 4) & 15),
                    bulk.getBlockState(index & 15, index >> 8, (index >> 4) & 15));
        }
    }

    @SuppressWarnings("unchecked")
    private static LevelChunkSection section() {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), null);
        return new LevelChunkSection(states, (PalettedContainer<Holder<Biome>>) mock(PalettedContainer.class, withSettings().stubOnly()));
    }

    private static byte[] networkBytes(PalettedContainer<BlockState> container) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer, null, 0);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    private static List<Short> counts(LevelChunkSection section) throws ReflectiveOperationException {
        List<Short> counts = new ArrayList<>();
        for (String name : new String[]{"nonEmptyBlockCount", "fluidCount", "tickingBlockCount", "tickingFluidCount",
                "specialCollidingBlocks"}) {
            Field field = LevelChunkSection.class.getDeclaredField(name);
            field.setAccessible(true);
            counts.add(field.getShort(section));
        }
        return counts;
    }

    private static Set<Short> tickingSet(LevelChunkSection section) {
        Set<Short> set = new HashSet<>();
        for (int index = 0; index < section.moonrise$getTickingBlockList().size(); index++) {
            set.add(section.moonrise$getTickingBlockList().getRaw(index));
        }
        return set;
    }
}
