package art.arcane.volmlib.nativelib.v26_3_R1.storage;

import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.CaptureOptions;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.CapturedStem;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.DeclaredEnvironment;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.Flat;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.InspectionContext;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.Limits;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.LoadedReceipt;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.Request;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.Snapshot;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.StorageFormat;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.Unrepresentable;
import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.VanillaNoise;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.LevelStem;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class NativeSavedWorldGenerationTest {
    private static RegistryAccess.Frozen registries;
    private static int version;
    private static final NamespacedKey WORLD_KEY = NamespacedKey.fromString("rift:test_generation");

    @TempDir
    Path temporary;
    private Path directory;
    private UUID uuid;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        version = SharedConstants.getCurrentVersion().dataVersion().version();
        HolderLookup.Provider generated = VanillaRegistries.createWorldLookup();
        registries = new RegistryAccess.ImmutableRegistryAccess(List.of(
                copy(generated, Registries.BIOME), copy(generated, Registries.STRUCTURE_SET),
                copy(generated, Registries.PLACED_FEATURE))).freeze();
    }

    private static <T> Registry<T> copy(HolderLookup.Provider source, ResourceKey<? extends Registry<T>> key) {
        MappedRegistry<T> registry = new MappedRegistry<>(key, Lifecycle.stable());
        for (Holder.Reference<T> holder : source.lookupOrThrow(key).listElements().toList()) {
            registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN);
        }
        registry.bindAllTagsToEmpty();
        return registry.freeze();
    }

    @BeforeEach
    void source() throws IOException {
        temporary = temporary.toRealPath();
        directory = temporary.resolve("dimensions/rift/test_generation");
        uuid = UUID.randomUUID();
        Path metadata = directory.resolve("data/paper/metadata.dat");
        Files.createDirectories(metadata.getParent());
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", version);
        root.put("data", PaperWorldMetadata.CODEC.encodeStart(NbtOps.INSTANCE, new PaperWorldMetadata(uuid)).getOrThrow());
        NbtIo.writeCompressed(root, metadata);
    }

    @Test
    void declaredEnvironmentSelectsItsOwnStemAmongAllThreeWithoutSourceWrites() throws IOException {
        write(generation("minecraft:overworld"));
        Map<Path, byte[]> original = files();
        Snapshot snapshot = inspector().inspect(request(World.Environment.NETHER));
        assertEquals("minecraft:the_nether", snapshot.selectedStem().toString());
        assertEquals("minecraft:nether", assertInstanceOf(VanillaNoise.class, snapshot.terrain()).noiseSettings().toString());
        assertEquals(uuid, snapshot.worldUuid());
        assertEquals(Long.MIN_VALUE + 22, snapshot.seed());
        assertTrue(snapshot.generateStructures());
        assertFalse(snapshot.bonusChest());
        assertEquals(2, snapshot.sourceFiles().size());
        assertTrue(snapshot.sourceFiles().stream().allMatch(file -> file.sha256().matches("[a-f0-9]{64}")));
        unchanged(original);
        assertFalse(Files.exists(directory.resolve("session.lock")));
        assertFalse(Files.exists(temporary.resolve("session.lock")));
    }

    @Test
    void savedNoisePresetsRecoverAmplifiedAndLargeBiomes() throws IOException {
        Map<String, WorldType> types = Map.of("minecraft:overworld", WorldType.NORMAL,
                "minecraft:amplified", WorldType.AMPLIFIED, "minecraft:large_biomes", WorldType.LARGE_BIOMES);
        for (Map.Entry<String, WorldType> type : types.entrySet()) {
            write(generation(type.getKey()));
            Snapshot snapshot = inspector().inspect(request(World.Environment.NORMAL));
            assertEquals(type.getValue(), assertInstanceOf(VanillaNoise.class, snapshot.terrain()).worldType());
        }
        Snapshot end = inspector().inspect(request(World.Environment.THE_END));
        assertEquals("minecraft:end", assertInstanceOf(VanillaNoise.class, end.terrain()).noiseSettings().toString());
    }

    @Test
    void flatSettingsPreserveLayersFeaturesLakesOverridesAndBiomeWithoutInventingAnOverride() throws IOException {
        CompoundTag generation = generation("minecraft:overworld");
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:flat");
        CompoundTag settings = new CompoundTag();
        settings.putString("biome", "minecraft:forest");
        settings.putBoolean("features", true);
        settings.putBoolean("lakes", true);
        settings.put("structure_overrides", new ListTag());
        ListTag layers = new ListTag();
        CompoundTag layer = new CompoundTag();
        layer.putInt("height", 3);
        layer.putString("block", "minecraft:stone");
        layers.add(layer);
        settings.put("layers", layers);
        generator.put("settings", settings);
        generation.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").put("generator", generator);
        write(generation);
        Snapshot snapshot = inspector().inspect(request(World.Environment.NORMAL));
        JsonObject flat = JsonParser.parseString(assertInstanceOf(Flat.class, snapshot.terrain()).settingsJson()).getAsJsonObject();
        assertEquals("minecraft:forest", flat.get("biome").getAsString());
        assertTrue(flat.get("features").getAsBoolean());
        assertTrue(flat.get("lakes").getAsBoolean());
        assertEquals(0, flat.getAsJsonArray("structure_overrides").size());
        assertEquals(3, flat.getAsJsonArray("layers").get(0).getAsJsonObject().get("height").getAsInt());
        assertEquals("minecraft:stone", flat.getAsJsonArray("layers").get(0).getAsJsonObject().get("block").getAsString());
        assertTrue(snapshot.fixedBiome().isEmpty());
        InspectionContext context = inspector();
        Flat normalized = context.normalizeFlatSettings("{\"layers\":[{\"height\":3.0,\"block\":\"minecraft:stone\"}],\"biome\":\"minecraft:forest\",\"features\":true,\"lakes\":true,\"structure_overrides\":[]}");
        assertEquals(assertInstanceOf(Flat.class, snapshot.terrain()).settingsJson(), normalized.settingsJson());
        assertEquals(normalized, context.normalizeFlatSettings(normalized.settingsJson()));
    }

    @Test
    void flatJsonNormalizationUsesCodecDefaultsAndRejectsUnprovenOrMalformedInput() throws IOException {
        InspectionContext context = inspector();
        assertEquals(context.normalizeFlatSettings("{\"layers\":[]}"), context.normalizeFlatSettings(
                "{\"layers\":[],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}"));
        for (String json : new String[]{"", "[]", "{} trailing", "{\"layers\":[],\"layers\":[]}",
                "{\"layers\":[],\"biome\":\"example:missing\"}", "{\"layers\":[],\"features\":1}",
                "{\"layers\":[{\"height\":1.5,\"block\":\"minecraft:stone\"}]}",
                "{\"layers\":[{\"height\":1,\"block\":\"example:missing\"}]}",
                "{\"layers\":[{\"height\":1,\"block\":\"minecraft:stone\",\"extra\":1}]}",
                "{\"layers\":[],\"custom\":true}", "{\"layers\":null}", "{\"layers\":[],\"features\":true,}"}) {
            assertThrows(IOException.class, () -> context.normalizeFlatSettings(json), json);
        }
        Limits limits = CaptureOptions.standard().limits();
        String source = "{\"layers\":[],\"biome\":\"minecraft:forest\"}";
        for (Limits budget : new Limits[]{
                new Limits(limits.compressedBytes(), 10, limits.depth(), limits.collectionElements(), limits.stringBytes()),
                new Limits(limits.compressedBytes(), limits.inflatedBytes(), limits.depth(), 1, limits.stringBytes()),
                new Limits(limits.compressedBytes(), limits.inflatedBytes(), limits.depth(), limits.collectionElements(), 3)}) {
            assertThrows(IOException.class, () -> inspector(budget).normalizeFlatSettings(source));
        }
        assertThrows(IOException.class, () -> context.normalizeFlatSettings("[".repeat(40) + "0" + "]".repeat(40)));
    }

    @Test
    void internalJsonNbtBoundaryUsesExactTypedValues() throws IOException {
        Limits limits = CaptureOptions.standard().limits();
        CompoundTag settings = SavedGenerationJson.toNbt(SavedGenerationJson.read(
                "{\"layers\":[{\"height\":3.0,\"block\":\"minecraft:stone\"}],\"features\":true,\"lakes\":false,\"structure_overrides\":[\"minecraft:villages\"]}", limits));
        assertEquals(Tag.TAG_BYTE, settings.get("features").getId());
        assertEquals(Tag.TAG_INT, settings.getList("layers").orElseThrow().getCompound(0).orElseThrow().get("height").getId());
        JsonObject encoded = JsonParser.parseString(SavedGenerationJson.flatJson(settings)).getAsJsonObject();
        assertTrue(encoded.get("features").getAsJsonPrimitive().isBoolean());
        assertTrue(encoded.get("features").getAsBoolean());
        assertFalse(encoded.get("lakes").getAsBoolean());
        assertEquals(3, encoded.getAsJsonArray("layers").get(0).getAsJsonObject().get("height").getAsInt());
        assertEquals("minecraft:villages", encoded.getAsJsonArray("structure_overrides").get(0).getAsString());
        for (String json : new String[]{"{\"layers\":[{},1]}", "{\"layers\":[1,true]}",
                "{\"height\":2147483648}", "{\"height\":-2147483649}", "{\"height\":1.5}", "{\"layers\":[null]}"}) {
            assertThrows(IOException.class, () -> SavedGenerationJson.toNbt(SavedGenerationJson.read(json, limits)), json);
        }
        CompoundTag invalid = settings.copy();
        invalid.putByte("features", (byte) 2);
        assertThrows(IOException.class, () -> SavedGenerationJson.flatJson(invalid));
        invalid.putBoolean("features", true);
        invalid.putDouble("height", 3);
        assertThrows(IOException.class, () -> SavedGenerationJson.flatJson(invalid));
    }

    @Test
    void savedGenerationCodecBoundaryHasNoMinecraftJsonOpsLinkage() throws IOException {
        for (Class<?> type : new Class<?>[]{NativeSavedWorldGenerationImpl.Inspector.class, SavedGenerationJson.class}) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (InputStream input = type.getResourceAsStream(resource)) {
                assertTrue(input != null);
                String bytecode = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(bytecode.contains("com/mojang/serialization/JsonOps"));
            }
        }
    }

    @Test
    void onlyASavedFixedBiomeSourceProducesAnExplicitFixedBiome() throws IOException {
        CompoundTag generation = generation("minecraft:overworld");
        CompoundTag biome = new CompoundTag();
        biome.putString("type", "minecraft:fixed");
        biome.putString("biome", "minecraft:forest");
        overworldGenerator(generation).put("biome_source", biome);
        write(generation);
        assertEquals("minecraft:forest", inspector().inspect(request(World.Environment.NORMAL)).fixedBiome().orElseThrow().toString());
        biome.putString("biome", "example:missing");
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
    }

    @Test
    void unknownNoiseAndGeneratorPlansRemainUnrepresentableAndRetainTypedRawNbt() throws IOException {
        CompoundTag generation = generation("example:custom_noise");
        write(generation);
        Snapshot custom = inspector().inspect(request(World.Environment.NORMAL));
        assertInstanceOf(Unrepresentable.class, custom.terrain());
        assertTrue(custom.selectedStemSnbt().contains("example:custom_noise"));
        overworldGenerator(generation).putString("type", "example:generator");
        write(generation);
        assertInstanceOf(Unrepresentable.class, inspector().inspect(request(World.Environment.NORMAL)).terrain());
        overworldGenerator(generation).putString("type", "minecraft:noise");
        overworldGenerator(generation).put("settings", new CompoundTag());
        write(generation);
        assertInstanceOf(Unrepresentable.class, inspector().inspect(request(World.Environment.NORMAL)).terrain());
    }

    @Test
    void invalidUuidsSeedsEnvironmentAndCanonicalPathFailWithoutWriting() throws IOException {
        write(generation("minecraft:overworld"));
        Map<Path, byte[]> before = files();
        Request base = request(World.Environment.NORMAL);
        assertThrows(IOException.class, () -> inspector().inspect(new Request(directory, WORLD_KEY, UUID.randomUUID(),
                base.format(), base.selection(), OptionalLong.empty())));
        assertThrows(IOException.class, () -> inspector().inspect(new Request(directory, WORLD_KEY, uuid,
                base.format(), base.selection(), OptionalLong.of(7))));
        assertThrows(IOException.class, () -> inspector().inspect(new Request(directory, NamespacedKey.fromString("rift:wrong"),
                uuid, base.format(), base.selection(), OptionalLong.empty())));
        unchanged(before);
        CompoundTag generation = generation("minecraft:overworld");
        generation.putInt("seed", 22);
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(base));
        generation.putLong("seed", 22);
        generation.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").putString("type", "minecraft:the_nether");
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(base));
    }

    @Test
    void verifiedPaper4903GenerationUpgradesInMemoryWithoutSourceWrites() throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 4903);
        root.put("data", generation("minecraft:large_biomes"));
        writeRoot(root);
        Map<Path, byte[]> before = files();
        if (version < 4903) {
            assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
            unchanged(before);
            return;
        }
        Snapshot snapshot = inspector().inspect(request(World.Environment.NORMAL));
        assertEquals(WorldType.LARGE_BIOMES, assertInstanceOf(VanillaNoise.class, snapshot.terrain()).worldType());
        assertEquals(4903, snapshot.sourceFiles().get(1).dataVersion());
        unchanged(before);
    }

    @Test
    void invalidBooleanAndMissingSelectedStemDoNotUseAnotherStem() throws IOException {
        CompoundTag generation = generation("minecraft:overworld");
        generation.putByte("generate_structures", (byte) 2);
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        generation.putBoolean("generate_structures", true);
        generation.getCompoundOrEmpty("dimensions").remove("minecraft:the_nether");
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NETHER)));
        assertEquals("minecraft:overworld", inspector().inspect(request(World.Environment.NORMAL)).selectedStem().toString());
    }

    @Test
    void futureVersionsMalformedMetadataAndMissingGenerationFailClosed() throws IOException {
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", version + 1);
        root.put("data", generation("minecraft:overworld"));
        writeRoot(root);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        write(generation("minecraft:overworld"));
        Path metadata = directory.resolve("data/paper/metadata.dat");
        Files.write(metadata, new byte[]{1, 2, 3});
        byte[] malformed = Files.readAllBytes(metadata);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        assertArrayEquals(malformed, Files.readAllBytes(metadata));
    }

    @Test
    void forgedLoadedReceiptAndNonPaperCaptureNeverTouchWorlds() throws IOException {
        write(generation("minecraft:overworld"));
        LoadedReceipt forged = mock(LoadedReceipt.class);
        assertThrows(IOException.class, () -> inspector().inspect(new Request(directory, WORLD_KEY, uuid,
                StorageFormat.PAPER_DIMENSION, new CapturedStem(forged), OptionalLong.empty())));
        verifyNoInteractions(forged);
        Server server = mock(Server.class);
        assertThrows(IOException.class, () -> new NativeSavedWorldGenerationImpl().capture(server, CaptureOptions.standard()));
        verifyNoInteractions(server);
    }

    @Test
    void capturedStemReceiptsAuthenticateOwningThreadWorldRegistrationAndContext() throws Exception {
        write(generation("minecraft:overworld"));
        CraftServer server = mock(CraftServer.class);
        DedicatedServer nativeServer = mock(DedicatedServer.class);
        CraftWorld world = mock(CraftWorld.class);
        ServerLevel level = mock(ServerLevel.class);
        when(server.getServer()).thenReturn(nativeServer);
        when(nativeServer.registryAccess()).thenReturn(registries);
        ReloadableServerRegistries.Holder resources = new ReloadableServerRegistries.Holder(registries);
        when(nativeServer.reloadableRegistries()).thenReturn(resources);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getBukkitVersion()).thenReturn("26.3-R0.1-SNAPSHOT");
        when(server.getWorld(uuid)).thenReturn(world);
        when(world.getHandle()).thenReturn(level);
        when(world.getKey()).thenReturn(WORLD_KEY);
        when(world.getUID()).thenReturn(uuid);
        when(world.getWorldPath()).thenReturn(directory);
        when(level.getServer()).thenReturn(nativeServer);
        when(level.getTypeKey()).thenReturn(LevelStem.NETHER);
        InspectionContext context = new NativeSavedWorldGenerationImpl.Inspector(
                new NativeSavedWorldGenerationImpl.ContextOptions(server, registries, CaptureOptions.standard().limits(), version,
                        new NativeSavedWorldGenerationImpl.RegistryEpoch(registries, resources, UUID.randomUUID())));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            LoadedReceipt receipt = context.capture(world);
            Request request = new Request(directory, WORLD_KEY, uuid, StorageFormat.PAPER_DIMENSION,
                    new CapturedStem(receipt), OptionalLong.empty());
            clearInvocations(server, world, level, nativeServer);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                assertEquals("minecraft:the_nether", executor.submit(() -> context.inspect(request)).get(10, TimeUnit.SECONDS).selectedStem().toString());
            }
            verifyNoInteractions(server, world, level, nativeServer);
            assertThrows(IOException.class, () -> inspector().inspect(request));
            Path primary = temporary.resolve("primary");
            Files.createDirectories(primary.resolve("data/paper"));
            Files.createDirectories(primary.resolve("data/minecraft"));
            Files.copy(directory.resolve("data/paper/metadata.dat"), primary.resolve("data/paper/metadata.dat"));
            Files.copy(generationFile(), primary.resolve("data/minecraft/world_gen_settings.dat"));
            NamespacedKey primaryKey = NamespacedKey.fromString("minecraft:overworld");
            when(world.getKey()).thenReturn(primaryKey);
            when(world.getWorldPath()).thenReturn(primary);
            when(level.getTypeKey()).thenReturn(LevelStem.OVERWORLD);
            LoadedReceipt primaryReceipt = context.capture(world);
            Request primaryRequest = new Request(primary, primaryKey, uuid, StorageFormat.PAPER_DIMENSION,
                    new CapturedStem(primaryReceipt), OptionalLong.empty());
            assertEquals("minecraft:overworld", context.inspect(primaryRequest).selectedStem().toString());
            assertEquals(context.contextId(), context.inspect(primaryRequest).contextId());
            assertThrows(IOException.class, () -> context.inspect(new Request(primary, primaryKey, uuid,
                    StorageFormat.PAPER_DIMENSION, new DeclaredEnvironment(World.Environment.NORMAL), OptionalLong.empty())));
            context.validateCurrent();
            when(nativeServer.registryAccess()).thenReturn(new RegistryAccess.ImmutableRegistryAccess(List.of()).freeze());
            assertThrows(IOException.class, context::validateCurrent);
            when(nativeServer.registryAccess()).thenReturn(registries);
            when(nativeServer.reloadableRegistries()).thenReturn(new ReloadableServerRegistries.Holder(registries));
            assertThrows(IOException.class, context::validateCurrent);
            assertThrows(IOException.class, () -> context.capture(world));
            when(nativeServer.reloadableRegistries()).thenReturn(resources);
            when(server.getWorld(uuid)).thenReturn(null);
            assertThrows(IOException.class, () -> context.capture(world));
            when(server.isPrimaryThread()).thenReturn(false);
            World untouched = mock(World.class);
            assertThrows(IOException.class, () -> context.capture(untouched));
            verifyNoInteractions(untouched);
        }
    }

    @Test
    void invalidFlatReferencesCannotSilentlyDefaultToVanillaSettings() throws IOException {
        CompoundTag generation = generation("minecraft:overworld");
        CompoundTag generator = overworldGenerator(generation);
        generator.putString("type", "minecraft:flat");
        generator.remove("biome_source");
        CompoundTag settings = new CompoundTag();
        settings.putString("biome", "example:missing");
        settings.put("layers", new ListTag());
        generator.put("settings", settings);
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        settings.putString("biome", "minecraft:plains");
        ListTag layers = new ListTag();
        CompoundTag layer = new CompoundTag();
        layer.putInt("height", 1);
        layer.putString("block", "example:missing");
        layers.add(layer);
        settings.put("layers", layers);
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        layer.putString("block", "minecraft:stone");
        settings.putInt("unknown", 1);
        write(generation);
        assertInstanceOf(Unrepresentable.class, inspector().inspect(request(World.Environment.NORMAL)).terrain());
    }

    @Test
    void verifiedLegacyFormatsUpgradeOnlyInMemoryWithExactUidAndSourceFingerprints() throws IOException {
        Path legacy = temporary.resolve("legacy");
        Files.createDirectories(legacy);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(legacy.resolve("uid.dat")))) {
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
        }
        for (int legacyVersion : new int[]{3465, 4189, 4671}) {
            CompoundTag root = new CompoundTag();
            CompoundTag data = new CompoundTag();
            data.putInt("DataVersion", legacyVersion);
            CompoundTag settings = generation("minecraft:amplified");
            settings.remove("generate_structures");
            settings.putBoolean("generate_features", true);
            data.put("WorldGenSettings", settings);
            root.put("Data", data);
            Path level = legacy.resolve("level.dat");
            NbtIo.writeCompressed(root, level);
            byte[] original = Files.readAllBytes(level);
            Snapshot snapshot = inspector().inspect(new Request(legacy, WORLD_KEY, uuid, StorageFormat.LEGACY_LEVEL_DATA,
                    new DeclaredEnvironment(World.Environment.NORMAL), OptionalLong.empty()));
            assertEquals(WorldType.AMPLIFIED, assertInstanceOf(VanillaNoise.class, snapshot.terrain()).worldType());
            assertTrue(snapshot.generateStructures());
            assertEquals(legacyVersion, snapshot.sourceFiles().get(1).dataVersion());
            assertArrayEquals(original, Files.readAllBytes(level));
            assertFalse(Files.exists(legacy.resolve("session.lock")));
            data.putInt("DataVersion", 1);
            NbtIo.writeCompressed(root, level);
            assertThrows(IOException.class, () -> inspector().inspect(new Request(legacy, WORLD_KEY, uuid,
                    StorageFormat.LEGACY_LEVEL_DATA, new DeclaredEnvironment(World.Environment.NORMAL), OptionalLong.empty())));
        }
    }

    @Test
    void compressedInflatedCollectionStringAndDepthBudgetsAreEnforcedBeforeNativeParsing() throws IOException {
        write(generation("minecraft:overworld"));
        Limits standard = CaptureOptions.standard().limits();
        for (Limits limits : new Limits[]{
                new Limits(10, standard.inflatedBytes(), standard.depth(), standard.collectionElements(), standard.stringBytes()),
                new Limits(standard.compressedBytes(), 20, standard.depth(), standard.collectionElements(), standard.stringBytes()),
                new Limits(standard.compressedBytes(), standard.inflatedBytes(), standard.depth(), 3, standard.stringBytes()),
                new Limits(standard.compressedBytes(), standard.inflatedBytes(), standard.depth(), standard.collectionElements(), 3)}) {
            assertThrows(IOException.class, () -> inspector(limits).inspect(request(World.Environment.NORMAL)));
        }
        CompoundTag generation = generation("minecraft:overworld");
        CompoundTag current = generation;
        for (int depth = 0; depth < 40; depth++) {
            CompoundTag nested = new CompoundTag();
            current.put("nested", nested);
            current = nested;
        }
        write(generation);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
    }

    @Test
    void symbolicLinksDuplicateKeysAndTrailingBytesAreRejected() throws IOException {
        write(generation("minecraft:overworld"));
        Path file = generationFile();
        Path original = temporary.resolve("outside.dat");
        Files.copy(file, original);
        byte[] before = Files.readAllBytes(original);
        Files.delete(file);
        Files.createSymbolicLink(file, original);
        assertThrows(IOException.class, () -> inspector().inspect(request(World.Environment.NORMAL)));
        assertArrayEquals(before, Files.readAllBytes(original));
        Files.delete(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            for (int value = 0; value < 2; value++) {
                output.writeByte(1);
                output.writeUTF("duplicate");
                output.writeByte(value);
            }
            output.writeByte(0);
        }
        Files.write(file, bytes.toByteArray());
        assertTrue(assertThrows(IOException.class, () -> SavedGenerationNbt.read(file, CaptureOptions.standard().limits())).getMessage().contains("duplicate"));
        bytes.reset();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(0);
            output.writeByte(1);
        }
        Files.write(file, bytes.toByteArray());
        assertTrue(assertThrows(IOException.class, () -> SavedGenerationNbt.read(file, CaptureOptions.standard().limits())).getMessage().contains("trailing"));
        bytes.reset();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeByte(10);
            output.writeUTF("");
            output.writeByte(9);
            output.writeUTF("list");
            output.writeByte(1);
            output.writeInt(-1);
            output.writeByte(0);
        }
        Files.write(file, bytes.toByteArray());
        assertTrue(assertThrows(IOException.class, () -> SavedGenerationNbt.read(file, CaptureOptions.standard().limits())).getMessage().contains("collection"));
    }

    private InspectionContext inspector() {
        return inspector(CaptureOptions.standard().limits());
    }

    private InspectionContext inspector(Limits limits) {
        return new NativeSavedWorldGenerationImpl.Inspector(new NativeSavedWorldGenerationImpl.ContextOptions(null, registries, limits, version,
                new NativeSavedWorldGenerationImpl.RegistryEpoch(registries, new ReloadableServerRegistries.Holder(registries), UUID.randomUUID())));
    }

    private Request request(World.Environment environment) {
        return new Request(directory, WORLD_KEY, uuid, StorageFormat.PAPER_DIMENSION,
                new DeclaredEnvironment(environment), OptionalLong.empty());
    }

    private CompoundTag generation(String noiseSettings) {
        CompoundTag generation = new CompoundTag();
        generation.putLong("seed", Long.MIN_VALUE + 22);
        generation.putBoolean("generate_structures", true);
        generation.putBoolean("bonus_chest", false);
        CompoundTag dimensions = new CompoundTag();
        dimensions.put("minecraft:overworld", noise("minecraft:overworld", noiseSettings, "minecraft:overworld"));
        dimensions.put("minecraft:the_nether", noise("minecraft:the_nether", "minecraft:nether", "minecraft:nether"));
        dimensions.put("minecraft:the_end", noise("minecraft:the_end", "minecraft:end", "minecraft:the_end"));
        generation.put("dimensions", dimensions);
        return generation;
    }

    private CompoundTag noise(String dimensionType, String settings, String biomePreset) {
        CompoundTag stem = new CompoundTag();
        stem.putString("type", dimensionType);
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:noise");
        generator.putString("settings", settings);
        CompoundTag biome = new CompoundTag();
        biome.putString("type", biomePreset.equals("minecraft:the_end") ? "minecraft:the_end" : "minecraft:multi_noise");
        if (!biomePreset.equals("minecraft:the_end")) {
            biome.putString("preset", biomePreset);
        }
        generator.put("biome_source", biome);
        stem.put("generator", generator);
        return stem;
    }

    private CompoundTag overworldGenerator(CompoundTag generation) {
        return generation.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").getCompoundOrEmpty("generator");
    }

    private void write(CompoundTag generation) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", version);
        root.put("data", generation);
        writeRoot(root);
    }

    private void writeRoot(CompoundTag root) throws IOException {
        Files.createDirectories(generationFile().getParent());
        NbtIo.writeCompressed(root, generationFile());
    }

    private Path generationFile() {
        return directory.resolve("data/minecraft/world_gen_settings.dat");
    }

    private Map<Path, byte[]> files() throws IOException {
        Map<Path, byte[]> files = new HashMap<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                files.put(path, Files.readAllBytes(path));
            }
        }
        return files;
    }

    private void unchanged(Map<Path, byte[]> original) throws IOException {
        assertEquals(original.keySet(), files().keySet());
        for (Map.Entry<Path, byte[]> entry : original.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        }
    }
}
