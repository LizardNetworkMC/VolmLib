/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.EncodeFailures;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.WorldgenBiome;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.WorldgenDimensionType;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.WorldgenLevelStem;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.WorldgenPlacedFeature;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.WorldgenSpawn;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeWorldgenSnapshot.WorldgenStructure;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

public final class NativeWorldgenScan {
    private static final int FAILURE_SAMPLES = 5;
    private static final int DECORATION_STEPS = GenerationStep.Decoration.values().length;

    private final RegistryAccess access;
    private final UnaryOperator<Biome> unmodifiedBiome;
    private final List<PendingBiome> biomes = new ArrayList<>();
    private final List<PendingPlacedFeature> placedFeatures = new ArrayList<>();
    private final List<WorldgenStructure> structures = new ArrayList<>();
    private final List<PendingDimensionType> dimensionTypes = new ArrayList<>();
    private final List<PendingLevelStem> levelStems = new ArrayList<>();
    private final Map<String, List<String>> blockTags = new TreeMap<>();

    private NativeWorldgenScan(RegistryAccess access, UnaryOperator<Biome> unmodifiedBiome) {
        this.access = Objects.requireNonNull(access, "access");
        this.unmodifiedBiome = Objects.requireNonNull(unmodifiedBiome, "unmodifiedBiome");
    }

