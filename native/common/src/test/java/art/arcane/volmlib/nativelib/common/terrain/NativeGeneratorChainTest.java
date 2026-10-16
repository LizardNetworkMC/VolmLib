package art.arcane.volmlib.nativelib.common.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class NativeGeneratorChainTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void findReturnsRootWhenRootIsTheRequestedType() {
        OwnedGenerator owned = mock(OwnedGenerator.class);

        assertSame(owned, NativeGeneratorChain.find(owned, OwnedGenerator.class));
    }

    @Test
    public void findResolvesThroughPrivateFinalDelegate() throws Exception {
        OwnedGenerator owned = mock(OwnedGenerator.class);
        TargetWrapper wrapper = wrap(owned);

        assertSame(owned, NativeGeneratorChain.find(wrapper, OwnedGenerator.class));
    }

    @Test
    public void findResolvesThroughNestedWrappers() throws Exception {
        OwnedGenerator owned = mock(OwnedGenerator.class);
        TargetWrapper outer = wrap(wrap(owned));

        assertSame(owned, NativeGeneratorChain.find(outer, OwnedGenerator.class));
    }

    @Test
    public void findResolvesDelegateDeclaredOnWrapperSuperclass() throws Exception {
        OwnedGenerator owned = mock(OwnedGenerator.class);
        DerivedWrapper wrapper = mock(DerivedWrapper.class);
        link(wrapper, owned);

        assertSame(owned, NativeGeneratorChain.find(wrapper, OwnedGenerator.class));
    }

    @Test
    public void findReturnsNullWhenChainNeverReachesType() throws Exception {
        ForeignGenerator foreign = mock(ForeignGenerator.class);

        assertNull(NativeGeneratorChain.find(wrap(foreign), OwnedGenerator.class));
    }

    @Test
    public void findTerminatesOnCyclicWrappers() throws Exception {
        TargetWrapper first = mock(TargetWrapper.class);
        TargetWrapper second = wrap(first);
        link(first, second);

        assertNull(NativeGeneratorChain.find(first, OwnedGenerator.class));
    }

    @Test
    public void findReturnsNullForMissingRoot() {
        assertNull(NativeGeneratorChain.find(null, OwnedGenerator.class));
    }

    @Test
    public void containsMatchesExactInstanceBehindWrappers() throws Exception {
        OwnedGenerator owned = mock(OwnedGenerator.class);
        TargetWrapper wrapper = wrap(wrap(owned));

        assertTrue(NativeGeneratorChain.contains(owned, owned));
        assertTrue(NativeGeneratorChain.contains(wrapper, owned));
    }

    @Test
    public void containsRejectsOtherInstanceOfSameType() throws Exception {
        OwnedGenerator active = mock(OwnedGenerator.class);
        OwnedGenerator stale = mock(OwnedGenerator.class);

        assertFalse(NativeGeneratorChain.contains(wrap(active), stale));
        assertFalse(NativeGeneratorChain.contains(wrap(active), null));
    }

    private static TargetWrapper wrap(ChunkGenerator target) throws Exception {
        TargetWrapper wrapper = mock(TargetWrapper.class);
        link(wrapper, target);
        return wrapper;
    }

    private static void link(TargetWrapper wrapper, ChunkGenerator target) throws Exception {
        Field field = TargetWrapper.class.getDeclaredField("target");
        field.setAccessible(true);
        field.set(wrapper, target);
    }

    abstract static class OwnedGenerator extends ChunkGenerator {
        protected OwnedGenerator() {
            super(null);
        }
    }

    abstract static class ForeignGenerator extends ChunkGenerator {
        protected ForeignGenerator() {
            super(null);
        }
    }

    abstract static class TargetWrapper extends ChunkGenerator {
        private final ChunkGenerator target;

        protected TargetWrapper(ChunkGenerator target) {
            super(null);
            this.target = target;
        }
    }

    abstract static class DerivedWrapper extends TargetWrapper {
        protected DerivedWrapper() {
            super(null);
        }
    }
}
