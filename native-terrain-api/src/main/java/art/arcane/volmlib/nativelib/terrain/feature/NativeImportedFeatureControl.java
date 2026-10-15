package art.arcane.volmlib.nativelib.terrain.feature;

import java.util.Set;

public interface NativeImportedFeatureControl {
    boolean shouldGenerateFeatures();
    boolean shouldGenerateStepOrdinal(int step);
    boolean shouldGenerate(String featureKey);
    boolean hasFeatureFilter();

    default boolean hasBiomeFeatureRules() {
        return false;
    }

    default boolean shouldGenerateInBiome(String biomeKey, Set<String> biomeTags, String featureKey) {
        return true;
    }
}
