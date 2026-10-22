package art.arcane.volmlib.util.metrics;

import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bstats.MetricsBase;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public final class BukkitMetrics implements AutoCloseable {
    private final JavaPlugin plugin;
    private final MetricsBase reporter;
    private final boolean enabled;
    private volatile boolean closed;

    public BukkitMetrics(JavaPlugin plugin, int serviceId) throws IOException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (serviceId <= 0) {
            throw new IllegalArgumentException("bStats service ID must be assigned before reporting starts");
        }
        Settings settings = settings(plugin.getDataFolder().toPath().toAbsolutePath().getParent());
        enabled = settings.enabled();
        reporter = enabled ? new MetricsBase("bukkit", settings.serverId(), serviceId, true,
                this::collectPlatform, this::collectPlugin, this::scheduleCollection, this::active,
                (message, failure) -> plugin.getLogger().log(Level.WARNING, message, failure),
                plugin.getLogger()::info, settings.logFailures(), settings.logPayload(), settings.logResponse(), true) : null;
    }

    public boolean enabled() {
        return enabled && !closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (reporter != null) {
            reporter.shutdown();
        }
    }

    private boolean active() {
        return !closed && plugin.isEnabled();
    }

    private void scheduleCollection(Runnable collect) {
        if (!active()) {
            return;
        }
        if (!FoliaScheduler.runGlobal(plugin, () -> {
            if (active()) {
                collect.run();
            }
        }) && active()) {
            plugin.getLogger().warning("bStats collection skipped because the server scheduler rejected the task.");
        }
    }

    private void collectPlatform(JsonObjectBuilder payload) {
        Server server = plugin.getServer();
        payload.appendField("playerAmount", server.getOnlinePlayers().size())
                .appendField("onlineMode", server.getOnlineMode() ? 1 : 0)
                .appendField("bukkitVersion", server.getVersion())
                .appendField("bukkitName", server.getName())
                .appendField("javaVersion", System.getProperty("java.version"))
                .appendField("osName", System.getProperty("os.name"))
                .appendField("osArch", System.getProperty("os.arch"))
                .appendField("osVersion", System.getProperty("os.version"))
                .appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    private void collectPlugin(JsonObjectBuilder payload) {
        payload.appendField("pluginVersion", plugin.getDescription().getVersion());
    }

    private static Settings settings(Path pluginsDirectory) throws IOException {
        Path file = pluginsDirectory.resolve("bStats/config.yml");
        YamlConfiguration config = new YamlConfiguration();
        if (Files.exists(file)) {
            try {
                config.load(file.toFile());
            } catch (InvalidConfigurationException failure) {
                throw new IOException("Invalid bStats configuration: " + file, failure);
            }
        }
        boolean enabled = config.getBoolean("enabled", true);
        String serverId = config.getString("serverUuid", "");
        if (enabled && serverId.isBlank()) {
            serverId = UUID.randomUUID().toString();
            config.set("serverUuid", serverId);
            if (!config.contains("enabled")) {
                config.set("enabled", true);
            }
            AtomicFileIO.writeString(file, config.saveToString());
        }
        return new Settings(enabled, serverId, config.getBoolean("logFailedRequests", false),
                config.getBoolean("logSentData", false), config.getBoolean("logResponseStatusText", false));
    }

    private record Settings(boolean enabled, String serverId, boolean logFailures, boolean logPayload, boolean logResponse) {
    }
}
