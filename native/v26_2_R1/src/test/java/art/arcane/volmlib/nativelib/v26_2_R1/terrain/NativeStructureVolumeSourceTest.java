package art.arcane.volmlib.nativelib.v26_2_R1.terrain;

import art.arcane.volmlib.nativelib.minecraft26_2.terrain.NativeStructureVolumeSource;
import art.arcane.volmlib.nativelib.minecraft26_2.terrain.NativeStructureSurfaceFitter;
import art.arcane.volmlib.nativelib.terrain.structure.NativeStructureVolume;
import art.arcane.volmlib.nativelib.terrain.structure.StructureTerrainSettings;
import art.arcane.volmlib.util.structure.StructureTerrainMode;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidStructure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NativeStructureVolumeSourceTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void flattenSurfacePiecesReserveTheCutOutsideTheirPhysicalBounds() {
        NativeStructureVolume volume = volume(GenerationStep.Decoration.SURFACE_STRUCTURES, flatten());

        assertNotNull(volume.surfaceAdjustment());
        assertEquals(volume.minY() - 1, volume.surfaceAdjustment().anchors().getFirst().groundY());
        assertTrue(volume.intersectsInfluenceRect(volume.minX() - 1, volume.minZ(), volume.minX() - 1, volume.minZ()));
        assertTrue(NativeStructureVolume.surfaceCutY(List.of(volume), volume.minX() - 1, volume.minZ(), volume.minY() + 20) < volume.minY() + 20);
    }

    @Test
    public void sourceFittingKeepsThePhysicalVolume() {
        assertNull(volume(GenerationStep.Decoration.SURFACE_STRUCTURES, StructureTerrainSettings.SOURCE).surfaceAdjustment());
    }

    @Test
    public void undergroundStructuresDoNotReserveSurfaceCuts() {
        assertNull(volume(GenerationStep.Decoration.UNDERGROUND_STRUCTURES, flatten()).surfaceAdjustment());
    }

    @Test
    public void projectedPoolPiecesReserveOnlyTheirActualJunctions() {
        PoolElementStructurePiece piece = mock(PoolElementStructurePiece.class);
        when(piece.getBoundingBox()).thenReturn(new BoundingBox(0, 64, 0, 10, 65, 10));
        when(piece.getJunctions()).thenReturn(List.of());
        Structure structure = new DesertPyramidStructure(new Structure.StructureSettings(
                HolderSet.empty(), Map.of(), GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE));
        StructureStart start = new StructureStart(structure, new ChunkPos(0, 0), 0, new PiecesContainer(List.of(piece)));
        NativeStructureVolume withoutJunction = NativeStructureVolumeSource.appendPieces(null, "minecraft:village_plains", start,
                flatten(), (x, z) -> 63).getFirst();
        assertNull(withoutJunction.surfaceAdjustment());
        when(piece.getJunctions()).thenReturn(List.of(new JigsawJunction(10, 81, 5, 0, StructureTemplatePool.Projection.TERRAIN_MATCHING)));
        NativeStructureVolume withJunction = NativeStructureVolumeSource.appendPieces(null, "minecraft:village_plains", start,
                flatten(), (x, z) -> 63).getFirst();
        assertEquals(List.of(new NativeStructureVolume.SurfaceAnchor(10, 10, 5, 5, 80, 1)),
                withJunction.surfaceAdjustment().anchors());
        assertEquals(80, NativeStructureVolume.surfaceCutY(List.of(withJunction), 0, 5, 80));
    }

    @Test
    public void reservationBlendMatchesFitterRoundingAndBoundaries() {
        assertParity(List.of(new NativeStructureVolume.SurfaceAnchor(0, 0, 0, 0, 99, 1)), 24);
        assertParity(List.of(new NativeStructureVolume.SurfaceAnchor(0, 0, 0, 0, 90, 1),
                new NativeStructureVolume.SurfaceAnchor(20, 20, 0, 0, 110, 1)), 20);
        assertParity(List.of(new NativeStructureVolume.SurfaceAnchor(0, 8, 0, 8, 90, 2),
                new NativeStructureVolume.SurfaceAnchor(4, 12, 4, 12, 85, 2)), 24);
    }

    private static void assertParity(List<NativeStructureVolume.SurfaceAnchor> anchors, int padding) {
        NativeStructureVolume volume = new NativeStructureVolume("minecraft:village_plains", 0, 64, 0, 0, 65, 0,
                new NativeStructureVolume.SurfaceAdjustment(anchors, padding, 64));
        List<NativeStructureSurfaceFitter.FlattenAnchor> expected = new ArrayList<>();
        for (NativeStructureVolume.SurfaceAnchor anchor : anchors) {
            expected.add(new NativeStructureSurfaceFitter.FlattenAnchor(new NativeStructureSurfaceFitter.SurfaceAnchor(
                    anchor.minX(), anchor.maxX(), anchor.minZ(), anchor.maxZ(), anchor.groundY(), anchor.strength()), padding, 64));
        }
        for (int x : new int[]{-25, -24, -23, 0, 6, 10, 12, 20, 23, 24, 25, 44}) {
            for (int z : new int[]{0, 6, 12, 24}) {
                assertEquals(NativeStructureSurfaceFitter.resolveFlattenSurface(expected, x, z, 100).targetY(),
                        NativeStructureVolume.surfaceCutY(List.of(volume), x, z, 100));
            }
        }
    }

    private static NativeStructureVolume volume(GenerationStep.Decoration step, StructureTerrainSettings terrain) {
        Structure structure = new DesertPyramidStructure(new Structure.StructureSettings(
                HolderSet.empty(), Map.of(), step, TerrainAdjustment.NONE));
        DesertPyramidPiece piece = new DesertPyramidPiece(RandomSource.create(7L), 0, 0);
        StructureStart start = new StructureStart(structure, new ChunkPos(0, 0), 0, new PiecesContainer(List.of(piece)));
        return NativeStructureVolumeSource.appendPieces(null, "minecraft:desert_pyramid", start, terrain,
                (x, z) -> piece.getBoundingBox().minY() - 1).getFirst();
    }

    private static StructureTerrainSettings flatten() {
        StructureTerrainSettings terrain = mock(StructureTerrainSettings.class);
        when(terrain.resolvedMode()).thenReturn(StructureTerrainMode.FLATTEN);
        when(terrain.resolvedFlattenRange()).thenReturn(64);
        when(terrain.getHorizontalPadding()).thenReturn(24);
        return terrain;
    }
}
