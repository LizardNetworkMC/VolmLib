package art.arcane.volmlib.nativelib.v26_3_R1.storage;

import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration;
import com.google.gson.JsonObject;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class NativeSavedWorldGenerationImpl implements NativeSavedWorldGeneration {
    private static final Set<Integer> LEGACY_VERSIONS = Set.of(3465, 4189, 4671);
    private static final Set<String> FLAT_FIELDS = Set.of("layers", "biome", "features", "lakes", "structure_overrides");

    @Override
    public InspectionContext capture(Server server, CaptureOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        requireOwner(server);
        CraftServer craftServer = (CraftServer) server;
        RegistryAccess.Frozen registries = craftServer.getServer().registryAccess();
        return new Inspector(new ContextOptions(server, registries, options.limits(),
                SharedConstants.getCurrentVersion().dataVersion().version(),
                new RegistryEpoch(registries, craftServer.getServer().reloadableRegistries(), UUID.randomUUID())));
    }

    private static void requireOwner(Server server) throws IOException {
        if (!(server instanceof CraftServer) || Bukkit.getServer() != server || !server.isPrimaryThread()
                || !server.getBukkitVersion().startsWith("26.3")) {
            throw new IOException("Generation capture requires the matching Paper 26.3 server thread");
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, CraftServer.class.getClassLoader());
            throw new IOException("Saved generation capture is unavailable on Folia");
        } catch (ClassNotFoundException absent) {
            return;
        }
    }

    static final class Inspector implements InspectionContext {
        private final ContextOptions options;

        Inspector(ContextOptions options) {
            this.options = Objects.requireNonNull(options, "options");
        }

        @Override
        public UUID contextId() {
            return options.epoch().contextId();
        }

        @Override
        public void validateCurrent() throws IOException {
            requireOwner(options.server());
            CraftServer server = (CraftServer) options.server();
            if (server.getServer().registryAccess() != options.epoch().registries()
                    || server.getServer().reloadableRegistries() != options.epoch().resources()) {
                throw new IOException("Saved generation registry context changed after capture");
            }
        }

        @Override
        public Flat normalizeFlatSettings(String settingsJson) throws IOException {
            JsonObject json = SavedGenerationJson.read(Objects.requireNonNull(settingsJson, "settingsJson"), options.limits());
            if (!FLAT_FIELDS.containsAll(json.keySet())) {
                throw new IOException("Flat settings contain additional fields");
            }
            for (String option : List.of("features", "lakes")) {
                if (json.has(option) && (!json.get(option).isJsonPrimitive() || !json.get(option).getAsJsonPrimitive().isBoolean())) {
                    throw new IOException("Flat settings require JSON boolean " + option);
                }
            }
            if (!json.has("layers") || !json.get("layers").isJsonArray()) {
                throw new IOException("Flat settings require a JSON layers array");
            }
            try {
                CompoundTag settings = SavedGenerationJson.toNbt(json);
                Optional<String> unsupported = flatFields(settings);
                if (unsupported.isPresent()) {
                    throw new IOException(unsupported.get());
                }
                return canonicalFlat(settings);
            } catch (RuntimeException failure) {
                throw new IOException("Flat settings cannot be represented by the native codec", failure);
            }
        }

        @Override
        public LoadedReceipt capture(World world) throws IOException {
            validateCurrent();
            if (!(world instanceof CraftWorld craftWorld)
                    || craftWorld.getHandle().getServer() != ((CraftServer) options.server()).getServer()
                    || options.server().getWorld(world.getUID()) != world) {
                throw new IOException("Generation receipt requires a currently registered Paper world");
            }
            return new Receipt(this, world.getKey(), world.getUID(),
                    key(craftWorld.getHandle().getTypeKey().identifier().toString()),
                    craftWorld.getWorldPath().toAbsolutePath().normalize());
        }

        @Override
        public Snapshot inspect(Request request) throws IOException {
            Objects.requireNonNull(request, "request");
            Selected selected = select(request);
            List<SourceFile> fingerprints = new ArrayList<>();
            UUID uuid = identity(request, fingerprints);
            Path generationPath = request.directory().resolve(request.format() == StorageFormat.PAPER_DIMENSION
                    ? "data/minecraft/world_gen_settings.dat" : "level.dat");
            SavedGenerationNbt.Read source = SavedGenerationNbt.read(generationPath, options.limits());
            CompoundTag generation = generation(source, request.format(), fingerprints);
            long seed = exactLong(generation, "seed");
            if (request.expectedSeed().isPresent() && request.expectedSeed().getAsLong() != seed) {
                throw new IOException("Saved generation seed does not match its declared source identity");
            }
            boolean structures = exactBoolean(generation, "generate_structures");
            boolean bonus = exactBoolean(generation, "bonus_chest");
            CompoundTag dimensions = compound(generation, "dimensions");
            CompoundTag stem = compound(dimensions, selected.stem().toString());
            NamespacedKey dimensionType = key(text(stem, "type"));
            if (selected.dimensionType().isPresent() && !selected.dimensionType().get().equals(dimensionType)) {
                throw new IOException("Saved dimension type conflicts with its declared environment");
            }
            CompoundTag generator = compound(stem, "generator");
            NamespacedKey codec = key(text(generator, "type"));
            Optional<NamespacedKey> biome = fixedBiome(generator);
            Terrain terrain = terrain(stem, generator, codec, dimensionType, selected.stem(), biome);
            for (SourceFile file : fingerprints) {
                int limit = file.path().getFileName().toString().equals("uid.dat") ? 16 : options.limits().compressedBytes();
                if (!file.sha256().equals(SavedGenerationNbt.sha256(SavedGenerationNbt.bytes(file.path(), limit)))) {
                    throw new IOException("Saved generation source changed during inspection: " + file.path());
                }
            }
            return new Snapshot(contextId(), request.worldKey(), uuid, selected.stem(), selected.evidence(), seed, structures,
                    bonus, dimensionType, codec, terrain, biome, stem.toString(), fingerprints);
        }

        private Selected select(Request request) throws IOException {
            if (request.format() == StorageFormat.PAPER_DIMENSION && request.selection() instanceof DeclaredEnvironment
                    && !request.directory().endsWith(
                    Path.of("dimensions", request.worldKey().getNamespace()).resolve(request.worldKey().getKey()))) {
                throw new IOException("Canonical source path does not match its exact dimension key");
            }
            if (request.selection() instanceof DeclaredEnvironment declared) {
                String stem = switch (declared.environment()) {
                    case NORMAL -> "minecraft:overworld";
                    case NETHER -> "minecraft:the_nether";
                    case THE_END -> "minecraft:the_end";
                    case CUSTOM -> throw new IOException("Custom generation has no declared vanilla stem");
                };
                return new Selected(key(stem), SelectionEvidence.DECLARED_VANILLA_ENVIRONMENT, Optional.of(key(stem)));
            }
            LoadedReceipt candidate = ((CapturedStem) request.selection()).receipt();
            if (!(candidate instanceof Receipt receipt) || receipt.inspector() != this
                    || !receipt.worldKey().equals(request.worldKey()) || !receipt.worldUuid().equals(request.expectedUuid())
                    || !receipt.directory().equals(request.directory())) {
                throw new IOException("Generation stem receipt does not authenticate this source and inspection context");
            }
            return new Selected(receipt.stemKey(), SelectionEvidence.CAPTURED_LOADED_STEM, Optional.empty());
        }

        private UUID identity(Request request, List<SourceFile> fingerprints) throws IOException {
            UUID uuid;
            if (request.format() == StorageFormat.PAPER_DIMENSION) {
                SavedGenerationNbt.Read source = SavedGenerationNbt.read(request.directory().resolve("data/paper/metadata.dat"), options.limits());
                int version = version(source.root());
                currentFormat(version);
                uuid = PaperWorldMetadata.CODEC.parse(NbtOps.INSTANCE, compound(source.root(), "data")).getOrThrow(IOException::new).uuid();
                fingerprints.add(new SourceFile(source.path(), source.sha256(), version));
            } else {
                Path path = request.directory().resolve("uid.dat");
                byte[] bytes = SavedGenerationNbt.bytes(path, 16);
                if (bytes.length != 16) {
                    throw new IOException("Legacy source UUID must contain exactly sixteen bytes");
                }
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    uuid = new UUID(input.readLong(), input.readLong());
                }
                fingerprints.add(new SourceFile(path, SavedGenerationNbt.sha256(bytes), 0));
            }
            if (!uuid.equals(request.expectedUuid())) {
                throw new IOException("Saved generation UUID does not match its declared source identity");
            }
            return uuid;
        }

        private CompoundTag generation(SavedGenerationNbt.Read source, StorageFormat format,
                                       List<SourceFile> fingerprints) throws IOException {
            CompoundTag container = format == StorageFormat.PAPER_DIMENSION ? source.root() : compound(source.root(), "Data");
            int version = version(container);
            CompoundTag generation;
            if (format == StorageFormat.PAPER_DIMENSION) {
                currentFormat(version);
                generation = compound(source.root(), "data");
                if (version != options.currentVersion()) {
                    generation = DataFixTypes.SAVED_DATA_WORLD_GEN_SETTINGS.updateToCurrentVersion(
                            DataFixers.getDataFixer(), source.root().copy(), version).getCompound("data")
                            .orElseThrow(() -> new IOException("Saved generation data fix produced no data compound"));
                }
            } else {
                if (!LEGACY_VERSIONS.contains(version)) {
                    throw new IOException("Legacy generation DataVersion is not a verified supported format: " + version);
                }
                CompoundTag raw = compound(container, "WorldGenSettings");
                generation = DataFixTypes.WORLD_GEN_SETTINGS.updateToCurrentVersion(DataFixers.getDataFixer(), raw.copy(), version);
                boolean structures = exactBoolean(generation, "generate_features");
                generation.putBoolean("generate_structures", structures);
                generation.remove("generate_features");
            }
            fingerprints.add(new SourceFile(source.path(), source.sha256(), version));
            return generation;
        }

        private void currentFormat(int version) throws IOException {
            if (version > options.currentVersion() || (version != options.currentVersion() && version != 4903)) {
                throw new IOException("Paper generation DataVersion is not a verified supported format: " + version);
            }
        }

        private Optional<NamespacedKey> fixedBiome(CompoundTag generator) throws IOException {
            if (!(generator.get("biome_source") instanceof CompoundTag source)
                    || !text(source, "type").equals("minecraft:fixed")) {
                return Optional.empty();
            }
            NamespacedKey biome = key(text(source, "biome"));
            if (options.registries().lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME,
                    Identifier.parse(biome.toString()))).isEmpty()) {
                throw new IOException("Saved fixed biome is not registered: " + biome);
            }
            return Optional.of(biome);
        }

        private Terrain terrain(CompoundTag stem, CompoundTag generator, NamespacedKey codec,
                                NamespacedKey dimensionType, NamespacedKey stemKey,
                                Optional<NamespacedKey> fixedBiome) throws IOException {
            if (!Set.of("type", "generator").containsAll(stem.keySet())) {
                return new Unrepresentable("Selected stem contains additional generation fields");
            }
            if (!dimensionType.equals(stemKey) || !Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end").contains(stemKey.toString())) {
                return new Unrepresentable("A custom selected dimension stem requires a native generation plan");
            }
            if (codec.toString().equals("minecraft:flat")) {
                if (!Set.of("type", "settings").containsAll(generator.keySet()) || !stemKey.toString().equals("minecraft:overworld")) {
                    return new Unrepresentable("Flat generator has an unsupported dimension or additional fields");
                }
                CompoundTag settings = compound(generator, "settings");
                if (!FLAT_FIELDS.containsAll(settings.keySet())) {
                    return new Unrepresentable("Flat settings contain additional fields");
                }
                Optional<String> unsupported = flatFields(settings);
                if (unsupported.isPresent()) {
                    return new Unrepresentable(unsupported.get());
                }
                return canonicalFlat(settings);
            }
            if (!codec.toString().equals("minecraft:noise") || !Set.of("type", "settings", "biome_source").containsAll(generator.keySet())) {
                return new Unrepresentable("Generator codec or fields require a native generation plan");
            }
            if (!(generator.get("settings") instanceof StringTag)) {
                return new Unrepresentable("Inline noise settings require a native generation plan");
            }
            NamespacedKey settings = key(text(generator, "settings"));
            CompoundTag biomeSource = compound(generator, "biome_source");
            if (!naturalBiomeSource(biomeSource, stemKey, fixedBiome)) {
                return new Unrepresentable("Biome source requires a native generation plan");
            }
            WorldType type = switch (settings.toString()) {
                case "minecraft:overworld" -> stemKey.toString().equals("minecraft:overworld") ? WorldType.NORMAL : null;
                case "minecraft:amplified" -> stemKey.toString().equals("minecraft:overworld") ? WorldType.AMPLIFIED : null;
                case "minecraft:large_biomes" -> stemKey.toString().equals("minecraft:overworld") ? WorldType.LARGE_BIOMES : null;
                case "minecraft:nether" -> stemKey.toString().equals("minecraft:the_nether") ? WorldType.NORMAL : null;
                case "minecraft:end" -> stemKey.toString().equals("minecraft:the_end") ? WorldType.NORMAL : null;
                default -> null;
            };
            return type == null ? new Unrepresentable("Noise preset conflicts with its selected dimension or is custom")
                    : new VanillaNoise(type, settings);
        }

        private Flat canonicalFlat(CompoundTag settings) throws IOException {
            settings = settings.copy();
            if (!settings.contains("biome")) {
                settings.putString("biome", FlatLevelGeneratorSettings.getDefaultBiome(options.registries().lookupOrThrow(Registries.BIOME))
                        .unwrapKey().orElseThrow(() -> new IOException("Default flat biome has no registered key")).identifier().toString());
            }
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, options.registries());
            FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.CODEC.parse(ops, settings).getOrThrow(IOException::new);
            Tag encoded = FlatLevelGeneratorSettings.CODEC.encodeStart(ops, flat).getOrThrow(IOException::new);
            return new Flat(SavedGenerationJson.flatJson(encoded));
        }

        private Optional<String> flatFields(CompoundTag settings) throws IOException {
            for (String option : List.of("features", "lakes")) {
                if (settings.contains(option)) {
                    exactBoolean(settings, option);
                }
            }
            if (settings.contains("biome")) {
                if (!(settings.get("biome") instanceof StringTag)) {
                    return Optional.of("Inline flat biomes require a native generation plan");
                }
                NamespacedKey biome = key(text(settings, "biome"));
                if (options.registries().lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME,
                        Identifier.parse(biome.toString()))).isEmpty()) {
                    throw new IOException("Saved flat biome is not registered: " + biome);
                }
            }
            if (!(settings.get("layers") instanceof ListTag layers)) {
                throw new IOException("Saved flat generation requires a layers list");
            }
            for (Tag value : layers) {
                if (!(value instanceof CompoundTag layer)) {
                    throw new IOException("Saved flat generation requires compound layers");
                }
                if (!Set.of("height", "block").containsAll(layer.keySet())) {
                    return Optional.of("Flat layer contains additional fields");
                }
                if (layer.get("height") == null || layer.get("height").getId() != Tag.TAG_INT) {
                    throw new IOException("Saved flat layer height requires an exact integer");
                }
                NamespacedKey block = key(text(layer, "block"));
                if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(block.toString()))) {
                    throw new IOException("Saved flat layer block is not registered: " + block);
                }
            }
            return Optional.empty();
        }

        private boolean naturalBiomeSource(CompoundTag source, NamespacedKey stem, Optional<NamespacedKey> fixed) throws IOException {
            String codec = text(source, "type");
            if (fixed.isPresent()) {
                return Set.of("type", "biome").containsAll(source.keySet());
            }
            if (stem.toString().equals("minecraft:the_end")) {
                return codec.equals("minecraft:the_end") && source.size() == 1;
            }
            String preset = stem.toString().equals("minecraft:the_nether") ? "minecraft:nether" : "minecraft:overworld";
            return codec.equals("minecraft:multi_noise") && Set.of("type", "preset").containsAll(source.keySet())
                    && text(source, "preset").equals(preset);
        }
    }

    private static CompoundTag compound(CompoundTag parent, String name) throws IOException {
        return parent.getCompound(name).orElseThrow(() -> new IOException("Saved generation requires compound " + name));
    }

    private static String text(CompoundTag parent, String name) throws IOException {
        return parent.getString(name).orElseThrow(() -> new IOException("Saved generation requires string " + name));
    }

    private static long exactLong(CompoundTag parent, String name) throws IOException {
        Tag tag = parent.get(name);
        if (tag == null || tag.getId() != Tag.TAG_LONG) {
            throw new IOException("Saved generation requires an exact signed 64-bit " + name);
        }
        return parent.getLong(name).orElseThrow();
    }

    private static boolean exactBoolean(CompoundTag parent, String name) throws IOException {
        Tag tag = parent.get(name);
        if (tag == null || tag.getId() != Tag.TAG_BYTE || parent.getByte(name).orElseThrow() < 0
                || parent.getByte(name).orElseThrow() > 1) {
            throw new IOException("Saved generation requires boolean " + name);
        }
        return parent.getBoolean(name).orElseThrow();
    }

    private static int version(CompoundTag parent) throws IOException {
        Tag tag = parent.get("DataVersion");
        if (tag == null || tag.getId() != Tag.TAG_INT || parent.getInt("DataVersion").orElseThrow() < 0) {
            throw new IOException("Saved generation requires an exact DataVersion");
        }
        return parent.getInt("DataVersion").orElseThrow();
    }

    private static NamespacedKey key(String value) throws IOException {
        NamespacedKey key = NamespacedKey.fromString(value);
        if (key == null || !key.toString().equals(value)) {
            throw new IOException("Saved generation key must be an exact namespace:key: " + value);
        }
        return key;
    }

    record ContextOptions(Server server, HolderLookup.Provider registries, Limits limits, int currentVersion, RegistryEpoch epoch) {
        ContextOptions {
            Objects.requireNonNull(registries, "registries");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(epoch, "epoch");
        }
    }

    record RegistryEpoch(RegistryAccess.Frozen registries, ReloadableServerRegistries.Holder resources, UUID contextId) {
        RegistryEpoch {
            Objects.requireNonNull(registries, "registries");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(contextId, "contextId");
        }
    }

    private record Selected(NamespacedKey stem, SelectionEvidence evidence, Optional<NamespacedKey> dimensionType) {
    }

    private record Receipt(Inspector inspector, NamespacedKey worldKey, UUID worldUuid, NamespacedKey stemKey,
                           Path directory) implements LoadedReceipt {
    }
}