    public static NativeWorldgenScan scan(NativeModdedServer server, NativeModdedLoader loader) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(loader, "loader");
        NativeWorldgenScan scan = new NativeWorldgenScan(server.registryAccess(), loader::unmodifiedBiome);
        scan.scanBiomes();
        scan.scanPlacedFeatures();
        scan.scanStructures();
        scan.scanDimensionTypes();
        scan.scanLevelStems();
        scan.scanBlockTags();
        return scan;
    }

    public int entryCount() {
        return biomes.size() + placedFeatures.size() + structures.size() + dimensionTypes.size() + levelStems.size();
    }

    public NativeWorldgenSnapshot encode() {
        JsonEncoder encoder = new JsonEncoder(RegistryOps.create(JsonOps.INSTANCE, access));
        Encoder<Biome> biomeEncoder = Biome.DIRECT_CODEC.comap(unmodifiedBiome);
        Encoder<List<PlacementModifier>> placementEncoder = PlacementModifier.CODEC.listOf();
        List<WorldgenBiome> encodedBiomes = new ArrayList<>(biomes.size());
        for (PendingBiome pending : biomes) {
            JsonElement definition = encoder.encode(biomeEncoder, pending.biome(), "biome " + pending.key());
            encodedBiomes.add(new WorldgenBiome(pending.key(), pending.temperature(), downfall(definition),
                    pending.hasPrecipitation(), pending.tags(), pending.featureSteps(), pending.spawns(), definition));
        }
        List<WorldgenPlacedFeature> encodedFeatures = new ArrayList<>(placedFeatures.size());
        for (PendingPlacedFeature pending : placedFeatures) {
            JsonElement config = encoder.encode(Feature.DIRECT_CODEC, pending.placed().feature().value(), "feature of " + pending.key());
            JsonElement placement = encoder.encode(placementEncoder, pending.placed().placement(), "placement of " + pending.key());
            encodedFeatures.add(new WorldgenPlacedFeature(pending.key(), pending.feature(), pending.featureType(), config, placement));
        }
        List<WorldgenDimensionType> encodedDimensionTypes = new ArrayList<>(dimensionTypes.size());
        for (PendingDimensionType pending : dimensionTypes) {
            DimensionType type = pending.type();
            JsonElement definition = encoder.encode(DimensionType.DIRECT_CODEC, type, "dimension type " + pending.key());
            encodedDimensionTypes.add(new WorldgenDimensionType(pending.key(), type.minY(), type.height(), type.logicalHeight(), definition));
        }
        List<WorldgenLevelStem> encodedStems = new ArrayList<>(levelStems.size());
        for (PendingLevelStem pending : levelStems) {
            ChunkGenerator generator = pending.stem().generator();
            JsonElement generatorJson = encoder.encode(ChunkGenerator.CODEC, generator, "generator of " + pending.key());
            JsonElement biomeSource = encoder.encode(BiomeSource.CODEC, generator.getBiomeSource(), "biome source of " + pending.key());
            encodedStems.add(new WorldgenLevelStem(pending.key(), pending.dimensionType(), pending.generatorType(),
                    typeOf(biomeSource), generatorJson));
        }
        return new NativeWorldgenSnapshot(encodedBiomes, encodedFeatures, structures, encodedDimensionTypes, encodedStems,
                blockTags, encoder.failures());
    }

    private void scanBiomes() {
        Registry<Biome> registry = access.lookupOrThrow(Registries.BIOME);
        for (Holder.Reference<Biome> holder : sorted(registry)) {
            Biome biome = holder.value();
            biomes.add(new PendingBiome(key(holder.key()), biome, biome.getBaseTemperature(), biome.hasPrecipitation(),
                    tags(holder), featureSteps(biome), spawns(biome)));
        }
    }

    private void scanPlacedFeatures() {
        Registry<PlacedFeature> registry = access.lookupOrThrow(Registries.PLACED_FEATURE);
        for (Holder.Reference<PlacedFeature> holder : sorted(registry)) {
            PlacedFeature placed = holder.value();
            Holder<Feature> feature = placed.feature();
            String featureKey = feature.unwrapKey().map(NativeWorldgenScan::key).orElse("");
            Identifier featureType = BuiltInRegistries.FEATURE_TYPE.getKey(feature.value().codec());
            placedFeatures.add(new PendingPlacedFeature(key(holder.key()), placed, featureKey,
                    featureType == null ? "" : featureType.toString()));
        }
    }

    private void scanStructures() {
        Map<String, List<String>> sets = structureSets();
        Registry<Structure> registry = access.lookupOrThrow(Registries.STRUCTURE);
        for (Holder.Reference<Structure> holder : sorted(registry)) {
            Structure structure = holder.value();
            String key = key(holder.key());
            Identifier type = BuiltInRegistries.STRUCTURE_TYPE.getKey(structure.type());
            HolderSet<Biome> biomeSet = structure.biomes();
            String biomeTag = biomeSet.unwrapKey().map(tag -> tag.location().toString()).orElse("");
            structures.add(new WorldgenStructure(key, type == null ? "" : type.toString(),
                    structure.step().getSerializedName(), structure.terrainAdaptation().getSerializedName(),
                    biomeTag, holderKeys(biomeSet), sets.getOrDefault(key, List.of())));
        }
    }

    private void scanDimensionTypes() {
        Registry<DimensionType> registry = access.lookupOrThrow(Registries.DIMENSION_TYPE);
        for (Holder.Reference<DimensionType> holder : sorted(registry)) {
            dimensionTypes.add(new PendingDimensionType(key(holder.key()), holder.value()));
        }
    }

    private void scanLevelStems() {
        Optional<Registry<LevelStem>> registry = access.lookup(Registries.LEVEL_STEM);
        if (registry.isEmpty()) {
            return;
        }
        for (Holder.Reference<LevelStem> holder : sorted(registry.get())) {
            LevelStem stem = holder.value();
            String dimensionType = stem.type().unwrapKey().map(NativeWorldgenScan::key).orElse("");
            String generatorType = stem.generator().getTypeNameForDataFixer().map(Identifier::toString).orElse("");
            levelStems.add(new PendingLevelStem(key(holder.key()), stem, dimensionType, generatorType));
        }
    }

    private Map<String, List<String>> structureSets() {
        Map<String, TreeSet<String>> owners = new HashMap<>();
        Registry<StructureSet> registry = access.lookupOrThrow(Registries.STRUCTURE_SET);
        for (Holder.Reference<StructureSet> holder : sorted(registry)) {
            String setKey = key(holder.key());
            for (StructureSet.StructureSelectionEntry entry : holder.value().structures()) {
                Optional<ResourceKey<Structure>> structureKey = entry.structure().unwrapKey();
                if (structureKey.isPresent()) {
                    owners.computeIfAbsent(key(structureKey.get()), ignored -> new TreeSet<>()).add(setKey);
                }
            }
        }
        Map<String, List<String>> result = new HashMap<>(Math.max(16, owners.size() * 2));
        for (Map.Entry<String, TreeSet<String>> entry : owners.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    private static List<List<String>> featureSteps(Biome biome) {
        List<HolderSet<PlacedFeature>> features = biome.getGenerationSettings().features();
        int stepCount = Math.max(DECORATION_STEPS, features.size());
        List<List<String>> steps = new ArrayList<>(stepCount);
        for (int step = 0; step < stepCount; step++) {
            steps.add(step < features.size() ? holderKeysInOrder(features.get(step)) : List.of());
        }
        return steps;
    }

    private static List<WorldgenSpawn> spawns(Biome biome) {
        MobSpawnSettings settings = biome.getAttributes().applyModifier(
                EnvironmentAttributes.NATURAL_MOB_SPAWNS, EnvironmentAttributes.NATURAL_MOB_SPAWNS.defaultValue());
        List<WorldgenSpawn> spawns = new ArrayList<>();
        for (MobCategory category : MobCategory.values()) {
            for (Weighted<MobSpawnSettings.SpawnerData> entry : settings.getMobsToSpawn(category).unwrap()) {
                MobSpawnSettings.SpawnerData data = entry.value();
                spawns.add(new WorldgenSpawn(category.getSerializedName(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(data.type()).toString(), entry.weight(),
                        data.count().minInclusive(), data.count().maxInclusive()));
            }
        }
        return spawns;
    }

    private static <T> List<String> tags(Holder<T> holder) {
        return holder.tags().map(tag -> tag.location().toString()).sorted().toList();
    }

    private void scanBlockTags() {
        BuiltInRegistries.BLOCK.getTags().forEach((HolderSet.Named<Block> tag) ->
                blockTags.put(tag.key().location().toString(), holderKeys(tag)));
    }

    private static <T> List<String> holderKeysInOrder(HolderSet<T> holders) {
        List<String> keys = new ArrayList<>(holders.size());
        for (Holder<T> holder : holders) {
            Optional<ResourceKey<T>> key = holder.unwrapKey();
            if (key.isPresent()) {
                keys.add(key(key.get()));
            }
        }
        return keys;
    }

    private static <T> List<String> holderKeys(HolderSet<T> holders) {
        TreeSet<String> keys = new TreeSet<>(holderKeysInOrder(holders));
        return List.copyOf(keys);
    }

    private static <T> List<Holder.Reference<T>> sorted(Registry<T> registry) {
        List<Holder.Reference<T>> holders = new ArrayList<>(registry.size());
        registry.listElements().forEach(holders::add);
        holders.sort(Comparator.comparing((Holder.Reference<T> holder) -> key(holder.key())));
        return holders;
    }

    private static String key(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    private static float downfall(JsonElement definition) {
        if (definition == null || !definition.isJsonObject()) {
            return 0F;
        }
        JsonElement downfall = definition.getAsJsonObject().get("downfall");
        return downfall instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsFloat() : 0F;
    }

    private static String typeOf(JsonElement encoded) {
        if (encoded == null || !encoded.isJsonObject()) {
            return "";
        }
        JsonObject object = encoded.getAsJsonObject();
        JsonElement type = object.get("type");
        return type instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : "";
    }

    private record PendingBiome(
            String key,
            Biome biome,
            float temperature,
            boolean hasPrecipitation,
            List<String> tags,
            List<List<String>> featureSteps,
            List<WorldgenSpawn> spawns) {
    }

    private record PendingPlacedFeature(String key, PlacedFeature placed, String feature, String featureType) {
    }

    private record PendingDimensionType(String key, DimensionType type) {
    }

    private record PendingLevelStem(String key, LevelStem stem, String dimensionType, String generatorType) {
    }

    private static final class JsonEncoder {
        private final RegistryOps<JsonElement> ops;
        private final List<String> samples = new ArrayList<>(FAILURE_SAMPLES);
        private int count;
        private Throwable firstException;

        private JsonEncoder(RegistryOps<JsonElement> ops) {
            this.ops = ops;
        }

        private <T> JsonElement encode(Encoder<T> encoder, T value, String label) {
            try {
                DataResult<JsonElement> result = encoder.encodeStart(ops, value);
                Optional<JsonElement> encoded = result.result();
                if (encoded.isPresent()) {
                    return encoded.get();
                }
                fail(label + ": " + result.error().map(DataResult.Error::message).orElse("no result"), null);
            } catch (RuntimeException failure) {
                fail(label + ": " + failure, failure);
            }
            return null;
        }

        private void fail(String message, Throwable failure) {
            count++;
            if (samples.size() < FAILURE_SAMPLES) {
                samples.add(message);
            }
            if (firstException == null && failure != null) {
                firstException = failure;
            }
        }

        private EncodeFailures failures() {
            return new EncodeFailures(count, samples, firstException);
        }
    }
}
