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

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Objects;

public record NativeWorldgenSnapshot(
        List<WorldgenBiome> biomes,
        List<WorldgenPlacedFeature> placedFeatures,
        List<WorldgenStructure> structures,
        List<WorldgenDimensionType> dimensionTypes,
        List<WorldgenLevelStem> levelStems,
        EncodeFailures failures) {
    public NativeWorldgenSnapshot {
        biomes = List.copyOf(Objects.requireNonNull(biomes, "biomes"));
        placedFeatures = List.copyOf(Objects.requireNonNull(placedFeatures, "placedFeatures"));
        structures = List.copyOf(Objects.requireNonNull(structures, "structures"));
        dimensionTypes = List.copyOf(Objects.requireNonNull(dimensionTypes, "dimensionTypes"));
        levelStems = List.copyOf(Objects.requireNonNull(levelStems, "levelStems"));
        Objects.requireNonNull(failures, "failures");
    }

    public record WorldgenBiome(
            String key,
            float temperature,
            float downfall,
            boolean hasPrecipitation,
            List<String> tags,
            List<List<String>> featureSteps,
            List<WorldgenSpawn> spawns,
            JsonElement definition) {
        public WorldgenBiome {
            Objects.requireNonNull(key, "key");
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            featureSteps = Objects.requireNonNull(featureSteps, "featureSteps").stream().map(List::copyOf).toList();
            spawns = List.copyOf(Objects.requireNonNull(spawns, "spawns"));
        }
    }

    public record WorldgenSpawn(String category, String entity, int weight, int minCount, int maxCount) {
        public WorldgenSpawn {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(entity, "entity");
        }
    }

    public record WorldgenPlacedFeature(
            String key,
            String feature,
            String featureType,
            JsonElement config,
            JsonElement placement) {
        public WorldgenPlacedFeature {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(feature, "feature");
            Objects.requireNonNull(featureType, "featureType");
        }
    }

    public record WorldgenStructure(
            String key,
            String type,
            String step,
            String terrainAdaptation,
            String biomeTag,
            List<String> biomes,
            List<String> structureSets) {
        public WorldgenStructure {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(terrainAdaptation, "terrainAdaptation");
            Objects.requireNonNull(biomeTag, "biomeTag");
            biomes = List.copyOf(Objects.requireNonNull(biomes, "biomes"));
            structureSets = List.copyOf(Objects.requireNonNull(structureSets, "structureSets"));
        }
    }

    public record WorldgenDimensionType(String key, int minY, int height, int logicalHeight, JsonElement definition) {
        public WorldgenDimensionType {
            Objects.requireNonNull(key, "key");
        }
    }

    public record WorldgenLevelStem(
            String key,
            String dimensionType,
            String generatorType,
            String biomeSourceType,
            JsonElement generator) {
        public WorldgenLevelStem {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(dimensionType, "dimensionType");
            Objects.requireNonNull(generatorType, "generatorType");
            Objects.requireNonNull(biomeSourceType, "biomeSourceType");
        }
    }

    public record EncodeFailures(int count, List<String> samples, Throwable firstException) {
        public EncodeFailures {
            samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        }
    }
}
