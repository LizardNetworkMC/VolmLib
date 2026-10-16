package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NativeSpawnTableMerger {
    private NativeSpawnTableMerger() {
    }

    public static WeightedRandomList<MobSpawnSettings.SpawnerData> merge(
            WeightedRandomList<MobSpawnSettings.SpawnerData> vanillaSpawns,
            WeightedRandomList<MobSpawnSettings.SpawnerData> explicitSpawns) {
        List<MobSpawnSettings.SpawnerData> entries = new ArrayList<>(
                vanillaSpawns.unwrap().size() + explicitSpawns.unwrap().size());
        Set<EntityType<?>> explicitTypes = new HashSet<>();
        for (MobSpawnSettings.SpawnerData entry : explicitSpawns.unwrap()) {
            explicitTypes.add(entry.type);
        }
        for (MobSpawnSettings.SpawnerData entry : vanillaSpawns.unwrap()) {
            if (!explicitTypes.contains(entry.type)) {
                entries.add(entry);
            }
        }
        entries.addAll(explicitSpawns.unwrap());
        return WeightedRandomList.create(entries);
    }
}
