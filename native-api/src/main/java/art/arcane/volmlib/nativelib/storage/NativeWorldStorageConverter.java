package art.arcane.volmlib.nativelib.storage;

import art.arcane.volmlib.nativelib.NativeBinding;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

@NativeBinding("storage.NativeWorldStorageConverterImpl")
public interface NativeWorldStorageConverter {
    Path convert(Server server, Conversion request) throws IOException;

    record Conversion(Path stagedSource, Path stagingContainer, NamespacedKey targetKey,
                      World.Environment environment, UUID expectedUuid) {
        public Conversion {
            stagedSource = Objects.requireNonNull(stagedSource, "stagedSource").toAbsolutePath().normalize();
            stagingContainer = Objects.requireNonNull(stagingContainer, "stagingContainer").toAbsolutePath().normalize();
            Objects.requireNonNull(targetKey, "targetKey");
            for (String segment : targetKey.getKey().split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Target world key must have a confined dimension path");
                }
            }
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(expectedUuid, "expectedUuid");
        }
    }
}
