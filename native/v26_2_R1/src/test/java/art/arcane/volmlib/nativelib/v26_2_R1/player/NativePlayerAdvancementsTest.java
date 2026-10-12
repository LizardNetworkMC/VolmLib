package art.arcane.volmlib.nativelib.v26_2_R1.player;

import art.arcane.volmlib.nativelib.player.NativePlayerAdvancements;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.advancement.CraftAdvancement;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class NativePlayerAdvancementsTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void exactCurrentNativePlayerFlushesWithoutToast() throws IOException {
        try (Fixture fixture = new Fixture()) {
            fixture.context.flushWithoutToast(fixture.player);
            verify(fixture.advancements).flushDirty(fixture.handle, false);
        }
    }

    @Test void replacedPlayerSessionAndRegistryRefuseBeforeAnyFlush() throws IOException {
        try (Fixture fixture = new Fixture()) {
            when(fixture.server.getPlayer(fixture.player.getUniqueId())).thenReturn(mock(CraftPlayer.class));
            assertThrows(IOException.class, () -> fixture.context.flushWithoutToast(fixture.player));
            when(fixture.server.getPlayer(fixture.player.getUniqueId())).thenReturn(fixture.player);
            when(fixture.nativeServer.reloadableRegistries()).thenReturn(new ReloadableServerRegistries.Holder(fixture.registries));
            assertThrows(IOException.class, () -> fixture.context.flushWithoutToast(fixture.player));
            verify(fixture.advancements, never()).flushDirty(fixture.handle, false);
        }
    }

    @Test void freshBukkitWrappersRequireTheExactCurrentNativeRegistryHolder() throws IOException {
        try (Fixture fixture = new Fixture()) {
            Identifier key = Identifier.fromNamespaceAndPath("rift_qa", "wrapper");
            Advancement definition = mock(Advancement.class);
            AdvancementHolder holder = new AdvancementHolder(key, definition);
            when(fixture.registry.get(key)).thenReturn(holder);
            CraftAdvancement first = new CraftAdvancement(holder);
            CraftAdvancement second = new CraftAdvancement(holder);
            assertNotSame(first, second);
            assertTrue(fixture.context.sameEntry(first, second));
            AdvancementHolder replacement = new AdvancementHolder(key, definition);
            when(fixture.registry.get(key)).thenReturn(replacement);
            assertThrows(IOException.class, () -> fixture.context.sameEntry(first, second));
            assertThrows(IOException.class, () -> fixture.context.sameEntry(first, new CraftAdvancement(replacement)));
            assertTrue(fixture.context.sameEntry(new CraftAdvancement(replacement), new CraftAdvancement(replacement)));
            when(fixture.registry.get(key)).thenReturn(null);
            assertThrows(IOException.class, () -> fixture.context.sameEntry(new CraftAdvancement(replacement), new CraftAdvancement(replacement)));
        }
    }

    @Test void actualNativeFlushFailureIsNotReportedAsSuccess() throws IOException {
        try (Fixture fixture = new Fixture()) {
            doThrow(new IllegalStateException("Native flush failed")).when(fixture.advancements).flushDirty(fixture.handle, false);
            assertThrows(IOException.class, () -> fixture.context.flushWithoutToast(fixture.player));
        }
    }

    @Test void wrongOwningThreadCannotFlushEvenWhenMockServerReportsPrimary() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { fixture.context.flushWithoutToast(fixture.player); } catch (Throwable thrown) { failure.set(thrown); }
            });
            worker.start(); worker.join(5000); assertNotNull(failure.get());
            verify(fixture.advancements, never()).flushDirty(fixture.handle, false);
        }
    }

    @Test void mapped2612CapturesWithCurrentNativeOwnerButOtherReleasesRefuse() throws IOException {
        try (Fixture fixture = new Fixture()) {
            for (String version : List.of("26.1.2-R0.1-SNAPSHOT", "26.1.2.build.74-stable")) {
                when(fixture.server.getBukkitVersion()).thenReturn(version);
                NativePlayerAdvancements.Context captured = new NativePlayerAdvancementsImpl().capture(fixture.server);
                captured.validateCurrent();
            }
            for (String version : List.of("26.1", "26.1.1", "26.1.3", "26.2.1", "26.3")) {
                when(fixture.server.getBukkitVersion()).thenReturn(version);
                assertThrows(IOException.class, () -> new NativePlayerAdvancementsImpl().capture(fixture.server));
                assertThrows(IOException.class, fixture.context::validateCurrent);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CraftServer server = mock(CraftServer.class);
        private final DedicatedServer nativeServer = mock(DedicatedServer.class);
        private final CraftPlayer player = mock(CraftPlayer.class);
        private final ServerPlayer handle = mock(ServerPlayer.class);
        private final PlayerAdvancements advancements = mock(PlayerAdvancements.class);
        private final ServerAdvancementManager registry = mock(ServerAdvancementManager.class);
        private final RegistryAccess.Frozen registries = new RegistryAccess.ImmutableRegistryAccess(List.of(BuiltInRegistries.MOB_EFFECT)).freeze();
        private final MockedStatic<Bukkit> bukkit;
        private final NativePlayerAdvancements.Context context;

        private Fixture() throws IOException {
            bukkit = mockStatic(Bukkit.class); bukkit.when(Bukkit::getServer).thenReturn(server);
            try {
                when(server.isPrimaryThread()).thenReturn(true); when(server.getBukkitVersion()).thenReturn("26.2-R0.1-SNAPSHOT");
                when(server.getServer()).thenReturn(nativeServer); when(nativeServer.registryAccess()).thenReturn(registries);
                when(nativeServer.getAdvancements()).thenReturn(registry);
                when(nativeServer.reloadableRegistries()).thenReturn(new ReloadableServerRegistries.Holder(registries));
                when(player.getServer()).thenReturn(server); when(player.getHandle()).thenReturn(handle);
                when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
                when(server.getPlayer(player.getUniqueId())).thenReturn(player); ServerLevel level = mock(ServerLevel.class); when(handle.level()).thenReturn(level); when(level.getServer()).thenReturn(nativeServer);
                when(handle.getAdvancements()).thenReturn(advancements);
                context = new NativePlayerAdvancementsImpl().capture(server);
            } catch (IOException | RuntimeException | Error failure) { bukkit.close(); throw failure; }
        }
        @Override public void close() { bukkit.close(); }
    }
}
