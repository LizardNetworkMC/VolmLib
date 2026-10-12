package art.arcane.volmlib.nativelib.v26_2_R1.player;

import art.arcane.volmlib.nativelib.NativeVersion;
import art.arcane.volmlib.nativelib.player.NativePlayerAdvancements;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ServerAdvancementManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.advancement.Advancement;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.advancement.CraftAdvancement;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class NativePlayerAdvancementsImpl implements NativePlayerAdvancements {
    @Override
    public Context capture(Server server) throws IOException {
        CraftServer craft = requireOwner(server);
        return new AdvancementContext(new Options(craft, Thread.currentThread(), craft.getServer(),
                craft.getServer().registryAccess(), craft.getServer().reloadableRegistries(), Objects.requireNonNull(craft.getServer().getAdvancements())));
    }

    private static CraftServer requireOwner(Server server) throws IOException {
        if (!(server instanceof CraftServer craft) || Bukkit.getServer() != server || !server.isPrimaryThread()
                || NativeVersion.resolve(server.getBukkitVersion()).orElse(null) != NativeVersion.V26_2_R1) {
            throw new IOException("Advancement application requires the matching Paper 26.1.2 or 26.2 server thread");
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, CraftServer.class.getClassLoader());
            throw new IOException("Native advancement application is unavailable on Folia");
        } catch (ClassNotFoundException absent) {
            return craft;
        }
    }

    private static final class AdvancementContext implements Context {
        private final Options options;

        private AdvancementContext(Options options) { this.options = options; }

        @Override
        public void validateCurrent() throws IOException {
            CraftServer server = requireOwner(options.server());
            if (Thread.currentThread() != options.thread() || server.getServer() != options.nativeServer()
                    || server.getServer().registryAccess() != options.registries()
                    || server.getServer().reloadableRegistries() != options.resources()
                    || server.getServer().getAdvancements() != options.advancements()) {
                throw new IOException("Native advancement server or registry context changed");
            }
        }

        @Override
        public boolean sameEntry(Advancement expected, Advancement actual) throws IOException {
            validateCurrent();
            AdvancementHolder first = requireEntry(expected);
            AdvancementHolder second = requireEntry(actual);
            validateCurrent();
            return first == second;
        }

        private AdvancementHolder requireEntry(Advancement advancement) throws IOException {
            if (!(advancement instanceof CraftAdvancement craft)) {
                throw new IOException("Advancement is not a genuine current Paper registry entry");
            }
            AdvancementHolder holder = craft.getHandle();
            if (holder == null || options.advancements().get(holder.id()) != holder) {
                throw new IOException("Captured advancement registry entry was removed or replaced");
            }
            return holder;
        }

        @Override
        public void flushWithoutToast(Player player) throws IOException {
            CraftPlayer craft = requirePlayer(player);
            try { craft.getHandle().getAdvancements().flushDirty(craft.getHandle(), false); }
            catch (RuntimeException failure) { throw new IOException("Unable to flush actual player advancement progress without toast", failure); }
            requirePlayer(craft);
        }

        private CraftPlayer requirePlayer(Player player) throws IOException {
            Objects.requireNonNull(player); validateCurrent();
            if (!(player instanceof CraftPlayer craft) || player.getServer() != options.server()
                    || options.server().getPlayer(player.getUniqueId()) != player || !player.isOnline()
                    || craft.getHandle().level().getServer() != options.nativeServer()) {
                throw new IOException("Native advancement player session is not current");
            }
            return craft;
        }
    }

    private record Options(CraftServer server, Thread thread, MinecraftServer nativeServer,
                           RegistryAccess.Frozen registries, ReloadableServerRegistries.Holder resources, ServerAdvancementManager advancements) { }
}
