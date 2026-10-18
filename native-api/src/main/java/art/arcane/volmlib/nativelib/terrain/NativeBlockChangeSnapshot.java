package art.arcane.volmlib.nativelib.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NativeBlockChangeSnapshot<T> {
    private final List<Entry<T>> entries;

    private NativeBlockChangeSnapshot(List<Entry<T>> entries) {
        this.entries = entries;
    }

    public static <T> NativeBlockChangeSnapshot<T> capture(NativeBlockChangeSet positions, BlockReader<T> reader) {
        List<Entry<T>> entries = new ArrayList<>(positions.size());
        positions.forEachChunk((chunkX, chunkZ) -> positions.forEachPosition(chunkX, chunkZ,
                (x, y, z) -> entries.add(new Entry<>(x, y, z, reader.read(x, y, z)))));
        return new NativeBlockChangeSnapshot<>(entries);
    }

    public void collectChanges(NativeBlockChangeSet changes, BlockReader<T> reader) {
        for (Entry<T> entry : entries) {
            if (!Objects.equals(entry.state(), reader.read(entry.x(), entry.y(), entry.z()))) {
                changes.add(entry.x(), entry.y(), entry.z());
            }
        }
    }

    @FunctionalInterface
    public interface BlockReader<T> {
        T read(int x, int y, int z);
    }

    private record Entry<T>(int x, int y, int z, T state) {
    }
}
