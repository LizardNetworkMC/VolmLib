package art.arcane.volmlib.util.metrics;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bstats.MetricsBase;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BukkitMetricsTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void unassignedIdDoesNotCreateGlobalConfiguration() throws Exception {
        JavaPlugin plugin = plugin();
        assertThrows(IllegalArgumentException.class, () -> new BukkitMetrics(plugin, 0));
        assertFalse(Files.exists(globalConfig()));
    }

    @Test
    public void globalOptOutDoesNotConstructAReporterOrRewriteConfiguration() throws Exception {
        Path file = globalConfig();
        Files.createDirectories(file.getParent());
        String original = "enabled: false\nserverUuid: retained-server-id\n";
        Files.writeString(file, original);
        try (MockedConstruction<MetricsBase> construction = mockConstruction(MetricsBase.class);
             BukkitMetrics metrics = new BukkitMetrics(plugin(), 42)) {
            assertFalse(metrics.enabled());
            assertTrue(construction.constructed().isEmpty());
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void collectionUsesTheGlobalSchedulerAndRetiredCallbacksDoNothing() throws Exception {
        JavaPlugin plugin = plugin();
        Server server = plugin.getServer();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        List<Object> arguments = new ArrayList<>();
        try (MockedConstruction<MetricsBase> construction = mockConstruction(MetricsBase.class,
                (reporter, context) -> arguments.addAll(context.arguments()));
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runGlobal(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                scheduled.set(call.getArgument(1));
                return true;
            });
            BukkitMetrics metrics = new BukkitMetrics(plugin, 42);
            assertTrue(metrics.enabled());
            assertEquals("bukkit", arguments.get(0));
            assertEquals(42, arguments.get(2));
            assertFalse(((String) arguments.get(1)).isBlank());
            Consumer<JsonObjectBuilder> collector = (Consumer<JsonObjectBuilder>) arguments.get(4);
            Consumer<Runnable> dispatcher = (Consumer<Runnable>) arguments.get(6);
            JsonObjectBuilder payload = new JsonObjectBuilder();
            dispatcher.accept(() -> collector.accept(payload));
            verify(server, never()).getOnlinePlayers();
            assertNotNull(scheduled.get());
            scheduled.get().run();
            verify(server).getOnlinePlayers();
            String json = payload.build().toString();
            assertTrue(json.contains("\"playerAmount\":0"));
            assertTrue(json.contains("\"bukkitName\":\"Folia\""));
            metrics.close();
            metrics.close();
            clearInvocations(server);
            scheduled.get().run();
            verify(server, never()).getOnlinePlayers();
            verify(construction.constructed().get(0), times(1)).shutdown();
            assertFalse(metrics.enabled());
        }
    }

    @Test
    public void invalidGlobalConfigurationIsPreserved() throws Exception {
        Path file = globalConfig();
        Files.createDirectories(file.getParent());
        String invalid = "enabled: [unterminated\n";
        Files.writeString(file, invalid);
        assertThrows(IOException.class, () -> new BukkitMetrics(plugin(), 42));
        assertEquals(invalid, Files.readString(file));
    }

    private JavaPlugin plugin() throws IOException {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Path folder = temporary.getRoot().toPath().resolve("Example");
        Files.createDirectories(folder);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BukkitMetricsTest"));
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getName()).thenReturn("Folia");
        when(server.getVersion()).thenReturn("26.3");
        when(server.getOnlinePlayers()).thenReturn(List.of());
        return plugin;
    }

    private Path globalConfig() {
        return temporary.getRoot().toPath().resolve("bStats/config.yml");
    }
}
