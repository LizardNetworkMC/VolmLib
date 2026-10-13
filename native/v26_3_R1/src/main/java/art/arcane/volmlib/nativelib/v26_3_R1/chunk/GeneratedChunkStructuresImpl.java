package art.arcane.volmlib.nativelib.v26_3_R1.chunk;

import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.GeneratedChunkStructures;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.generator.structure.CraftGeneratedStructure;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.util.BoundingBox;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Objects;

import java.util.ArrayList;
import java.util.List;

public final class GeneratedChunkStructuresImpl implements GeneratedChunkStructures {
    @Override
    public Snapshot read(Chunk chunk) {
        if (!(chunk instanceof CraftChunk current) || !Bukkit.isOwnedByCurrentRegion(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            throw new IllegalStateException("Generated structure metadata requires the chunk's owning thread");
        }
        LevelChunk loaded = current.getCraftWorld().getHandle().getChunkIfLoaded(chunk.getX(), chunk.getZ());
        if (loaded == null) {
            return new Snapshot(List.of(), List.of());
        }
        List<Entry> starts = new ArrayList<>(loaded.getAllStarts().size());
        for (StructureStart start : loaded.getAllStarts().values()) {
            if (start.isValid()) {
                GeneratedStructure generated = new CraftGeneratedStructure(start);
                BoundingBox bounds = generated.getBoundingBox();
                starts.add(new Entry(Objects.requireNonNull(RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE)
                        .getKey(generated.getStructure())), Objects.requireNonNull(RegistryAccess.registryAccess()
                        .getRegistry(RegistryKey.STRUCTURE_TYPE).getKey(generated.getStructure().getStructureType())),
                        new Bounds(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                                bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ())));
            }
        }
        List<ChunkPosition> references = new ArrayList<>();
        for (LongSet positions : loaded.getAllReferences().values()) {
            for (long position : positions) {
                references.add(new ChunkPosition((int) position, (int) (position >> 32)));
            }
        }
        return new Snapshot(starts, references);
    }
}
