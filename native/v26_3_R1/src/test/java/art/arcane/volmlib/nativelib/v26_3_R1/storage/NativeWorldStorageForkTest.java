package art.arcane.volmlib.nativelib.v26_3_R1.storage;

import art.arcane.volmlib.nativelib.storage.NativeWorldStorageFork;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeWorldStorageForkTest {
    @TempDir
    Path directory;

    @BeforeEach
    void resolveSystemTemporaryDirectory() throws IOException {
        directory = directory.toRealPath();
    }

    @Test
    void forkChangesOnlyTheUuidAndPreservesOtherSavedMetadata() throws IOException {
        UUID original = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        Path metadata = metadata(original);
        NativeWorldStorageFork fork = new NativeWorldStorageForkImpl();
        fork.fork(new NativeWorldStorageFork.Fork(directory, original, replacement));
        CompoundTag root = NbtIo.readCompressed(metadata, NbtAccounter.create(2L * 1024L * 1024L));
        CompoundTag data = root.getCompound("data").orElseThrow();
        assertEquals(replacement, PaperWorldMetadata.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow().uuid());
        assertEquals("retained", data.getString("additional-field").orElseThrow());
        assertEquals(100, root.getInt("DataVersion").orElseThrow());
        try (Stream<Path> files = Files.list(metadata.getParent())) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void wrongSourceIdentityDoesNotRewriteTheFile() throws IOException {
        UUID original = UUID.randomUUID();
        Path metadata = metadata(original);
        byte[] before = Files.readAllBytes(metadata);
        assertThrows(IOException.class, () -> new NativeWorldStorageForkImpl().fork(
                new NativeWorldStorageFork.Fork(directory, UUID.randomUUID(), UUID.randomUUID())));
        assertArrayEquals(before, Files.readAllBytes(metadata));
    }

    @Test
    void invalidAndSymbolicLinkMetadataNeverGetsOverwritten() throws IOException {
        UUID original = UUID.randomUUID();
        Path metadata = metadata(original);
        Files.write(metadata, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> new NativeWorldStorageForkImpl().fork(
                new NativeWorldStorageFork.Fork(directory, original, UUID.randomUUID())));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(metadata));
        Path outside = directory.resolve("outside.dat");
        Files.write(outside, new byte[]{4, 5, 6});
        Files.delete(metadata);
        Files.createSymbolicLink(metadata, outside);
        assertThrows(IOException.class, () -> new NativeWorldStorageForkImpl().fork(
                new NativeWorldStorageFork.Fork(directory, original, UUID.randomUUID())));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(outside));
    }

    private Path metadata(UUID uuid) throws IOException {
        Path metadata = directory.resolve("data/paper/metadata.dat");
        Files.createDirectories(metadata.getParent());
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 100);
        Tag encoded = PaperWorldMetadata.CODEC.encodeStart(NbtOps.INSTANCE, new PaperWorldMetadata(uuid)).getOrThrow();
        CompoundTag data = (CompoundTag) encoded;
        data.putString("additional-field", "retained");
        root.put("data", data);
        NbtIo.writeCompressed(root, metadata);
        return metadata;
    }
}
