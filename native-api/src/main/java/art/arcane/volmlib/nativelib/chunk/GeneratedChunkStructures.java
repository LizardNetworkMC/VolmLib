package art.arcane.volmlib.nativelib.chunk;

import art.arcane.volmlib.nativelib.NativeBinding;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;

import java.util.List;
import java.util.Objects;

@NativeBinding("chunk.GeneratedChunkStructuresImpl")
public interface GeneratedChunkStructures {
    Snapshot read(Chunk chunk);

    record Snapshot(List<Entry> starts, List<ChunkPosition> references) {
        public Snapshot {
            starts = List.copyOf(starts);
            references = List.copyOf(references);
        }
    }

    record Entry(NamespacedKey structureKey, NamespacedKey typeKey, Bounds bounds) {
        public Entry {
            Objects.requireNonNull(structureKey, "structureKey");
            Objects.requireNonNull(typeKey, "typeKey");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }
}
