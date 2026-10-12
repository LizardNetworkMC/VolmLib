package art.arcane.volmlib.nativelib.v26_3_R1.storage;

import art.arcane.volmlib.nativelib.storage.NativeWorldStorageFork;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class NativeWorldStorageForkImpl implements NativeWorldStorageFork {
    private static final long MAX_METADATA_BYTES = 2L * 1024L * 1024L;

    @Override
    public void flush(World world) throws IOException {
        Objects.requireNonNull(world, "world");
        if (!Bukkit.isPrimaryThread() || !(world instanceof CraftWorld craftWorld)) {
            throw new IOException("World flush requires the matching Paper server thread");
        }
        craftWorld.save(true);
    }

    @Override
    public void fork(Fork request) throws IOException {
        Objects.requireNonNull(request, "request");
        Path dimension = request.stagedDimension();
        Path metadata = dimension.resolve("data/paper/metadata.dat");
        for (Path cursor = metadata; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("World identity replacement refuses symbolic-link storage: " + cursor);
            }
        }
        if (!Files.isDirectory(dimension, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > MAX_METADATA_BYTES) {
            throw new IOException("Staged dimension has no valid bounded Paper metadata");
        }
        CompoundTag root = NbtIo.readCompressed(metadata, NbtAccounter.create(MAX_METADATA_BYTES));
        CompoundTag data = root.getCompound("data").orElseThrow(() -> new IOException("Paper metadata has no data compound"));
        PaperWorldMetadata original = PaperWorldMetadata.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow(IOException::new);
        if (!request.expectedUuid().equals(original.uuid())) {
            throw new IOException("Staged dimension UUID does not match its source identity");
        }
        original.set(request.replacementUuid());
        Tag replacement = PaperWorldMetadata.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow(IOException::new);
        if (!(replacement instanceof CompoundTag encoded)) {
            throw new IOException("Paper world metadata codec did not produce a compound");
        }
        for (String key : encoded.keySet()) {
            data.put(key, encoded.get(key));
        }
        Path temporary = Files.createTempFile(metadata.getParent(), ".world-identity-", ".dat");
        try {
            NbtIo.writeCompressed(root, temporary);
            Files.move(temporary, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
