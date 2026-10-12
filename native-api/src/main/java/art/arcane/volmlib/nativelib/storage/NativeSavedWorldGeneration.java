package art.arcane.volmlib.nativelib.storage;

import art.arcane.volmlib.nativelib.NativeBinding;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

@NativeBinding("storage.NativeSavedWorldGenerationImpl")
public interface NativeSavedWorldGeneration {
    InspectionContext capture(Server server, CaptureOptions options) throws IOException;

    interface InspectionContext {
        UUID contextId();

        void validateCurrent() throws IOException;

        Flat normalizeFlatSettings(String settingsJson) throws IOException;

        LoadedReceipt capture(World world) throws IOException;

        Snapshot inspect(Request request) throws IOException;
    }

    interface LoadedReceipt {
        NamespacedKey worldKey();

        UUID worldUuid();

        NamespacedKey stemKey();

        Path directory();
    }

    record CaptureOptions(Limits limits) {
        public CaptureOptions {
            Objects.requireNonNull(limits, "limits");
        }

        public static CaptureOptions standard() {
            return new CaptureOptions(new Limits(2 * 1024 * 1024, 8 * 1024 * 1024, 32, 16_384, 32_768));
        }
    }

    record Limits(int compressedBytes, int inflatedBytes, int depth, int collectionElements, int stringBytes) {
        public Limits {
            if (compressedBytes < 1 || compressedBytes > 16 * 1024 * 1024
                    || inflatedBytes < 1 || inflatedBytes > 64 * 1024 * 1024
                    || depth < 1 || depth > 64 || collectionElements < 1 || collectionElements > 262_144
                    || stringBytes < 1 || stringBytes > 65_535) {
                throw new IllegalArgumentException("Saved generation limits exceed the supported bounds");
            }
        }
    }

    enum StorageFormat {
        PAPER_DIMENSION,
        LEGACY_LEVEL_DATA
    }

    sealed interface Selection permits DeclaredEnvironment, CapturedStem {
    }

    record DeclaredEnvironment(World.Environment environment) implements Selection {
        public DeclaredEnvironment {
            Objects.requireNonNull(environment, "environment");
            if (environment == World.Environment.CUSTOM) {
                throw new IllegalArgumentException("Custom generation requires a captured native stem receipt");
            }
        }
    }

    record CapturedStem(LoadedReceipt receipt) implements Selection {
        public CapturedStem {
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    record Request(Path directory, NamespacedKey worldKey, UUID expectedUuid, StorageFormat format,
                   Selection selection, OptionalLong expectedSeed) {
        public Request {
            directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(expectedUuid, "expectedUuid");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(expectedSeed, "expectedSeed");
            for (String part : worldKey.getKey().split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw new IllegalArgumentException("Saved world key has an unconfined path");
                }
            }
        }
    }

    enum SelectionEvidence {
        DECLARED_VANILLA_ENVIRONMENT,
        CAPTURED_LOADED_STEM
    }

    sealed interface Terrain permits VanillaNoise, Flat, Unrepresentable {
    }

    record VanillaNoise(WorldType worldType, NamespacedKey noiseSettings) implements Terrain {
        public VanillaNoise {
            Objects.requireNonNull(worldType, "worldType");
            Objects.requireNonNull(noiseSettings, "noiseSettings");
            if (worldType != WorldType.NORMAL && worldType != WorldType.AMPLIFIED && worldType != WorldType.LARGE_BIOMES) {
                throw new IllegalArgumentException("Noise terrain requires a supported vanilla type");
            }
        }
    }

    record Flat(String settingsJson) implements Terrain {
        public Flat {
            if (Objects.requireNonNull(settingsJson, "settingsJson").isBlank()) {
                throw new IllegalArgumentException("Flat settings must be explicit");
            }
        }
    }

    record Unrepresentable(String reason) implements Terrain {
        public Unrepresentable {
            if (Objects.requireNonNull(reason, "reason").isBlank()) {
                throw new IllegalArgumentException("Unsupported terrain requires a reason");
            }
        }
    }

    record SourceFile(Path path, String sha256, int dataVersion) {
        public SourceFile {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (!Objects.requireNonNull(sha256, "sha256").matches("[a-f0-9]{64}") || dataVersion < 0) {
                throw new IllegalArgumentException("Saved generation requires an exact source fingerprint and version");
            }
        }
    }

    record Snapshot(UUID contextId, NamespacedKey worldKey, UUID worldUuid, NamespacedKey selectedStem,
                    SelectionEvidence selectionEvidence, long seed, boolean generateStructures,
                    boolean bonusChest, NamespacedKey dimensionType, NamespacedKey generatorCodec,
                    Terrain terrain, Optional<NamespacedKey> fixedBiome, String selectedStemSnbt,
                    List<SourceFile> sourceFiles) {
        public Snapshot {
            Objects.requireNonNull(contextId, "contextId");
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(worldUuid, "worldUuid");
            Objects.requireNonNull(selectedStem, "selectedStem");
            Objects.requireNonNull(selectionEvidence, "selectionEvidence");
            Objects.requireNonNull(dimensionType, "dimensionType");
            Objects.requireNonNull(generatorCodec, "generatorCodec");
            Objects.requireNonNull(terrain, "terrain");
            Objects.requireNonNull(fixedBiome, "fixedBiome");
            if (Objects.requireNonNull(selectedStemSnbt, "selectedStemSnbt").isBlank()) {
                throw new IllegalArgumentException("Saved generation requires the selected serialized stem");
            }
            sourceFiles = List.copyOf(sourceFiles);
            if (sourceFiles.isEmpty()) {
                throw new IllegalArgumentException("Saved generation requires source fingerprints");
            }
        }
    }
}
