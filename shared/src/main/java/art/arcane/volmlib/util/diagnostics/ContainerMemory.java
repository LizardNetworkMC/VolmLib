package art.arcane.volmlib.util.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

public final class ContainerMemory {
    private static final long SAMPLE_INTERVAL_NANOS = 200_000_000L;
    private static final long V1_UNLIMITED = Long.MAX_VALUE - Integer.MAX_VALUE;
    private static final Snapshot UNSUPPORTED = new Snapshot(Long.MAX_VALUE, 0, 0, 0, Long.MAX_VALUE);
    private static final ContainerMemory SYSTEM = new ContainerMemory(new Sources(Files::readString, System::nanoTime));

    private final Sources sources;
    private final List<Scope> scopes;
    private volatile Sample cached;

    ContainerMemory(Sources sources) {
        this.sources = Objects.requireNonNull(sources);
        this.scopes = discoverScopes();
    }

    public static Snapshot snapshot() {
        return SYSTEM.sample();
    }

    Snapshot sample() {
        long now = sources.nanoTime().getAsLong();
        Sample previous = cached;
        if (previous != null && now - previous.nanoTime() < SAMPLE_INTERVAL_NANOS) {
            return previous.snapshot();
        }
        synchronized (this) {
            previous = cached;
            if (previous != null && now - previous.nanoTime() < SAMPLE_INTERVAL_NANOS) {
                return previous.snapshot();
            }
            Snapshot snapshot = sampleScopes();
            cached = new Sample(now, snapshot);
            return snapshot;
        }
    }

    private Snapshot sampleScopes() {
        long smallestLimit = Long.MAX_VALUE;
        long selectedUsage = 0;
        long selectedWorkingSet = 0;
        long headroom = Long.MAX_VALUE;
        double pressure = 0;
        for (Scope scope : scopes) {
            long limit = readLimit(scope);
            if (limit == Long.MAX_VALUE) {
                continue;
            }
            long usage = readNonnegative(scope.path.resolve(scope.v2 ? "memory.current" : "memory.usage_in_bytes"));
            long reclaimable;
            if (usage < 0) {
                usage = scope.previousUsage < 0 ? limit : scope.previousUsage;
                reclaimable = scope.previousReclaimable;
            } else {
                reclaimable = Math.min(usage, readReclaimable(scope));
                scope.previousUsage = usage;
                scope.previousReclaimable = reclaimable;
            }
            long workingSet = Math.max(0, usage - reclaimable);
            pressure = Math.max(pressure, workingSet / (double) limit);
            headroom = Math.min(headroom, Math.max(0, limit - workingSet));
            if (limit < smallestLimit || limit == smallestLimit && workingSet > selectedWorkingSet) {
                smallestLimit = limit;
                selectedUsage = usage;
                selectedWorkingSet = workingSet;
            }
        }
        return smallestLimit == Long.MAX_VALUE ? UNSUPPORTED
                : new Snapshot(smallestLimit, selectedUsage, selectedWorkingSet, pressure, headroom);
    }

    private long readLimit(Scope scope) {
        String value = read(scope.path.resolve(scope.v2 ? "memory.max" : "memory.limit_in_bytes"));
        if (value == null) {
            return scope.previousLimit;
        }
        if (scope.v2 && value.trim().equals("max")) {
            scope.previousLimit = Long.MAX_VALUE;
            return Long.MAX_VALUE;
        }
        long parsed = parseNonnegative(value);
        if (parsed < 0) {
            return scope.previousLimit;
        }
        scope.previousLimit = !scope.v2 && parsed >= V1_UNLIMITED ? Long.MAX_VALUE : Math.max(1, parsed);
        return scope.previousLimit;
    }

    private long readReclaimable(Scope scope) {
        String statistics = read(scope.path.resolve("memory.stat"));
        if (statistics == null) {
            return 0;
        }
        String prefix = !scope.v2 && statistic(statistics, "total_inactive_file") >= 0 ? "total_" : "";
        long inactive = statistic(statistics, prefix + "inactive_file");
        long dirty = statistic(statistics, scope.v2 ? "file_dirty" : prefix + "dirty");
        long writeback = statistic(statistics, scope.v2 ? "file_writeback" : prefix + "writeback");
        if (inactive < 0 || dirty < 0 || writeback < 0 || dirty >= inactive) {
            return 0;
        }
        return Math.max(0, inactive - dirty - Math.min(writeback, inactive - dirty));
    }

