package art.arcane.volmlib.nativelib.v26_2_R1.player;

import art.arcane.volmlib.nativelib.NativeVersion;
import art.arcane.volmlib.nativelib.player.NativePlayerEffects;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPotionEffectEvent;

public final class NativePlayerEffectsImpl implements NativePlayerEffects {
    @Override
    public Context capture(Server server) throws IOException {
        requireOwner(server);
        CraftServer craft = (CraftServer) server;
        return new EffectsContext(new ContextOptions(craft, Thread.currentThread(), craft.getServer(), craft.getServer().registryAccess(),
                craft.getServer().reloadableRegistries(), UUID.randomUUID()));
    }

    private static void requireOwner(Server server) throws IOException {
        if (!(server instanceof CraftServer) || Bukkit.getServer() != server || !server.isPrimaryThread()
                || NativeVersion.resolve(server.getBukkitVersion()).orElse(null) != NativeVersion.V26_2_R1) {
            throw new IOException("Player effect capture requires the matching Paper 26.1.2 or 26.2 server thread");
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, CraftServer.class.getClassLoader());
            throw new IOException("Native player effect application is unavailable on Folia");
        } catch (ClassNotFoundException absent) {
            return;
        }
    }

    private static final class EffectsContext implements Context {
        private final ContextOptions options;

        private EffectsContext(ContextOptions options) { this.options = options; }

        @Override public UUID contextId() { return options.id(); }

        @Override
        public void validateCurrent() throws IOException {
            requireOwner(options.server());
            if (Thread.currentThread() != options.owner()
                    || options.server().getServer() != options.nativeServer()
                    || options.server().getServer().registryAccess() != options.registries()
                    || options.server().getServer().reloadableRegistries() != options.resources()) {
                throw new IOException("Native player effect owner or registry context changed");
            }
        }

        @Override
        public PreparedEffects prepare(Player player, List<Effect> effects) throws IOException {
            CraftPlayer current = requirePlayer(player);
            List<Effect> requested = List.copyOf(Objects.requireNonNull(effects, "effects"));
            if (requested.size() > 256) { throw new IOException("Native player effect list exceeds its bound"); }
            Set<NamespacedKey> keys = new HashSet<>();
            List<MobEffectInstance> decoded = new ArrayList<>(requested.size());
            for (Effect effect : requested) {
                if (!keys.add(effect.key())) { throw new IOException("Native player effect list repeats an active effect identity"); }
                decoded.add(decode(effect, 1));
            }
            return new Prepared(this, new PreparedOptions(current, current.getUniqueId(), current.getWorld(),
                    current.getWorld().getKey(), current.getWorld().getUID(), List.copyOf(decoded)));
        }

        @Override
        public void apply(Player player, PreparedEffects effects) throws IOException {
            CraftPlayer current = requirePlayer(player);
            if (!(effects instanceof Prepared prepared) || prepared.issuer != this || prepared.consumed) {
                throw new IOException("Native player effect receipt is foreign or consumed");
            }
            requireIdentity(current, prepared);
            prepared.consumed = true;
            current.clearActivePotionEffects();
            requireIdentity(requirePlayer(current), prepared);
            if (!current.getActivePotionEffects().isEmpty()) {
                throw new IOException("Paper refused to clear existing player effects");
            }
            for (MobEffectInstance effect : prepared.options.effects()) {
                requireIdentity(requirePlayer(current), prepared);
                if (!current.getHandle().addEffect(effect, EntityPotionEffectEvent.Cause.PLUGIN)) {
                    throw new IOException("Paper refused prepared player effect application");
                }
            }
            requireIdentity(requirePlayer(current), prepared);
        }

        private static void requireIdentity(CraftPlayer player, Prepared prepared) throws IOException {
            if (prepared.options.player() != player || !prepared.options.playerId().equals(player.getUniqueId())
                    || prepared.options.world() != player.getWorld() || !prepared.options.worldKey().equals(player.getWorld().getKey())
                    || !prepared.options.worldId().equals(player.getWorld().getUID())) {
                throw new IOException("Native player effect receipt has a changed player/world identity");
            }
        }

        private CraftPlayer requirePlayer(Player player) throws IOException {
            validateCurrent();
            if (!(player instanceof CraftPlayer current) || !current.isOnline() || current.isDead()
                    || options.server().getPlayer(current.getUniqueId()) != current
                    || current.getHandle().level().getServer() != options.server().getServer()
                    || options.server().getWorld(current.getWorld().getUID()) != current.getWorld()) {
                throw new IOException("Native player effects require the current registered live Paper player");
            }
            return current;
        }

        private MobEffectInstance decode(Effect effect, int depth) throws IOException {
            if (depth > 32) { throw new IOException("Native hidden effect chain exceeds its depth limit"); }
            Holder<MobEffect> type = options.registries().lookupOrThrow(Registries.MOB_EFFECT)
                    .get(ResourceKey.create(Registries.MOB_EFFECT, Identifier.fromNamespaceAndPath(effect.key().getNamespace(), effect.key().getKey())))
                    .orElseThrow(() -> new IOException("Native effect identity is absent from the captured registry: " + effect.key()));
            return new MobEffectInstance(type, effect.duration(), effect.amplifier(), effect.ambient(), effect.particles(),
                    effect.icon(), effect.hidden() == null ? null : decode(effect.hidden(), depth + 1));
        }
    }

    private static final class Prepared implements PreparedEffects {
        private final EffectsContext issuer;
        private final PreparedOptions options;
        private boolean consumed;

        private Prepared(EffectsContext issuer, PreparedOptions options) { this.issuer = issuer; this.options = options; }
    }

    private record ContextOptions(CraftServer server, Thread owner, MinecraftServer nativeServer, RegistryAccess.Frozen registries,
                                  ReloadableServerRegistries.Holder resources, UUID id) { }
    private record PreparedOptions(CraftPlayer player, UUID playerId, World world, NamespacedKey worldKey,
                                   UUID worldId, List<MobEffectInstance> effects) { }
}
