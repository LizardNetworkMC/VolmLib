package art.arcane.volmlib.nativelib.terrain;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeBlockChangeSetTest {
    @Test
    public void recordsDistinctPositionsInsideTheRegionOnly() {
        NativeBlockChangeSet changes = NativeBlockChangeSet.create(
                new NativeBlockChangeSet.Bounds(-2, 4, 0, 6, -64, 384));

        changes.add(-17, -64, 70);
        changes.add(-17, -64, 70);
        changes.add(15, 319, 111);
        changes.add(16, 70, 80);
        changes.add(-33, 70, 80);
        changes.add(0, -65, 80);
        changes.add(0, 320, 80);
        changes.add(0, 70, 63);

        assertEquals(2, changes.size());
        assertTrue(changes.contains(-17, -64, 70));
        assertTrue(changes.contains(15, 319, 111));
        assertFalse(changes.contains(16, 70, 80));
        assertFalse(changes.contains(0, 70, 63));
    }

    @Test
    public void visitsChangedChunksAndTheirPositionsInAscendingHeight() {
        NativeBlockChangeSet changes = NativeBlockChangeSet.create(
                new NativeBlockChangeSet.Bounds(-1, -1, 1, 1, -64, 384));
        changes.add(20, 100, -3);
        changes.add(17, 90, -14);
        changes.add(18, -60, -2);
        changes.add(-5, 64, 5);

        List<String> chunks = new ArrayList<>();
        changes.forEachChunk((chunkX, chunkZ) -> chunks.add(chunkX + "," + chunkZ));
        List<String> positions = new ArrayList<>();
        changes.forEachPosition(1, -1, (x, y, z) -> positions.add(x + "," + y + "," + z));

        assertEquals(List.of("1,-1", "-1,0"), chunks);
        assertEquals(List.of("18,-60,-2", "17,90,-14", "20,100,-3"), positions);
    }

    @Test
    public void disabledSetIgnoresEveryPosition() {
        NativeBlockChangeSet changes = NativeBlockChangeSet.disabled();

        changes.add(0, 64, 0);

        assertTrue(changes.isEmpty());
        assertFalse(changes.contains(0, 64, 0));
        changes.forEachChunk((chunkX, chunkZ) -> {
            throw new AssertionError("Disabled change sets have no chunks");
        });
    }
}