    private List<Scope> discoverScopes() {
        String memberships = read(Path.of("/proc/self/cgroup"));
        String mountInfo = read(Path.of("/proc/self/mountinfo"));
        if (memberships == null || mountInfo == null) {
            return List.of();
        }
        List<Scope> discovered = new ArrayList<>();
        Set<Path> paths = new HashSet<>();
        for (String membership : memberships.split("\\R")) {
            String[] controller = membership.split(":", 3);
            if (controller.length != 3) {
                continue;
            }
            boolean v2 = controller[0].equals("0") && controller[1].isEmpty();
            if (!v2 && !containsController(controller[1], "memory")) {
                continue;
            }
            addMountScopes(discovered, paths, mountInfo, controller[2], v2);
        }
        return List.copyOf(discovered);
    }

    private void addMountScopes(List<Scope> discovered, Set<Path> paths, String mountInfo, String membership, boolean v2) {
        for (String mount : mountInfo.split("\\R")) {
            String[] sections = mount.split(" - ", 2);
            if (sections.length != 2) {
                continue;
            }
            String[] location = sections[0].split(" ");
            String[] filesystem = sections[1].split(" ");
            if (location.length < 6 || filesystem.length < 3
                    || !filesystem[0].equals(v2 ? "cgroup2" : "cgroup")
                    || !v2 && !containsController(filesystem[2], "memory")) {
                continue;
            }
            try {
                Path root = Path.of(unescape(location[3])).normalize();
                Path mountPoint = Path.of(unescape(location[4])).normalize();
                Path member = Path.of(membership).normalize();
                if (!root.isAbsolute() || !mountPoint.isAbsolute() || !member.isAbsolute()) {
                    continue;
                }
                Path relative;
                if (member.startsWith(root)) {
                    relative = root.relativize(member);
                } else if (!root.equals(Path.of("/"))) {
                    relative = Path.of("/").relativize(member);
                    Path candidate = mountPoint.resolve(relative).normalize();
                    if (read(candidate.resolve(v2 ? "memory.current" : "memory.usage_in_bytes")) == null
                            && read(candidate.resolve(v2 ? "memory.max" : "memory.limit_in_bytes")) == null) {
                        continue;
                    }
                } else {
                    continue;
                }
                Path current = mountPoint.resolve(relative).normalize();
                while (current != null && current.startsWith(mountPoint)) {
                    if (paths.add(current)) {
                        discovered.add(new Scope(current, v2));
                    }
                    if (current.equals(mountPoint)) {
                        break;
                    }
                    current = current.getParent();
                }
            } catch (InvalidPathException failure) {
                continue;
            }
        }
    }

    private String read(Path path) {
        try {
            return sources.files().read(path);
        } catch (IOException | SecurityException failure) {
            return null;
        }
    }

    private long readNonnegative(Path path) {
        String value = read(path);
        return value == null ? -1 : parseNonnegative(value);
    }

    private static long parseNonnegative(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static long statistic(String statistics, String key) {
        for (String line : statistics.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 2 && fields[0].equals(key)) {
                return parseNonnegative(fields[1]);
            }
        }
        return -1;
    }

    private static boolean containsController(String controllers, String desired) {
        for (String controller : controllers.split(",")) {
            if (controller.equals(desired)) {
                return true;
            }
        }
        return false;
    }

    private static String unescape(String value) {
        return value.replace("\\040", " ").replace("\\011", "\t")
                .replace("\\012", "\n").replace("\\134", "\\");
    }

    public record Snapshot(long limitBytes, long usedBytes, long workingSetBytes, double pressureFraction,
                           long headroomBytes) {
        public boolean supported() {
            return limitBytes != Long.MAX_VALUE;
        }
    }

    record Sources(FileReader files, LongSupplier nanoTime) {
        Sources {
            Objects.requireNonNull(files);
            Objects.requireNonNull(nanoTime);
        }
    }

    @FunctionalInterface
    interface FileReader {
        String read(Path path) throws IOException;
    }

    private record Sample(long nanoTime, Snapshot snapshot) {
    }

    private static final class Scope {
        private final Path path;
        private final boolean v2;
        private long previousLimit = Long.MAX_VALUE;
        private long previousUsage = -1;
        private long previousReclaimable;

        private Scope(Path path, boolean v2) {
            this.path = path;
            this.v2 = v2;
        }
    }
}
