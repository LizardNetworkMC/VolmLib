package art.arcane.volmlib.util.diagnostics;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ContainerMemoryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private FileSystem fileSystem;

    @Before
    public void openUnixFileSystem() throws IOException {
        fileSystem = FileSystems.newFileSystem(
                temporaryFolder.getRoot().toPath().resolve("cgroup.zip"), Map.of("create", "true"));
    }

    @After
    public void closeUnixFileSystem() throws IOException {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    @Test
    public void v2MeasuresWorkingSetWithinNamespacedDockerMount() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/docker/container", "/sys/fs/cgroup"));
        fixture.v2("/sys/fs/cgroup", "1000", "920", "inactive_file 200\nfile_dirty 30\nfile_writeback 20\n");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertTrue(snapshot.supported());
        assertEquals(1000, snapshot.limitBytes());
        assertEquals(920, snapshot.usedBytes());
        assertEquals(770, snapshot.workingSetBytes());
        assertEquals(0.77, snapshot.pressureFraction(), 0.0001);
        assertEquals(230, snapshot.headroomBytes());
    }

    @Test
    public void nestedNamespaceMembershipResolvesBelowItsMount() {
        Fixture fixture = new Fixture("0::/server\n", v2Mount("/docker/container", "/limits"));
        fixture.v2("/limits/server", "1000", "600", "");
        fixture.v2("/limits", "2000", "700", "");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertEquals(1000, snapshot.limitBytes());
        assertEquals(600, snapshot.usedBytes());
        assertEquals(0.6, snapshot.pressureFraction(), 0.0001);
    }

    @Test
    public void ancestorPressureUsesAncestorUsageInsteadOfChildUsage() {
        Fixture fixture = new Fixture("0::/tenant/server\n", v2Mount("/", "/limits"));
        fixture.v2("/limits/tenant/server", "1000", "400", "");
        fixture.v2("/limits/tenant", "2000", "1900", "");
        fixture.v2("/limits", "max", "2200", "");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertEquals(1000, snapshot.limitBytes());
        assertEquals(400, snapshot.usedBytes());
        assertEquals(0.95, snapshot.pressureFraction(), 0.0001);
        assertEquals(100, snapshot.headroomBytes());
    }

    @Test
    public void unlimitedChildStillHonorsFiniteParent() {
        Fixture fixture = new Fixture("0::/tenant/server\n", v2Mount("/tenant", "/limits"));
        fixture.v2("/limits/server", "max", "400", "");
        fixture.v2("/limits", "1500", "1200", "");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertEquals(1500, snapshot.limitBytes());
        assertEquals(1200, snapshot.usedBytes());
        assertEquals(0.8, snapshot.pressureFraction(), 0.0001);
    }

    @Test
    public void v1UsesMemoryControllerAndHierarchicalCacheStatistics() {
        Fixture fixture = new Fixture("3:cpu,cpuacct:/server\n5:memory:/docker/server\n",
                "21 20 0:27 /docker /limits rw,nosuid - cgroup cgroup rw,memory\n");
        fixture.v1("/limits/server", "1000", "950",
                "inactive_file 900\ndirty 0\nwriteback 0\ntotal_inactive_file 200\ntotal_dirty 40\ntotal_writeback 10\n");
        fixture.v1("/limits", "9223372036854771712", "1000", "");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertEquals(1000, snapshot.limitBytes());
        assertEquals(800, snapshot.workingSetBytes());
        assertEquals(0.8, snapshot.pressureFraction(), 0.0001);
    }

    @Test
    public void dirtyAndWritebackCacheRemainChargedAndCannotUnderflow() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/", "/limits"));
        fixture.v2("/limits", "1000", "950",
                "inactive_file 100\nfile_dirty 80\nfile_writeback 90\n");
        assertEquals(950, fixture.monitor().sample().workingSetBytes());
        fixture.files.put("/limits/memory.stat", "inactive_file 100\nfile_dirty 120\nfile_writeback 0\n");
        assertEquals(950, fixture.monitor().sample().workingSetBytes());
    }

    @Test
    public void missingOrMalformedStatisticsDoNotDiscountUsage() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/", "/limits"));
        fixture.v2("/limits", "1000", "950", "inactive_file 800\nfile_dirty invalid\nfile_writeback 0\n");
        assertEquals(950, fixture.monitor().sample().workingSetBytes());
        fixture.files.remove("/limits/memory.stat");
        assertEquals(950, fixture.monitor().sample().workingSetBytes());
    }

    @Test
    public void sampleCacheAvoidsRepeatedFileReadsAndRefreshesUsageAndLimits() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/", "/limits"));
        fixture.v2("/limits", "1000", "500", "");
        ContainerMemory monitor = fixture.monitor();
        ContainerMemory.Snapshot first = monitor.sample();
        int reads = fixture.reads.get();
        fixture.files.put("/limits/memory.current", "900");
        fixture.time.set(199_999_999L);
        assertSame(first, monitor.sample());
        assertEquals(reads, fixture.reads.get());
        fixture.time.set(200_000_000L);
        assertEquals(900, monitor.sample().usedBytes());
        fixture.files.put("/limits/memory.max", "800");
        fixture.time.set(400_000_000L);
        assertEquals(1.125, monitor.sample().pressureFraction(), 0.0001);
        assertEquals(0, monitor.sample().headroomBytes());
    }

    @Test
    public void transientReadFailureRetainsKnownFiniteConstraintAndUsage() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/", "/limits"));
        fixture.v2("/limits", "1000", "950", "");
        ContainerMemory monitor = fixture.monitor();
        assertEquals(0.95, monitor.sample().pressureFraction(), 0.0001);
        fixture.files.remove("/limits/memory.current");
        fixture.files.put("/limits/memory.max", "invalid");
        fixture.time.set(200_000_000L);
        assertEquals(0.95, monitor.sample().pressureFraction(), 0.0001);
        assertEquals(1000, monitor.sample().limitBytes());
    }

    @Test
    public void unreadableUsageForNewFiniteLimitIsConservative() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/", "/limits"));
        fixture.files.put("/limits/memory.max", "1000");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertTrue(snapshot.supported());
        assertEquals(1, snapshot.pressureFraction(), 0);
        assertEquals(0, snapshot.headroomBytes());
    }

    @Test
    public void unsupportedAndUnlimitedEnvironmentsDoNotInventConstraints() {
        Fixture fixture = new Fixture("0::/\n", v2Mount("/", "/limits"));
        fixture.v2("/limits", "max", "1000", "");
        assertFalse(fixture.monitor().sample().supported());
        fixture.files.put("/limits/memory.max", "not a number");
        assertFalse(fixture.monitor().sample().supported());
        fixture.files.clear();
        ContainerMemory.Snapshot unsupported = fixture.monitor().sample();
        assertFalse(unsupported.supported());
        assertEquals(Long.MAX_VALUE, unsupported.headroomBytes());
        assertEquals(0, unsupported.pressureFraction(), 0);
    }

    @Test
    public void escapedMountPathsResolveWithoutTraversingOutsideMount() {
        Fixture fixture = new Fixture("0::/tenant/server\n", v2Mount("/tenant", "/my\\040limits"));
        fixture.v2("/my limits/server", "1000", "600", "");
        fixture.v2("/my limits", "max", "600", "");
        assertEquals(0.6, fixture.monitor().sample().pressureFraction(), 0.0001);
    }

    @Test
    public void unmatchedNamespaceMountDoesNotAddAnUnrelatedAncestorLimit() {
        Fixture fixture = new Fixture("0::/server\n",
                v2Mount("/docker/container", "/limits") + v2Mount("/unrelated", "/other"));
        fixture.v2("/limits/server", "1000", "600", "");
        fixture.v2("/limits", "max", "600", "");
        fixture.v2("/other", "100", "100", "");
        ContainerMemory.Snapshot snapshot = fixture.monitor().sample();
        assertEquals(1000, snapshot.limitBytes());
        assertEquals(0.6, snapshot.pressureFraction(), 0.0001);
    }

    private static String v2Mount(String root, String mountPoint) {
        return "21 20 0:27 " + root + " " + mountPoint + " rw,nosuid - cgroup2 cgroup rw\n";
    }

    private final class Fixture {
        private final Map<String, String> files = new HashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicLong time = new AtomicLong();

        private Fixture(String cgroup, String mountInfo) {
            files.put("/proc/self/cgroup", cgroup);
            files.put("/proc/self/mountinfo", mountInfo);
        }

        private void v2(String path, String limit, String used, String stat) {
            files.put(path + "/memory.max", limit);
            files.put(path + "/memory.current", used);
            files.put(path + "/memory.stat", stat);
        }

        private void v1(String path, String limit, String used, String stat) {
            files.put(path + "/memory.limit_in_bytes", limit);
            files.put(path + "/memory.usage_in_bytes", used);
            files.put(path + "/memory.stat", stat);
        }

        private ContainerMemory monitor() {
            return new ContainerMemory(new ContainerMemory.Sources(fileSystem, this::read, time::get));
        }

        private String read(Path path) throws IOException {
            reads.incrementAndGet();
            String content = files.get(path.toString());
            if (content == null) {
                throw new IOException("Missing fixture: " + path);
            }
            return content;
        }
    }
}
