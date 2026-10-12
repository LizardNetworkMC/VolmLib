package art.arcane.volmlib.nativelib.storage;

import art.arcane.volmlib.nativelib.NativeBinding;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

@NativeBinding("storage.NativeWorldStorageForkImpl")
public interface NativeWorldStorageFork {
    void flush(World world) throws IOException;

    void fork(Fork request) throws IOException;

    record Fork(Path stagedDimension, UUID expectedUuid, UUID replacementUuid) {
        public Fork {
            stagedDimension = Objects.requireNonNull(stagedDimension, "stagedDimension").toAbsolutePath().normalize();
            Objects.requireNonNull(expectedUuid, "expectedUuid");
            Objects.requireNonNull(replacementUuid, "replacementUuid");
            if (expectedUuid.equals(replacementUuid)) {
                throw new IllegalArgumentException("A forked world requires a new UUID");
            }
        }
    }
}
