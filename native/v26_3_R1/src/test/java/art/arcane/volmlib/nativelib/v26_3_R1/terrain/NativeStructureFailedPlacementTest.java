package art.arcane.volmlib.nativelib.v26_3_R1.terrain;


import art.arcane.volmlib.nativelib.terrain.NativeBlockChangeSet;
import art.arcane.volmlib.nativelib.terrain.structure.StructureCachePolicy;
import art.arcane.volmlib.nativelib.terrain.structure.StructureOwnershipRecordView;
import art.arcane.volmlib.nativelib.terrain.structure.StructurePlacementDecision;
import art.arcane.volmlib.nativelib.terrain.structure.StructureReferencePolicy;
import art.arcane.volmlib.nativelib.terrain.structure.StructureStagePolicy;
import art.arcane.volmlib.nativelib.terrain.structure.StructureStartPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NativeStructureFailedPlacementTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void partialStructureWritesAreInvalidatedWhenGenerationFails() {
        Fixture fixture = new Fixture();
        NativeStructureGenerationException failure = fixture.failDuringClearing();

        assertSame(fixture.placementFailure, failure.getCause());
        assertTrue(fixture.changes.contains(2, 65, 2));
        verify(fixture.policy).invalidateBlockChanges(null, fixture.changes);
    }

    @Test
    public void invalidationFailurePreservesOriginalPlacementFailure() {
        Fixture fixture = new Fixture();
        IllegalStateException invalidationFailure = new IllegalStateException("invalidation failed");
        doThrow(invalidationFailure).when(fixture.policy).invalidateBlockChanges(null, fixture.changes);
        NativeStructureGenerationException failure = fixture.failDuringClearing();

        assertSame(fixture.placementFailure, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(invalidationFailure, failure.getSuppressed()[0].getCause());
    }

    private static final class Fixture {
        private final WorldGenLevel world = mock(WorldGenLevel.class);
        private final ChunkAccess chunk = mock(ChunkAccess.class);
        private final StructureManager manager = mock(StructureManager.class);
        private final IllegalStateException placementFailure = new IllegalStateException("clearing failed after writing");
        private final NativeBlockChangeSet changes = NativeBlockChangeSet.create(
                new NativeBlockChangeSet.Bounds(-1, -1, 1, 1, -64, 384));
        private final StructureStagePolicy<Object, StructureStartPlan, Ownership> policy;
        private final NativeBukkitStructureStage<Object, Object, StructureStartPlan, Ownership> stage;

        @SuppressWarnings("unchecked")
        private Fixture() {
            policy = mock(StructureStagePolicy.class);
            StructureReferencePolicy<StructureStartPlan, Ownership> structures = mock(StructureReferencePolicy.class);
            StructureCachePolicy<Object> cache = mock(StructureCachePolicy.class);
            RegistryAccess registries = mock(RegistryAccess.class);
            Registry<Structure> registry = mock(Registry.class);
            Structure structure = mock(Structure.class);
            StructureStart start = mock(StructureStart.class);
            StructurePlacementDecision decision = mock(StructurePlacementDecision.class);
            Holder<Biome> biome = Holder.direct(mock(Biome.class));
            when(world.registryAccess()).thenReturn(registries);
            when(registries.lookupOrThrow(Registries.STRUCTURE)).thenReturn(registry);
            when(registry.iterator()).thenAnswer(invocation -> List.of(structure).iterator());
            when(registry.getKey(structure)).thenReturn(Identifier.parse("minecraft:village_plains"));
            when(structure.step()).thenReturn(GenerationStep.Decoration.SURFACE_STRUCTURES);
            when(manager.shouldGenerateStructures()).thenReturn(true);
            when(manager.startsForStructure(anyInt(), anyInt(), any(Structure.class))).thenReturn(List.of(start));
            when(start.getChunkPos()).thenReturn(new ChunkPos(0, 0));
            when(start.getBoundingBox()).thenReturn(new BoundingBox(0, 60, 0, 15, 80, 15));
            when(policy.structurePolicy(any())).thenReturn(structures);
            when(structures.resolve(anyString(), anyBoolean())).thenReturn(decision);
            when(decision.generate()).thenReturn(true);
            when(policy.protectedPositions(any())).thenReturn((x, y, z) -> false);
            when(policy.worldgenHeight(any(), anyInt(), anyBoolean())).thenReturn((x, z) -> 64);
            when(policy.trackBlockChanges(any(), any())).thenReturn(changes);
            when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
            when(chunk.getMinY()).thenReturn(-64);
            when(chunk.getHeight()).thenReturn(384);
            when(world.getMinY()).thenReturn(-64);
            when(world.getHeight()).thenReturn(384);
            when(world.getBiome(any())).thenReturn(biome);
            when(world.getBiomeManager()).thenReturn(new BiomeManager((x, y, z) -> biome, 7L));
            when(world.setBlock(any(), any(), anyInt(), anyInt())).thenReturn(true);
            stage = new NativeBukkitStructureStage<>(new NativeBukkitStructureStage.Configuration<>(
                    null, null, -64, policy, cache, null));
        }

        private NativeStructureGenerationException failDuringClearing() {
            try (MockedStatic<WorldgenTerrainHeightmaps> heights = mockStatic(WorldgenTerrainHeightmaps.class);
                 MockedStatic<NativeStructureOwnershipRecovery> ownership = mockStatic(NativeStructureOwnershipRecovery.class);
                 MockedStatic<NativeStructureVegetationClearer> vegetation = mockStatic(NativeStructureVegetationClearer.class)) {
                vegetation.when(() -> NativeStructureVegetationClearer.clearIntersectingVegetation(any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            WorldGenLevel placementWorld = invocation.getArgument(0);
                            placementWorld.setBlock(new BlockPos(2, 65, 2), Blocks.AIR.defaultBlockState(), 2);
                            throw placementFailure;
                        });
                return assertThrows(NativeStructureGenerationException.class,
                        () -> stage.placeVanillaStructures(world, chunk, manager));
            }
        }
    }

    private interface Ownership extends StructureOwnershipRecordView<Ownership> {
    }
}
