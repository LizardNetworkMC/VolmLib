package art.arcane.volmlib.nativelib.storage;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.Test;

import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NativeSavedWorldGenerationTest {
    @Test
    public void sourceIdentitySelectionAndResourceLimitsRemainExplicit() {
        UUID uuid = UUID.randomUUID();
        Path source = Path.of("generation-source");
        NativeSavedWorldGeneration.Request request = new NativeSavedWorldGeneration.Request(source,
                NamespacedKey.fromString("rift:example"), uuid, NativeSavedWorldGeneration.StorageFormat.PAPER_DIMENSION,
                new NativeSavedWorldGeneration.DeclaredEnvironment(World.Environment.NORMAL), OptionalLong.of(42));
        assertEquals(source.toAbsolutePath().normalize(), request.directory());
        assertEquals(uuid, request.expectedUuid());
        assertEquals(42, request.expectedSeed().getAsLong());
        assertThrows(IllegalArgumentException.class, () -> new NativeSavedWorldGeneration.DeclaredEnvironment(World.Environment.CUSTOM));
        assertThrows(IllegalArgumentException.class, () -> new NativeSavedWorldGeneration.Limits(1, 1, 65, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeSavedWorldGeneration.Request(source,
                NamespacedKey.fromString("rift:../example"), uuid, request.format(), request.selection(), OptionalLong.empty()));
        assertThrows(NullPointerException.class, () -> new NativeSavedWorldGeneration.CapturedStem(null));
    }
}
