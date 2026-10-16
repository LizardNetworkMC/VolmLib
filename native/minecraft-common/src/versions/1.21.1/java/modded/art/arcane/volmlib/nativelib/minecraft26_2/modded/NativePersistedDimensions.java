package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NativePersistedDimensions {
    private static volatile Map<ResourceKey<LevelStem>, LevelStem> saved = Map.of();

    private NativePersistedDimensions() {
    }

    public static void loaded(WorldDimensions dimensions) {
        saved = Map.copyOf(dimensions.dimensions());
    }

    public static WorldDimensions persisted(WorldDimensions live) {
        Map<ResourceKey<LevelStem>, LevelStem> savedStems = saved;
        Map<ResourceKey<LevelStem>, LevelStem> restored = null;
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : live.dimensions().entrySet()) {
            LevelStem original = savedStems.get(entry.getKey());
            if (original == null || !replaced(entry.getValue(), original)) {
                continue;
            }
            if (restored == null) {
                restored = new LinkedHashMap<>(live.dimensions());
            }
            restored.put(entry.getKey(), original);
        }
        return restored == null ? live : new WorldDimensions(restored);
    }

    private static boolean replaced(LevelStem live, LevelStem original) {
        return live.generator() instanceof NativeModdedChunkGenerator<?, ?, ?>
                && !(original.generator() instanceof NativeModdedChunkGenerator<?, ?, ?>);
    }
}
