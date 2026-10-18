package art.arcane.volmlib.nativelib.terrain;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeBlockChangeSnapshotTest {
    @Test
    public void snapshotsOnlyTrackedPositionsAndCombinesChangesWithRecordedWrites() {
        NativeBlockChangeSet.Bounds bounds = new NativeBlockChangeSet.Bounds(-1, -1, 1, 1, -64, 384);
        NativeBlockChangeSet positions = NativeBlockChangeSet.create(bounds);
        positions.add(-1, -30, 17);
        positions.add(2, 65, 2);
        Map<Position, String> blocks = new HashMap<>();
        Position replaced = new Position(-1, -30, 17);
        Position untouched = new Position(2, 65, 2);
        blocks.put(replaced, "stone");
        blocks.put(untouched, "air");
        AtomicInteger reads = new AtomicInteger();
        NativeBlockChangeSnapshot.BlockReader<String> reader = (x, y, z) -> {
            reads.incrementAndGet();
            return blocks.get(new Position(x, y, z));
        };
        NativeBlockChangeSnapshot<String> snapshot = NativeBlockChangeSnapshot.capture(positions, reader);
        NativeBlockChangeSet changes = NativeBlockChangeSet.create(bounds);
        changes.add(3, 64, 3);
        blocks.put(replaced, "iron_ore");
        blocks.put(untouched, new String("air"));
        blocks.put(new Position(10, 70, 10), "stone");

        snapshot.collectChanges(changes, reader);

        assertEquals(4, reads.get());
        assertEquals(2, changes.size());
        assertTrue(changes.contains(-1, -30, 17));
        assertTrue(changes.contains(3, 64, 3));
        assertFalse(changes.contains(2, 65, 2));
        assertFalse(changes.contains(10, 70, 10));
    }

    @Test
    public void emptySnapshotNeverReadsTheWorld() {
        AtomicInteger reads = new AtomicInteger();
        NativeBlockChangeSnapshot.BlockReader<String> reader = (x, y, z) -> {
            reads.incrementAndGet();
            return "air";
        };
        NativeBlockChangeSnapshot<String> snapshot = NativeBlockChangeSnapshot.capture(
                NativeBlockChangeSet.disabled(), reader);

        snapshot.collectChanges(NativeBlockChangeSet.disabled(), reader);

        assertEquals(0, reads.get());
    }

    private record Position(int x, int y, int z) {
    }
}
