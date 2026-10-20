package art.arcane.volmlib.nativelib.chunk;

import org.bukkit.entity.Player;

import java.util.concurrent.CompletionStage;

public interface PreparedChunkPacket {
    boolean ready();

    CompletionStage<Boolean> send(Player player);
}
