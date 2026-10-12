package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import art.arcane.volmlib.nativelib.terrain.NativeGenerationRegistry.Definition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRegistryGsonBoundaryTest {
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider generated = VanillaRegistries.createWorldLookup();
        registries = new RegistryAccess.ImmutableRegistryAccess(List.of(copy(generated, Registries.BIOME),
                copy(generated, Registries.DIMENSION_TYPE), copy(generated, Registries.PLACED_FEATURE))).freeze();
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> copy(HolderLookup.Provider source, ResourceKey<? extends Registry<T>> key) {
        MappedRegistry<T> registry = new MappedRegistry<>(key, Lifecycle.stable());
        for (Holder.Reference<T> holder : source.lookupOrThrow(key).listElements().toList()) {
            T value = holder.value();
            if (value instanceof Biome biome) {
                value = (T) new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8F).downfall(0.4F)
                        .specialEffects(biome.getSpecialEffects()).generationSettings(BiomeGenerationSettings.EMPTY)
                        .mobSpawnSettings(MobSpawnSettings.EMPTY).build();
            }
            registry.register(holder.key(), value, RegistrationInfo.BUILT_IN);
        }
        registry.bindAllTagsToEmpty();
        return registry.freeze();
    }

    @Test
    void generatedBiomeRoundTripsNativeCodecWithoutGsonCrossing() {
        NativeGenerationRegistryImpl access = new NativeGenerationRegistryImpl(() -> registries);
        for (String registry : List.of("minecraft:worldgen/biome")) {
            String name = registry.equals("minecraft:worldgen/biome") ? "minecraft:plains" : "minecraft:overworld";
            Definition actual = access.generatedDefinition(registry, name);
            Definition canonical = access.canonicalDefinition(registry, name, actual.value());
            assertEquals(JsonParser.parseString(actual.value()), JsonParser.parseString(canonical.value()));
            JsonObject json = JsonParser.parseString(actual.value()).getAsJsonObject();
            String booleanField = registry.equals("minecraft:worldgen/biome") ? "has_precipitation" : "has_skylight";
            assertTrue(json.get(booleanField).getAsJsonPrimitive().isBoolean());
            assertTrue(json.get(booleanField).getAsBoolean());
        }
    }

    @Test
    void malformedUnboundedAndNullDefinitionsRefuseBeforeNativeCodec() {
        NativeGenerationRegistryImpl access = new NativeGenerationRegistryImpl(() -> registries);
        for (String json : List.of("{} trailing", "{\"a\":null}", "{\"a\":1,\"a\":2}",
                "{\"a\":[1,true]}", "{\"a\":1e500}", "{\"a\":9223372036854775808}",
                "{\"a\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> access.canonicalDefinition("minecraft:worldgen/biome", "minecraft:plains", json));
        }
    }

    @Test
    void registryNativeBoundaryHasNoJsonOpsReference() throws IOException {
        Class<?> type = NativeGenerationRegistryImpl.class;
        while (type != Object.class) {
            try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertTrue(input != null);
                assertFalse(new String(input.readAllBytes(), StandardCharsets.ISO_8859_1)
                        .contains("com/mojang/serialization/JsonOps"));
            }
            type = type.getSuperclass();
        }
    }
}
