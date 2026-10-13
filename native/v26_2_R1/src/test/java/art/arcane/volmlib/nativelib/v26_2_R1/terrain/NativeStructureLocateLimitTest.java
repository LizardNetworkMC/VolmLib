package art.arcane.volmlib.nativelib.v26_2_R1.terrain;

import art.arcane.volmlib.nativelib.minecraft26_2.terrain.NativeStructureVanillaLocator;
import art.arcane.volmlib.nativelib.terrain.structure.StructureLocateCandidate;
import art.arcane.volmlib.nativelib.terrain.structure.StructureLocateSearchAccess;
import art.arcane.volmlib.nativelib.terrain.structure.StructureOwnershipRecordView;
import art.arcane.volmlib.nativelib.terrain.structure.StructureStagePolicy;
import art.arcane.volmlib.nativelib.terrain.structure.StructureStartPlan;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure;
import java.lang.reflect.Method;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NativeStructureLocateLimitTest {
    private static final StructureLocateCandidate LIMITED = new Candidate(false, true, 0, 0, 0);
    private static final StructureLocateCandidate FOUND = new Candidate(true, false, 16, 20, 0);

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void exhaustedSearchReturnsNoTarget() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(LIMITED);
        fixture.add("minecraft:camp", search);

        assertNull(fixture.locate(null));

        verify(search, never()).verify(any());
        verify(search, never()).reference(any());
    }

    @Test
    public void exhaustedHolderDoesNotDiscardAnotherHoldersVerifiedTarget() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> near = search(FOUND);
        StructureLocateSearchAccess<StructureStart, Ownership> limited = search(LIMITED);
        StructureLocateSearchAccess<StructureStart, Ownership> far =
                search(new Candidate(true, false, 64, 20, 0));
        Holder<Structure> nearHolder = fixture.add("minecraft:c_near", near);
        fixture.add("minecraft:b_limited", limited);
        fixture.add("minecraft:a_far", far);
        VerifiedStart verified = new VerifiedStart(startAt(16, 0), mock(Ownership.class));
        when(near.verify(FOUND)).thenReturn(verified);
        when(fixture.policy.locatorY(verified.ownership())).thenReturn(72);
        NativeStructureVanillaLocator.Candidate nativeCandidate =
                nativeCandidate(Pair.of(new BlockPos(128, 64, 0), nearHolder));

        Pair<BlockPos, Holder<Structure>> result = fixture.locate(nativeCandidate);

        assertEquals(new BlockPos(16, 72, 0), result.getFirst());
        assertSame(nearHolder, result.getSecond());
        verify(near).reference(verified);
        verify(limited, never()).verify(any());
        verify(limited, never()).reference(any());
        verify(far, times(StructureLocateSearchAccess.MAX_SELECTED_CANDIDATE_RETRIES - 1)).verify(any());
        verify(far, never()).reference(any());
        verify(nativeCandidate, never()).reference(any());
    }

    @Test
    public void exhaustedHolderIsNotSearchedAgainAfterAnotherCandidateIsRejected() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> limited = search(LIMITED);
        StructureLocateSearchAccess<StructureStart, Ownership> selected = search(FOUND);
        StructureLocateCandidate second = new Candidate(true, false, 32, 20, 0);
        when(selected.predict()).thenReturn(FOUND, second);
        fixture.add("minecraft:a_limited", limited);
        Holder<Structure> holder = fixture.add("minecraft:b_selected", selected);
        VerifiedStart verified = new VerifiedStart(startAt(32, 0), mock(Ownership.class));
        when(selected.verify(second)).thenReturn(verified);
        when(fixture.policy.locatorY(verified.ownership())).thenReturn(72);

        Pair<BlockPos, Holder<Structure>> result = fixture.locate(null);

        assertEquals(new BlockPos(32, 72, 0), result.getFirst());
        assertSame(holder, result.getSecond());
        verify(limited).predict();
        verify(limited, never()).verify(any());
        verify(limited, never()).reference(any());
        verify(selected, times(2)).predict();
        verify(selected).reject(FOUND);
        verify(selected).reference(verified);
    }

    @Test
    public void exhaustedSearchPreservesAndReferencesNativeTarget() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(LIMITED);
        Holder<Structure> holder = fixture.add("minecraft:camp", search);
        Pair<BlockPos, Holder<Structure>> nativeResult = Pair.of(new BlockPos(128, 64, 0), holder);
        NativeStructureVanillaLocator.Candidate nativeCandidate = nativeCandidate(nativeResult);

        assertSame(nativeResult, fixture.locate(nativeCandidate));

        verify(nativeCandidate).reference(fixture.manager);
        verify(search, never()).reference(any());
    }

    @Test
    public void rejectedCandidateLimitReturnsNoTarget() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(FOUND);
        fixture.add("minecraft:camp", search);

        assertNull(fixture.locate(null));

        verify(search, times(StructureLocateSearchAccess.MAX_SELECTED_CANDIDATE_RETRIES)).verify(FOUND);
        verify(search, times(StructureLocateSearchAccess.MAX_SELECTED_CANDIDATE_RETRIES)).reject(FOUND);
        verify(search, never()).reference(any());
    }

    @Test
    public void rejectedCandidateLimitPreservesAndReferencesNativeTarget() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(FOUND);
        Holder<Structure> holder = fixture.add("minecraft:camp", search);
        Pair<BlockPos, Holder<Structure>> nativeResult = Pair.of(new BlockPos(128, 64, 0), holder);
        NativeStructureVanillaLocator.Candidate nativeCandidate = nativeCandidate(nativeResult);

        assertSame(nativeResult, fixture.locate(nativeCandidate));

        verify(search, times(StructureLocateSearchAccess.MAX_SELECTED_CANDIDATE_RETRIES)).verify(FOUND);
        verify(search, times(StructureLocateSearchAccess.MAX_SELECTED_CANDIDATE_RETRIES)).reject(FOUND);
        verify(search, never()).reference(any());
        verify(nativeCandidate).reference(fixture.manager);
    }

    @Test
    public void unrelatedSearchFailureStillPropagates() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(FOUND);
        fixture.add("minecraft:camp", search);
        IllegalStateException failure = new IllegalStateException("Structure placement is invalid");
        when(search.predict()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.locate(null)));

        verify(search, never()).reference(any());
    }

    @Test
    public void unrelatedVerificationFailureStillPropagates() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(FOUND);
        fixture.add("minecraft:camp", search);
        IllegalStateException failure = new IllegalStateException("Structure ownership is invalid");
        when(search.verify(FOUND)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.locate(null)));

        verify(search, never()).reference(any());
    }

    @Test
    public void buriedTreasureMapUsesTheActualChestPiecePosition() throws Exception {
        ChunkPos chunk = new ChunkPos(1, 2);
        Structure.GenerationContext generation = mock(Structure.GenerationContext.class);
        when(generation.chunkPos()).thenReturn(chunk);
        StructurePiecesBuilder pieces = new StructurePiecesBuilder();
        Method generate = BuriedTreasureStructure.class.getDeclaredMethod(
                "generatePieces", StructurePiecesBuilder.class, Structure.GenerationContext.class);
        generate.setAccessible(true);
        generate.invoke(null, pieces, generation);
        StructureStart start = mock(StructureStart.class);
        when(start.getPieces()).thenReturn(pieces.build().pieces());
        Fixture fixture = new Fixture();
        StructureLocateCandidate candidate = new Candidate(true, false, 16, 90, 32);
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(candidate);
        Holder<Structure> holder = fixture.add("minecraft:buried_treasure", search);
        VerifiedStart verified = new VerifiedStart(start, mock(Ownership.class));
        when(search.verify(candidate)).thenReturn(verified);
        when(fixture.policy.locatorY(verified.ownership())).thenReturn(63);

        Pair<BlockPos, Holder<Structure>> result = fixture.locate(null);

        assertEquals(new BlockPos(25, 63, 41), result.getFirst());
        assertSame(holder, result.getSecond());
        verify(search).reference(verified);
    }

    @Test
    public void actualPiecePositionCanBeCloserThanThePredictedChunkOrigin() {
        Fixture fixture = new Fixture();
        StructureLocateCandidate prediction = new Candidate(true, false, 128, 20, 0);
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(prediction);
        Holder<Structure> holder = fixture.add("minecraft:configured_structure", search);
        VerifiedStart verified = new VerifiedStart(startAt(16, 0), mock(Ownership.class));
        when(search.verify(prediction)).thenReturn(verified);
        when(fixture.policy.locatorY(verified.ownership())).thenReturn(72);
        NativeStructureVanillaLocator.Candidate nativeCandidate =
                nativeCandidate(Pair.of(new BlockPos(64, 64, 0), holder));

        Pair<BlockPos, Holder<Structure>> result = fixture.locate(nativeCandidate);

        assertEquals(new BlockPos(16, 72, 0), result.getFirst());
        verify(search).reference(verified);
        verify(nativeCandidate, never()).reference(any());
    }

    @Test
    public void actualPiecePositionCanLeaveTheNativeTargetCloser() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> search = search(FOUND);
        Holder<Structure> holder = fixture.add("minecraft:configured_structure", search);
        VerifiedStart verified = new VerifiedStart(startAt(128, 0), mock(Ownership.class));
        when(search.verify(FOUND)).thenReturn(verified);
        Pair<BlockPos, Holder<Structure>> nativeResult = Pair.of(new BlockPos(64, 64, 0), holder);
        NativeStructureVanillaLocator.Candidate nativeCandidate = nativeCandidate(nativeResult);

        assertSame(nativeResult, fixture.locate(nativeCandidate));

        verify(search, never()).reference(any());
        verify(nativeCandidate).reference(fixture.manager);
    }

    @Test
    public void closestVerifiedHolderWinsDespitePredictedOrdering() {
        Fixture fixture = new Fixture();
        StructureLocateCandidate firstPrediction = new Candidate(true, false, 100, 20, 0);
        StructureLocateCandidate secondPrediction = new Candidate(true, false, 200, 20, 0);
        StructureLocateSearchAccess<StructureStart, Ownership> first = search(firstPrediction);
        StructureLocateSearchAccess<StructureStart, Ownership> second = search(secondPrediction);
        fixture.add("minecraft:a_first", first);
        Holder<Structure> selected = fixture.add("minecraft:b_second", second);
        VerifiedStart firstStart = new VerifiedStart(startAt(500, 0), mock(Ownership.class));
        VerifiedStart secondStart = new VerifiedStart(startAt(210, 0), mock(Ownership.class));
        when(first.verify(firstPrediction)).thenReturn(firstStart);
        when(second.verify(secondPrediction)).thenReturn(secondStart);
        NativeStructureVanillaLocator.Candidate nativeCandidate =
                nativeCandidate(Pair.of(new BlockPos(300, 64, 0), selected));

        Pair<BlockPos, Holder<Structure>> result = fixture.locate(nativeCandidate);

        assertEquals(new BlockPos(210, 0, 0), result.getFirst());
        assertSame(selected, result.getSecond());
        verify(first, never()).reference(any());
        verify(second).reference(secondStart);
        verify(nativeCandidate, never()).reference(any());
    }

    @Test
    public void repeatedRejectionsDoNotStarveAnotherHoldersVerifiedTarget() {
        Fixture fixture = new Fixture();
        StructureLocateSearchAccess<StructureStart, Ownership> rejected = search(FOUND);
        StructureLocateCandidate prediction = new Candidate(true, false, 128, 20, 0);
        StructureLocateSearchAccess<StructureStart, Ownership> valid = search(prediction);
        fixture.add("minecraft:a_rejected", rejected);
        Holder<Structure> holder = fixture.add("minecraft:b_valid", valid);
        VerifiedStart start = new VerifiedStart(startAt(128, 0), mock(Ownership.class));
        when(valid.verify(prediction)).thenReturn(start);

        Pair<BlockPos, Holder<Structure>> result = fixture.locate(null);

        assertSame(holder, result.getSecond());
        verify(rejected, times(StructureLocateSearchAccess.MAX_SELECTED_CANDIDATE_RETRIES - 1)).verify(FOUND);
        verify(rejected, never()).reference(any());
        verify(valid).verify(prediction);
        verify(valid).reference(start);
    }

    private static StructureStart startAt(int x, int z) {
        StructureStart start = mock(StructureStart.class);
        StructurePiece piece = mock(StructurePiece.class);
        when(piece.getLocatorPosition()).thenReturn(new BlockPos(x, 20, z));
        when(start.getPieces()).thenReturn(List.of(piece));
        return start;
    }

    @SuppressWarnings("unchecked")
    private static StructureLocateSearchAccess<StructureStart, Ownership> search(
            StructureLocateCandidate candidate) {
        StructureLocateSearchAccess<StructureStart, Ownership> search = mock(StructureLocateSearchAccess.class);
        when(search.predict()).thenReturn(candidate);
        return search;
    }

    private static NativeStructureVanillaLocator.Candidate nativeCandidate(
            Pair<BlockPos, Holder<Structure>> result) {
        NativeStructureVanillaLocator.Candidate candidate = mock(NativeStructureVanillaLocator.Candidate.class);
        when(candidate.result()).thenReturn(result);
        return candidate;
    }

    private static final class Fixture {
        private final ServerLevel level = mock(ServerLevel.class);
        private final StructureManager manager = mock(StructureManager.class);
        private final Object context = new Object();
        private final List<Holder<Structure>> holders = new ArrayList<>();
        private final Map<String, StructureLocateSearchAccess<StructureStart, Ownership>> searches = new HashMap<>();
        private final Registry<Structure> registry;
        private final StructureStagePolicy<Object, StructureStartPlan, Ownership> policy;
        private final NativeBukkitStructureStage<Object, Object, StructureStartPlan, Ownership> stage;

        @SuppressWarnings("unchecked")
        private Fixture() {
            registry = mock(Registry.class);
            policy = mock(StructureStagePolicy.class);
            RegistryAccess.Frozen access = mock(RegistryAccess.Frozen.class);
            when(level.registryAccess()).thenReturn(access);
            when(level.structureManager()).thenReturn(manager);
            when(access.lookupOrThrow(Registries.STRUCTURE)).thenReturn(registry);
            when(policy.hasPlacement(eq(context), anyString())).thenReturn(true);
            when(policy.<StructureStart>locateSearch(eq(context), any())).thenAnswer(invocation -> {
                StructureStagePolicy.LocateRequest<StructureStart, Ownership> request = invocation.getArgument(1);
                return searches.get(request.key());
            });
            stage = new NativeBukkitStructureStage<>(
                    new NativeBukkitStructureStage.Configuration<Object, Object, StructureStartPlan, Ownership>(
                            null, null, 0, policy, null, null));
        }

        private Holder<Structure> add(String key, StructureLocateSearchAccess<StructureStart, Ownership> search) {
            Structure structure = mock(Structure.class);
            Holder<Structure> holder = Holder.direct(structure);
            when(registry.getKey(structure)).thenReturn(Identifier.parse(key));
            holders.add(holder);
            searches.put(key, search);
            return holder;
        }

        private Pair<BlockPos, Holder<Structure>> locate(NativeStructureVanillaLocator.Candidate nativeCandidate) {
            return stage.findNearestStructure(
                    level, HolderSet.direct(holders), BlockPos.ZERO, 50, true, context, nativeCandidate);
        }
    }

    private record Candidate(boolean found, boolean limitReached, int originX, int baseY, int originZ)
            implements StructureLocateCandidate {
    }

    private record VerifiedStart(StructureStart start, Ownership ownership)
            implements StructureLocateSearchAccess.Verified<StructureStart, Ownership> {
    }

    private interface Ownership extends StructureOwnershipRecordView<Ownership> {
    }
}
