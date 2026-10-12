package art.arcane.volmlib.nativelib.storage;

import org.junit.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NativeWorldStorageForkTest {
    @Test
    public void forkRequiresDistinctExplicitUuidsAndNormalizesTheStagingPath() {
        UUID source = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        Path stage = Path.of("world-copy");
        NativeWorldStorageFork.Fork request = new NativeWorldStorageFork.Fork(stage, source, replacement);
        assertEquals(stage.toAbsolutePath().normalize(), request.stagedDimension());
        assertEquals(source, request.expectedUuid());
        assertEquals(replacement, request.replacementUuid());
        assertThrows(IllegalArgumentException.class, () -> new NativeWorldStorageFork.Fork(stage, source, source));
        assertThrows(NullPointerException.class, () -> new NativeWorldStorageFork.Fork(stage, null, replacement));
        assertThrows(NullPointerException.class, () -> new NativeWorldStorageFork.Fork(stage, source, null));
    }
}
