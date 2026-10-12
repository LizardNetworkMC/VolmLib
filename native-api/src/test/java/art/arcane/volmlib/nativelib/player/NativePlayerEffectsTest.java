package art.arcane.volmlib.nativelib.player;

import org.bukkit.NamespacedKey;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NativePlayerEffectsTest {
    @Test public void effectValuesPreserveInfiniteDurationAndMaximumAmplifier() {
        NativePlayerEffects.Effect effect = new NativePlayerEffects.Effect(NamespacedKey.minecraft("speed"), -1, 255, true, false, true, null);
        assertEquals(-1, effect.duration()); assertEquals(255, effect.amplifier());
    }

    @Test public void unrepresentableFieldsAndForeignHiddenIdentityRefuse() {
        NamespacedKey speed = NamespacedKey.minecraft("speed");
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerEffects.Effect(speed, -2, 0, false, true, true, null));
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerEffects.Effect(speed, 100, 256, false, true, true, null));
        NativePlayerEffects.Effect other = new NativePlayerEffects.Effect(NamespacedKey.minecraft("slowness"), 100, 0, false, true, true, null);
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerEffects.Effect(speed, 100, 0, false, true, true, other));
    }

    @Test public void hiddenChainDepthIsBoundedBeforeNativePreparation() {
        NativePlayerEffects.Effect effect = null;
        for (int depth = 0; depth < 32; depth++) { effect = new NativePlayerEffects.Effect(NamespacedKey.minecraft("speed"), 100, 0, false, true, true, effect); }
        NativePlayerEffects.Effect chain = effect;
        assertThrows(IllegalArgumentException.class, () -> new NativePlayerEffects.Effect(chain.key(), 100, 0, false, true, true, chain));
    }
}
