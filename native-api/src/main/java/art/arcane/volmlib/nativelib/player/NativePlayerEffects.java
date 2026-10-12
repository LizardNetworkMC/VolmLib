package art.arcane.volmlib.nativelib.player;

import art.arcane.volmlib.nativelib.NativeBinding;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;

@NativeBinding("player.NativePlayerEffectsImpl")
public interface NativePlayerEffects {
    Context capture(Server server) throws IOException;

    interface Context {
        UUID contextId();
        void validateCurrent() throws IOException;
        PreparedEffects prepare(Player player, List<Effect> effects) throws IOException;
        void apply(Player player, PreparedEffects effects) throws IOException;
    }

    interface PreparedEffects { }

    record Effect(NamespacedKey key, int duration, int amplifier, boolean ambient,
                  boolean particles, boolean icon, Effect hidden) {
        public Effect {
            Objects.requireNonNull(key, "key");
            if (duration < -1 || amplifier < 0 || amplifier > 255) {
                throw new IllegalArgumentException("Native effect duration or amplifier is outside the supported range");
            }
            int depth = 1;
            for (Effect current = hidden; current != null; current = current.hidden()) {
                if (++depth > 32) { throw new IllegalArgumentException("Native hidden effect chain exceeds its depth limit"); }
                if (!key.equals(current.key())) { throw new IllegalArgumentException("Native hidden effect chain changes effect identity"); }
            }
        }
    }
}
