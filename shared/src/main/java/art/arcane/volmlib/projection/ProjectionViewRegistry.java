package art.arcane.volmlib.projection;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class ProjectionViewRegistry implements ProjectionViewProvider, AutoCloseable {
    private static final long LEASE_NANOS = 2_000_000_000L;
    private static final int CELL_SIZE = 64;

    private final Map<Key, Entry> entries = new HashMap<>();
    private final Map<UUID, Map<Cell, Map<Key, Entry>>> worlds = new HashMap<>();
    private final Map<UUID, Map<UUID, Entry>> viewers = new HashMap<>();
    private final LongSupplier clock;
    private final Plugin plugin;
    private long nextSweep;
    private boolean closed;

    public ProjectionViewRegistry(Plugin plugin) {
        this(Objects.requireNonNull(plugin, "plugin"), System::nanoTime);
        plugin.getServer().getServicesManager().register(ProjectionViewProvider.class, this, plugin, ServicePriority.Normal);
    }

    ProjectionViewRegistry(Plugin plugin, LongSupplier clock) {
        this.plugin = plugin;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void publish(UUID viewer, UUID view, UUID world, double x, double y, double z) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(world, "world");
        coordinates(x, y, z);
        if (closed) {
            return;
        }
        long now = clock.getAsLong();
        sweep(now);
        Key key = new Key(viewer, view);
        Entry previous = entries.get(key);
        Cell cell = cell(x, z);
        if (previous != null && (!previous.world().equals(world) || !previous.cell().equals(cell))) {
            remove(previous);
        }
        Entry entry = new Entry(key, world, cell, x, y, z, now + LEASE_NANOS);
        entries.put(key, entry);
        worlds.computeIfAbsent(world, ignored -> new HashMap<>()).computeIfAbsent(cell, ignored -> new HashMap<>()).put(key, entry);
        viewers.computeIfAbsent(viewer, ignored -> new HashMap<>()).put(view, entry);
    }

    public synchronized void removeView(UUID viewer, UUID view) {
        Entry entry = entries.get(new Key(viewer, view));
        if (entry != null) {
            remove(entry);
        }
    }

    public synchronized void removeViewer(UUID viewer) {
        Map<UUID, Entry> active = viewers.get(viewer);
        if (active != null) {
            for (Entry entry : active.values().toArray(Entry[]::new)) {
                remove(entry);
            }
        }
    }

    @Override
    public synchronized Map<UUID, Double> viewers(UUID world, double x, double y, double z, double range) {
        coordinates(x, y, z);
        if (!Double.isFinite(range) || range < 0.0D) {
            throw new IllegalArgumentException("Projection range must be finite and nonnegative");
        }
        Map<Cell, Map<Key, Entry>> cells = worlds.get(world);
        if (closed || cells == null) {
            return Map.of();
        }
        long now = clock.getAsLong();
        double limit = range * range;
        long minX = cellCoordinate(x - range);
        long maxX = cellCoordinate(x + range);
        long minZ = cellCoordinate(z - range);
        long maxZ = cellCoordinate(z + range);
        Map<UUID, Double> result = new HashMap<>();
        double area = ((double) maxX - minX + 1.0D) * ((double) maxZ - minZ + 1.0D);
        if (area > 4096.0D) {
            for (Map.Entry<Cell, Map<Key, Entry>> bucket : cells.entrySet()) {
                Cell cell = bucket.getKey();
                if (cell.x() >= minX && cell.x() <= maxX && cell.z() >= minZ && cell.z() <= maxZ) {
                    collect(bucket.getValue(), result, x, y, z, limit, now);
                }
            }
        } else {
            for (long cx = minX;; cx++) {
                for (long cz = minZ;; cz++) {
                    Map<Key, Entry> bucket = cells.get(new Cell(cx, cz));
                    if (bucket != null) {
                        collect(bucket, result, x, y, z, limit, now);
                    }
                    if (cz == maxZ) {
                        break;
                    }
                }
                if (cx == maxX) {
                    break;
                }
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public synchronized double distanceSquared(UUID viewer, UUID world, double x, double y, double z) {
        coordinates(x, y, z);
        Map<UUID, Entry> active = viewers.get(viewer);
        if (closed || active == null) {
            return Double.POSITIVE_INFINITY;
        }
        long now = clock.getAsLong();
        double distance = Double.POSITIVE_INFINITY;
        for (Entry entry : active.values()) {
            if (entry.world().equals(world) && now - entry.expiresAt() < 0L) {
                distance = Math.min(distance, entry.distanceSquared(x, y, z));
            }
        }
        return distance;
    }

    @Override
    public synchronized void close() {
        closed = true;
        entries.clear();
        worlds.clear();
        viewers.clear();
        if (plugin != null) {
            plugin.getServer().getServicesManager().unregister(ProjectionViewProvider.class, this);
        }
    }

    private void sweep(long now) {
        if (now - nextSweep < 0L) {
            return;
        }
        nextSweep = now + LEASE_NANOS;
        for (Entry entry : entries.values().toArray(Entry[]::new)) {
            if (now - entry.expiresAt() >= 0L) {
                remove(entry);
            }
        }
    }

    private void remove(Entry entry) {
        entries.remove(entry.key());
        Map<UUID, Entry> active = viewers.get(entry.key().viewer());
        active.remove(entry.key().view());
        if (active.isEmpty()) {
            viewers.remove(entry.key().viewer());
        }
        Map<Cell, Map<Key, Entry>> cells = worlds.get(entry.world());
        Map<Key, Entry> bucket = cells.get(entry.cell());
        bucket.remove(entry.key());
        if (bucket.isEmpty()) {
            cells.remove(entry.cell());
        }
        if (cells.isEmpty()) {
            worlds.remove(entry.world());
        }
    }

    private static void collect(Map<Key, Entry> bucket, Map<UUID, Double> result, double x, double y, double z, double limit, long now) {
        for (Entry entry : bucket.values()) {
            double distance = entry.distanceSquared(x, y, z);
            if (now - entry.expiresAt() < 0L && distance <= limit) {
                result.merge(entry.key().viewer(), distance, Math::min);
            }
        }
    }

    private static Cell cell(double x, double z) {
        return new Cell(cellCoordinate(x), cellCoordinate(z));
    }

    private static long cellCoordinate(double coordinate) {
        return (long) Math.floor(coordinate / CELL_SIZE);
    }

    private static void coordinates(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Projection coordinates must be finite");
        }
    }

    private record Key(UUID viewer, UUID view) {
    }

    private record Cell(long x, long z) {
    }

    private record Entry(Key key, UUID world, Cell cell, double x, double y, double z, long expiresAt) {
        double distanceSquared(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
