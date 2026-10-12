package art.arcane.volmlib.nativelib.v26_3_R1.player;

import art.arcane.volmlib.nativelib.player.NativePlayerEffects;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativePlayerEffectsTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void normalPluginEffectApplicationPreservesEveryHiddenNodeField() throws IOException {
        try (Fixture fixture = new Fixture()) {
            NativePlayerEffects.Effect hidden = new NativePlayerEffects.Effect(NamespacedKey.minecraft("speed"), 600, 0, true, false, false, null);
            NativePlayerEffects.Effect root = new NativePlayerEffects.Effect(hidden.key(), 1200, 1, false, true, true, hidden);
            NativePlayerEffects.PreparedEffects prepared = fixture.context.prepare(fixture.player, List.of(root));
            fixture.context.apply(fixture.player, prepared);
            ArgumentCaptor<MobEffectInstance> effect = ArgumentCaptor.forClass(MobEffectInstance.class);
            verify(fixture.handle).addEffect(effect.capture(), eq(EntityPotionEffectEvent.Cause.PLUGIN));
            assertEquals(1200, effect.getValue().getDuration()); assertEquals(1, effect.getValue().getAmplifier());
            assertEquals(false, effect.getValue().isAmbient()); assertEquals(true, effect.getValue().isVisible()); assertEquals(true, effect.getValue().showIcon());
            MobEffectInstance observed = effect.getValue().hiddenEffect; assertNotNull(observed);
            assertEquals(600, observed.getDuration()); assertEquals(0, observed.getAmplifier());
            assertEquals(true, observed.isAmbient()); assertEquals(false, observed.isVisible()); assertEquals(false, observed.showIcon());
            assertEquals(effect.getValue().getEffect(), observed.getEffect());
            assertThrows(IOException.class, () -> fixture.context.apply(fixture.player, prepared));
            verify(fixture.player, times(1)).clearActivePotionEffects();
        }
    }

    @Test void lateUnknownAndDuplicateEffectRefuseBeforeAnySetter() throws IOException {
        try (Fixture fixture = new Fixture()) {
            NativePlayerEffects.Effect valid = speed();
            NativePlayerEffects.Effect unknown = new NativePlayerEffects.Effect(NamespacedKey.fromString("missing:effect"), 100, 0, false, true, true, null);
            assertThrows(IOException.class, () -> fixture.context.prepare(fixture.player, List.of(valid, unknown)));
            assertThrows(IOException.class, () -> fixture.context.prepare(fixture.player, List.of(valid, valid)));
            verify(fixture.player, never()).clearActivePotionEffects(); verify(fixture.handle, never()).addEffect(any(), any(EntityPotionEffectEvent.Cause.class));
        }
    }

    @Test void cancelledClearAndCancelledAddRefuseAndConsumeTheirOwnReceipts() throws IOException {
        try (Fixture fixture = new Fixture()) {
            NativePlayerEffects.PreparedEffects clear = fixture.context.prepare(fixture.player, List.of(speed()));
            when(fixture.player.getActivePotionEffects()).thenReturn(List.of(mock(PotionEffect.class)));
            assertThrows(IOException.class, () -> fixture.context.apply(fixture.player, clear));
            verify(fixture.handle, never()).addEffect(any(), any(EntityPotionEffectEvent.Cause.class));
            when(fixture.player.getActivePotionEffects()).thenReturn(List.of());
            NativePlayerEffects.PreparedEffects add = fixture.context.prepare(fixture.player, List.of(speed()));
            when(fixture.handle.addEffect(any(), eq(EntityPotionEffectEvent.Cause.PLUGIN))).thenReturn(false);
            assertThrows(IOException.class, () -> fixture.context.apply(fixture.player, add));
            assertThrows(IOException.class, () -> fixture.context.apply(fixture.player, add));
            verify(fixture.handle, times(1)).addEffect(any(), eq(EntityPotionEffectEvent.Cause.PLUGIN));
        }
    }

    @Test void changedWorldUuidAndForeignContextRefuseBeforeSetters() throws IOException {
        try (Fixture fixture = new Fixture()) {
            NativePlayerEffects.PreparedEffects prepared = fixture.context.prepare(fixture.player, List.of(speed()));
            NativePlayerEffects.Context other = new NativePlayerEffectsImpl().capture(fixture.server);
            assertThrows(IOException.class, () -> other.apply(fixture.player, prepared));
            when(fixture.world.getUID()).thenReturn(UUID.randomUUID());
            assertThrows(IOException.class, () -> fixture.context.apply(fixture.player, prepared));
            verify(fixture.player, never()).clearActivePotionEffects();
        }
    }

    @Test void changedRegistryContextAndOfflinePlayerRefuseBeforeSetters() throws IOException {
        try (Fixture fixture = new Fixture()) {
            when(fixture.player.isOnline()).thenReturn(false);
            assertThrows(IOException.class, () -> fixture.context.prepare(fixture.player, List.of(speed())));
            when(fixture.player.isOnline()).thenReturn(true);
            when(fixture.nativeServer.reloadableRegistries()).thenReturn(new ReloadableServerRegistries.Holder(fixture.registries));
            assertThrows(IOException.class, fixture.context::validateCurrent);
            verify(fixture.player, never()).clearActivePotionEffects();
        }
    }

    @Test void wrongServerAndOffThreadCaptureRefuseBeforeNativePlayerCalls() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IOException.class, () -> new NativePlayerEffectsImpl().capture(mock(Server.class)));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { fixture.context.prepare(fixture.player, List.of(speed())); } catch (Throwable caught) { failure.set(caught); }
            });
            thread.start(); thread.join(5000);
            assertNotNull(failure.get());
            verify(fixture.player, never()).clearActivePotionEffects();
        }
    }

    private static NativePlayerEffects.Effect speed() { return new NativePlayerEffects.Effect(NamespacedKey.minecraft("speed"), 100, 0, false, true, true, null); }

    private static final class Fixture implements AutoCloseable {
        private final CraftServer server = mock(CraftServer.class);
        private final DedicatedServer nativeServer = mock(DedicatedServer.class);
        private final CraftPlayer player = mock(CraftPlayer.class);
        private final ServerPlayer handle = mock(ServerPlayer.class);
        private final World world = mock(World.class);
        private final RegistryAccess.Frozen registries = new RegistryAccess.ImmutableRegistryAccess(List.of(BuiltInRegistries.MOB_EFFECT)).freeze();
        private final MockedStatic<Bukkit> bukkit;
        private final NativePlayerEffects.Context context;

        private Fixture() throws IOException {
            bukkit = mockStatic(Bukkit.class); bukkit.when(Bukkit::getServer).thenReturn(server);
            try {
                when(server.isPrimaryThread()).thenReturn(true); when(server.getBukkitVersion()).thenReturn("26.3-R0.1-SNAPSHOT");
                when(server.getServer()).thenReturn(nativeServer); when(nativeServer.registryAccess()).thenReturn(registries);
                when(nativeServer.reloadableRegistries()).thenReturn(new ReloadableServerRegistries.Holder(registries));
                when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getWorld()).thenReturn(world); when(player.getHandle()).thenReturn(handle);
                ServerLevel level = mock(ServerLevel.class); when(handle.level()).thenReturn(level); when(level.getServer()).thenReturn(nativeServer);
                when(player.isOnline()).thenReturn(true); when(server.getPlayer(player.getUniqueId())).thenReturn(player);
                when(world.getKey()).thenReturn(NamespacedKey.fromString("rift:effect_test")); when(world.getUID()).thenReturn(UUID.randomUUID());
                when(server.getWorld(world.getUID())).thenReturn(world); when(player.getActivePotionEffects()).thenReturn(List.of());
                when(handle.addEffect(any(), eq(EntityPotionEffectEvent.Cause.PLUGIN))).thenReturn(true);
                context = new NativePlayerEffectsImpl().capture(server);
            } catch (IOException | RuntimeException | Error failure) { bukkit.close(); throw failure; }
        }
        @Override public void close() { bukkit.close(); }
    }
}
