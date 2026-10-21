package art.arcane.volmlib.projection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class ProjectionViewAccess implements Listener, AutoCloseable {
    private final Plugin plugin;
    private volatile List<Provider> providers = List.of();
    private boolean closed;

    public ProjectionViewAccess(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Class<?> service : plugin.getServer().getServicesManager().getKnownServices()) {
            for (RegisteredServiceProvider<?> registration : plugin.getServer().getServicesManager().getRegistrations(service)) {
                add(registration);
            }
        }
    }

    public Map<UUID, Double> viewers(UUID world, double x, double y, double z, double range) {
        if (providers.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Double> result = new HashMap<>();
        for (Provider provider : providers) {
            Object value = invoke(provider, provider.viewers(), world, x, y, z, range);
            if (value instanceof Map<?, ?> matches) {
                for (Map.Entry<?, ?> match : matches.entrySet()) {
                    if (match.getKey() instanceof UUID viewer && match.getValue() instanceof Number number) {
                        double distance = number.doubleValue();
                        if (Double.isFinite(distance) && distance >= 0.0D && distance <= range * range) {
                            result.merge(viewer, distance, Math::min);
                        }
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    public double distanceSquared(UUID viewer, UUID world, double x, double y, double z) {
        double distance = Double.POSITIVE_INFINITY;
        for (Provider provider : providers) {
            Object value = invoke(provider, provider.distance(), viewer, world, x, y, z);
            if (value instanceof Number number && number.doubleValue() >= 0.0D) {
                distance = Math.min(distance, number.doubleValue());
            }
        }
        return distance;
    }

    @EventHandler
    public void registered(ServiceRegisterEvent event) {
        add(event.getProvider());
    }

    @EventHandler
    public synchronized void unregistered(ServiceUnregisterEvent event) {
        Object provider = event.getProvider().getProvider();
        List<Provider> updated = new ArrayList<>(providers);
        updated.removeIf(entry -> entry.instance() == provider);
        providers = List.copyOf(updated);
    }

    @Override
    public synchronized void close() {
        closed = true;
        providers = List.of();
        HandlerList.unregisterAll(this);
    }

    private synchronized void add(RegisteredServiceProvider<?> registration) {
        if (closed || !registration.getService().getName().endsWith(".projection.ProjectionViewProvider")) {
            return;
        }
        Object instance = registration.getProvider();
        for (Provider provider : providers) {
            if (provider.instance() == instance) {
                return;
            }
        }
        try {
            Class<?> service = registration.getService();
            Method viewers = service.getMethod("viewers", UUID.class, double.class, double.class, double.class, double.class);
            Method distance = service.getMethod("distanceSquared", UUID.class, UUID.class, double.class, double.class, double.class);
            List<Provider> updated = new ArrayList<>(providers);
            updated.add(new Provider(instance, viewers, distance, new AtomicBoolean()));
            providers = List.copyOf(updated);
        } catch (ReflectiveOperationException failure) {
            plugin.getLogger().log(Level.WARNING, "Cannot access projection viewer provider " + registration.getService().getName(), failure);
        }
    }

    private Object invoke(Provider provider, Method method, Object... arguments) {
        try {
            return method.invoke(provider.instance(), arguments);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
            if (provider.failed().compareAndSet(false, true)) {
                Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
                plugin.getLogger().log(Level.WARNING, "Projection viewer provider query failed", cause);
            }
            return null;
        }
    }

    private record Provider(Object instance, Method viewers, Method distance, AtomicBoolean failed) {
    }
}
