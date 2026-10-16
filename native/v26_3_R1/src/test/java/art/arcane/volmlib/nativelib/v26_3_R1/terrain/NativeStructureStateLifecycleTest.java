package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import art.arcane.volmlib.nativelib.terrain.NativeStructureBootstrapPolicy;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.junit.BeforeClass;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NativeStructureStateLifecycleTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void publicationWaitsForAllNativeRingFutures() throws Exception {
        Fixture fixture = new Fixture();
        AtomicBoolean published = new AtomicBoolean();
        CompletableFuture<Void> completion = fixture.lifecycle.initializeAndPublishStructureState(
                fixture.state, () -> published.set(true));
        assertFalse(completion.isDone());
        assertFalse(published.get());
        verify(fixture.state).ensureStructuresGenerated();
        fixture.rings.complete(null);
        completion.join();
        assertTrue(published.get());
    }

    @Test
    public void failedRingNeverPublishesAndReportsOriginalFailure() throws Exception {
        Fixture fixture = new Fixture();
        AtomicBoolean published = new AtomicBoolean();
        CompletableFuture<Void> completion = fixture.lifecycle.initializeAndPublishStructureState(
                fixture.state, () -> published.set(true));
        IllegalStateException failure = new IllegalStateException("ring failed");
        fixture.rings.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, completion::join).getCause());
        assertSame(failure, fixture.failure.get());
        assertFalse(published.get());
    }

    @Test
    public void publicationFailureCompletesExceptionallyAndReportsCause() throws Exception {
        Fixture fixture = new Fixture();
        IllegalAccessException failure = new IllegalAccessException("publication failed");
        CompletableFuture<Void> completion = fixture.lifecycle.initializeAndPublishStructureState(
                fixture.state, () -> { throw failure; });
        fixture.rings.complete(null);
        Throwable publication = assertThrows(CompletionException.class, completion::join).getCause();
        assertSame(failure, publication.getCause());
        assertSame(publication, fixture.failure.get());
    }

    @Test
    public void retainStateAcceptsNativeGeneratorBehindWrapper() throws Exception {
        ChunkGenerator nativeGenerator = mock(ChunkGenerator.class);
        OwnerRuntime runtime = new OwnerRuntime(nativeGenerator, wrap(nativeGenerator));
        ChunkGeneratorStructureState state = mock(ChunkGeneratorStructureState.class);
        runtime.lifecycle.retainState(runtime.level, runtime.chunkMap, state);
        when(runtime.chunkSource.getGeneratorState()).thenReturn(state);
        assertSame(state, runtime.lifecycle.retainedState(runtime.level, runtime.chunkMap).structureState());
    }

    @Test
    public void retainStateRejectsForeignActiveGenerator() throws Exception {
        OwnerRuntime runtime = new OwnerRuntime(mock(ChunkGenerator.class), wrap(mock(ChunkGenerator.class)));
        assertThrows(IllegalStateException.class, () -> runtime.lifecycle.retainState(
                runtime.level, runtime.chunkMap, mock(ChunkGeneratorStructureState.class)));
    }

    private static TargetWrapper wrap(ChunkGenerator target) throws ReflectiveOperationException {
        TargetWrapper wrapper = mock(TargetWrapper.class);
        Field field = TargetWrapper.class.getDeclaredField("target");
        field.setAccessible(true);
        field.set(wrapper, target);
        return wrapper;
    }

    private static final class OwnerRuntime {
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        private final ChunkMap chunkMap = mock(ChunkMap.class);
        private final NativeStructureStateLifecycle lifecycle;

        private OwnerRuntime(ChunkGenerator nativeGenerator, ChunkGenerator activeGenerator) throws ReflectiveOperationException {
            Field chunkMapField = ServerChunkCache.class.getDeclaredField("chunkMap");
            chunkMapField.setAccessible(true);
            chunkMapField.set(chunkSource, chunkMap);
            when(level.getChunkSource()).thenReturn(chunkSource);
            when(chunkSource.getGenerator()).thenReturn(activeGenerator);
            lifecycle = new NativeStructureStateLifecycle(new NativeStructureStateLifecycle.Configuration(
                    level, nativeGenerator, mock(NativeStructureBootstrapPolicy.class)));
        }
    }

    abstract static class TargetWrapper extends ChunkGenerator {
        private final ChunkGenerator target;

        protected TargetWrapper(ChunkGenerator target) {
            super(null);
            this.target = target;
        }
    }

    private static final class Fixture implements NativeStructureBootstrapPolicy {
        private final CompletableFuture<Void> rings = new CompletableFuture<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ChunkGeneratorStructureState state = mock(ChunkGeneratorStructureState.class);
        private final NativeStructureStateLifecycle lifecycle = new NativeStructureStateLifecycle(
                new NativeStructureStateLifecycle.Configuration(null, null, this));

        private Fixture() throws IllegalAccessException {
            boolean bound = false;
            for (Field field : ChunkGeneratorStructureState.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())
                        && field.getGenericType().getTypeName().contains(CompletableFuture.class.getName())) {
                    field.setAccessible(true);
                    Map<Object, CompletableFuture<?>> futures = new HashMap<>();
                    futures.put(new Object(), rings);
                    field.set(state, futures);
                    bound = true;
                }
            }
            assertTrue(bound);
        }

        @Override
        public CompletableFuture<Void> start(Runnable claim, Supplier<CompletableFuture<Void>> preparation, Runnable activation) {
            claim.run();
            return preparation.get().thenRun(activation);
        }

        @Override
        public void failed(Throwable cause) {
            failure.set(cause);
        }
    }
}
