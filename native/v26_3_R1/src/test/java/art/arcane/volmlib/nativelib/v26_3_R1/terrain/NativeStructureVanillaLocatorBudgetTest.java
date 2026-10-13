package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NativeStructureVanillaLocatorBudgetTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void unavailableStartsStopAfterSixtyFourChunkLoads() {
        Fixture fixture = new Fixture();
        fixture.add(randomSpread());
        fixture.requireLoads();

        assertNull(fixture.locate(50));

        verify(fixture.level, times(64)).getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS));
        verify(fixture.manager, never()).addReference(any());
    }

    @Test(timeout = 30_000)
    public void missingStartsShareFiveHundredTwelveChecksAcrossTargets() {
        Fixture fixture = new Fixture();
        RandomSpreadStructurePlacement placement = randomSpread();
        fixture.add(placement);
        fixture.add(placement);
        when(fixture.manager.checkStructurePresence(any(), any(), any(), eq(true)))
                .thenReturn(StructureCheckResult.START_NOT_PRESENT);

        assertNull(fixture.locate(Integer.MAX_VALUE));

        verify(fixture.manager, times(512)).checkStructurePresence(any(), any(), any(), eq(true));
        verify(fixture.level, never()).getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS));
    }

    @Test
    public void nativeCandidateSurvivesExhaustionOfAnotherPlacement() {
        Fixture fixture = new Fixture();
        ConcentricRingsStructurePlacement concentric = mock(ConcentricRingsStructurePlacement.class);
        Holder<Structure> target = fixture.add(concentric);
        ChunkPos chunk = new ChunkPos(10, 10);
        when(fixture.state.getRingPositionsFor(concentric)).thenReturn(List.of(chunk));
        when(concentric.getLocatePos(chunk)).thenReturn(new BlockPos(168, 32, 168));
        StructureStart start = fixture.existing(target, chunk);
        fixture.add(randomSpread());

        NativeStructureVanillaLocator.Candidate found = fixture.locate(50);

        assertNotNull(found);
        assertSame(target, found.result().getSecond());
        assertEquals(new BlockPos(168, 32, 168), found.result().getFirst());
        verify(fixture.level, times(64)).getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS));
        verify(fixture.manager, never()).addReference(any());
        assertTrue(found.reference(fixture.manager));
        assertFalse(found.reference(fixture.manager));
        verify(fixture.manager).addReference(start);
    }

    @Test
    public void candidateSurvivesLaterTargetsExhaustingTheSameRing() {
        Fixture fixture = new Fixture();
        RandomSpreadStructurePlacement firstPlacement = randomSpread();
        Holder<Structure> target = fixture.add(firstPlacement);
        ChunkPos chunk = new ChunkPos(0, 0);
        when(firstPlacement.getLocatePos(chunk)).thenReturn(new BlockPos(9, 0, 9));
        fixture.existing(target, chunk);
        for (int index = 0; index < 70; index++) {
            fixture.add(randomSpread());
        }

        NativeStructureVanillaLocator.Candidate found = fixture.locate(50);

        assertNotNull(found);
        assertSame(target, found.result().getSecond());
        verify(fixture.level, times(64)).getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS));
    }

    @Test(timeout = 30_000)
    public void concentricSearchWithoutRandomPlacementsDoesNotLoopThroughRadius() {
        Fixture fixture = new Fixture();
        ConcentricRingsStructurePlacement placement = mock(ConcentricRingsStructurePlacement.class);
        fixture.add(placement);
        when(fixture.state.getRingPositionsFor(placement)).thenReturn(List.of());

        assertNull(fixture.locate(Integer.MAX_VALUE));

        verify(fixture.manager, never()).checkStructurePresence(any(), any(), any(), eq(true));
    }

    @Test
    public void candidateGenerationStopsEvenWhenAllConcentricPositionsAreFarther() {
        Fixture fixture = new Fixture();
        ConcentricRingsStructurePlacement placement = mock(ConcentricRingsStructurePlacement.class);
        Holder<Structure> target = fixture.add(placement);
        ChunkPos origin = new ChunkPos(0, 0);
        List<ChunkPos> positions = new ArrayList<>(5_000);
        positions.add(origin);
        for (int index = 1; index < 5_000; index++) {
            positions.add(new ChunkPos(index, index));
        }
        when(fixture.state.getRingPositionsFor(placement)).thenReturn(positions);
        when(placement.getLocatePos(origin)).thenReturn(new BlockPos(8, 32, 8));
        fixture.existing(target, origin);

        assertNotNull(fixture.locate(50));

        verify(fixture.level).getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS));
    }

    @Test
    public void unrelatedStorageFailureStillPropagates() {
        Fixture fixture = new Fixture();
        fixture.add(randomSpread());
        IllegalStateException failure = new IllegalStateException("Structure storage unavailable");
        when(fixture.manager.checkStructurePresence(any(), any(), any(), eq(true))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.locate(50)));
    }

    private static RandomSpreadStructurePlacement randomSpread() {
        RandomSpreadStructurePlacement placement = mock(RandomSpreadStructurePlacement.class);
        when(placement.spacing()).thenReturn(32);
        when(placement.getPotentialStructureChunk(anyLong(), anyInt(), anyInt())).thenAnswer(invocation ->
                new ChunkPos(invocation.getArgument(1), invocation.getArgument(2)));
        return placement;
    }

    private static final class Fixture {
        private final ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        private final StructureManager manager = mock(StructureManager.class);
        private final ChunkGeneratorStructureState state = mock(ChunkGeneratorStructureState.class);
        private final ChunkAccess loaded = mock(ChunkAccess.class);
        private final List<Holder<Structure>> holders = new ArrayList<>();

        private Fixture() {
            when(level.getServer().getWorldGenSettings().options().generateStructures()).thenReturn(true);
            when(level.getChunkSource().getGeneratorState()).thenReturn(state);
            when(level.structureManager()).thenReturn(manager);
            when(loaded.getPos()).thenReturn(new ChunkPos(0, 0));
            when(level.getChunk(anyInt(), anyInt(), eq(ChunkStatus.STRUCTURE_STARTS))).thenReturn(loaded);
        }

        private Holder<Structure> add(StructurePlacement placement) {
            Holder<Structure> holder = Holder.direct(mock(Structure.class));
            holders.add(holder);
            when(state.getPlacementsForStructure(holder)).thenReturn(List.of(placement));
            return holder;
        }

        private void requireLoads() {
            when(manager.checkStructurePresence(any(), any(), any(), eq(true)))
                    .thenReturn(StructureCheckResult.CHUNK_LOAD_NEEDED);
        }

        private StructureStart existing(Holder<Structure> holder, ChunkPos origin) {
            requireLoads();
            StructureStart start = mock(StructureStart.class);
            when(start.isValid()).thenReturn(true);
            when(start.canBeReferenced()).thenReturn(true);
            when(start.getChunkPos()).thenReturn(origin);
            when(manager.getStartForStructure(holder.value(), loaded)).thenReturn(start);
            return start;
        }

        private NativeStructureVanillaLocator.Candidate locate(int radius) {
            return NativeStructureVanillaLocator.predict(level, HolderSet.direct(holders), BlockPos.ZERO, radius, true);
        }
    }
}
