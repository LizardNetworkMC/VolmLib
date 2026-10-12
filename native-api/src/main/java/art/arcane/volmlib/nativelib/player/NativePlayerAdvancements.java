package art.arcane.volmlib.nativelib.player;

import art.arcane.volmlib.nativelib.NativeBinding;
import java.io.IOException;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

@NativeBinding("player.NativePlayerAdvancementsImpl")
public interface NativePlayerAdvancements {
    Context capture(Server server) throws IOException;

    interface Context {
        void validateCurrent() throws IOException;
        boolean sameEntry(Advancement expected, Advancement actual) throws IOException;
        void flushWithoutToast(Player player) throws IOException;
    }
}
