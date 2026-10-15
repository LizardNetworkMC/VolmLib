package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import art.arcane.volmlib.nativelib.terrain.feature.NativeImportedFeatureControl;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class NativeImportedFeatureScopeTest {
    private static final HolderOwner<PlacedFeature> OWNER = new HolderOwner<>() {
    };

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void scopedSettingsDropOnlyTheFeaturesDeniedForThatBiome() {
        Holder<PlacedFeature> modTree = feature("terralith:trees/oak");
        Holder<PlacedFeature> modFlower = feature("terralith:flowers/lavender");
        Holder<PlacedFeature> vanillaTree = feature("minecraft:trees_plains");
        BiomeGenerationSettings settings = new BiomeGenerationSettings.PlainBuilder()
                .addFeature(9, vanillaTree)
                .addFeature(9, modTree)
                .addFeature(9, modFlower)
                .build();
        NativeImportedFeatureControl control = new DenyInBiome("minecraft:forest", "terralith:trees/oak");

        BiomeGenerationSettings forest = NativeImportedFeatureStage.scopeSettings(settings, "minecraft:forest", Set.of(), control);
        BiomeGenerationSettings modBiome = NativeImportedFeatureStage.scopeSettings(settings, "terralith:lavender_forest", Set.of(), control);

        assertEquals(List.of(vanillaTree, modFlower), step(forest, 9));
        assertEquals(List.of(vanillaTree, modTree, modFlower), step(modBiome, 9));
        assertEquals(10, forest.features().size());
    }

    private static List<Holder<PlacedFeature>> step(BiomeGenerationSettings settings, int step) {
        List<Holder<PlacedFeature>> features = new ArrayList<>();
        settings.features().get(step).forEach(features::add);
        return features;
    }

    private static Holder<PlacedFeature> feature(String key) {
        return Holder.Reference.createStandAlone(OWNER, ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(key)));
    }

    private record DenyInBiome(String biome, String feature) implements NativeImportedFeatureControl {
        @Override
        public boolean shouldGenerateFeatures() {
            return true;
        }

        @Override
        public boolean shouldGenerateStepOrdinal(int step) {
            return true;
        }

        @Override
        public boolean shouldGenerate(String featureKey) {
            return true;
        }

        @Override
        public boolean hasFeatureFilter() {
            return false;
        }

        @Override
        public boolean hasBiomeFeatureRules() {
            return true;
        }

        @Override
        public boolean shouldGenerateInBiome(String biomeKey, Set<String> biomeTags, String featureKey) {
            return !(biome.equals(biomeKey) && feature.equals(featureKey));
        }
    }
}
