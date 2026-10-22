package art.arcane.volmlib.nativelib.v26_1_R1.protection;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.NativeBinding;
import art.arcane.volmlib.nativelib.NativeVersion;
import art.arcane.volmlib.nativelib.protection.SpawnProtectionAccess;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SpawnProtectionBindingTest {
    @Test
    public void resolvesSpawnProtectionForBothInitialReleases() {
        for (String release : new String[]{"26.1", "26.1.0-R0.1-SNAPSHOT", "26.1.1"}) {
            assertEquals(NativeSpawnProtectionAccess.class,
                    NativeAdapters.require(SpawnProtectionAccess.class, release).getClass());
        }
    }

    @Test
    public void doesNotExposeUnimplementedCapabilities() {
        assertFalse(NativeAdapters.available(MissingCapability.class, NativeVersion.V26_1_R1));
        assertFalse(NativeAdapters.find(MissingCapability.class, "26.1.1").isPresent());
    }

    @NativeBinding("terrain.UnimplementedAccess")
    public interface MissingCapability {
    }
}
