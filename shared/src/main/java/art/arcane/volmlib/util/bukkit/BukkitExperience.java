package art.arcane.volmlib.util.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerExpChangeEvent;

import java.util.Objects;

public final class BukkitExperience {
    private BukkitExperience() {
    }

    public static int award(Player player, int points) {
        Objects.requireNonNull(player, "player");
        if (points <= 0) {
            return 0;
        }

        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, points);
        player.getServer().getPluginManager().callEvent(event);
        int awarded = Math.max(0, event.getAmount());
        if (awarded > 0) {
            player.giveExp(awarded);
        }
        return awarded;
    }
}
