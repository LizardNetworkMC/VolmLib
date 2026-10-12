package art.arcane.volmlib.nativelib.storage;

import art.arcane.volmlib.nativelib.NativeAdapters;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativeWorldStorageConverterTest {
    @Test
    public void unsupportedOrAbsentBackendCannotResolveAnotherVersion() {
        assertTrue(NativeAdapters.find(NativeWorldStorageConverter.class, "27.2").isEmpty());
        assertTrue(NativeAdapters.find(NativeWorldStorageConverter.class, "26.2").isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> NativeAdapters.require(NativeWorldStorageConverter.class, "27.2"));
    }

    @Test
    public void conversionRequiresAnExplicitFullIdentityAndNormalizesPaths() {
        Path container = Path.of("storage-conversion");
        NamespacedKey key = new NamespacedKey("iris", "island");
        UUID uuid = UUID.randomUUID();
        NativeWorldStorageConverter.Conversion request = new NativeWorldStorageConverter.Conversion(
                container.resolve("source"), container, key, World.Environment.NORMAL, uuid);
        assertEquals(container.toAbsolutePath().normalize(), request.stagingContainer());
        assertEquals(container.resolve("source").toAbsolutePath().normalize(), request.stagedSource());
        assertEquals(key, request.targetKey());
        assertEquals(uuid, request.expectedUuid());
        assertThrows(NullPointerException.class, () -> new NativeWorldStorageConverter.Conversion(
                container.resolve("source"), container, key, World.Environment.NORMAL, null));
    }

    @Test
    public void targetKeyCannotEscapeTheOutputRoot() {
        Path container = Path.of("storage-conversion");
        for (String key : new String[]{"../escape", "folder/../escape", "/absolute", "folder//world"}) {
            assertThrows(IllegalArgumentException.class, () -> new NativeWorldStorageConverter.Conversion(
                    container.resolve("source"), container, new NamespacedKey("rift", key),
                    World.Environment.NORMAL, UUID.randomUUID()));
        }
    }
}
