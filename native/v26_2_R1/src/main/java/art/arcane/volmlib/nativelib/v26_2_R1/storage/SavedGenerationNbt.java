package art.arcane.volmlib.nativelib.v26_2_R1.storage;

import art.arcane.volmlib.nativelib.storage.NativeSavedWorldGeneration.Limits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

final class SavedGenerationNbt {
    private SavedGenerationNbt() {
    }

    static Read read(Path path, Limits limits) throws IOException {
        byte[] compressed = bytes(path, limits.compressedBytes());
        byte[] inflated;
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            inflated = input.readNBytes(limits.inflatedBytes() + 1);
        }
        if (inflated.length > limits.inflatedBytes()) {
            throw new IOException("Saved generation exceeds the inflated byte limit: " + path);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(inflated))) {
            if (input.readUnsignedByte() != 10) {
                throw new IOException("Saved generation NBT root must be a compound");
            }
            string(input, limits);
            scan(input, 10, 0, limits);
            if (input.read() != -1) {
                throw new IOException("Saved generation NBT has trailing bytes");
            }
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(inflated))) {
            CompoundTag root = NbtIo.read(input, new NbtAccounter(limits.inflatedBytes(), limits.depth()));
            return new Read(path, sha256(compressed), root);
        } catch (RuntimeException failure) {
            throw new IOException("Saved generation NBT is invalid: " + path, failure);
        }
    }

    static byte[] bytes(Path path, int limit) throws IOException {
        confined(path);
        try (SeekableByteChannel channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > limit) {
                throw new IOException("Saved generation exceeds the file byte limit: " + path);
            }
            byte[] bytes = Channels.newInputStream(channel).readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Saved generation changed beyond the file byte limit: " + path);
            }
            confined(path);
            return bytes;
        }
    }

    static void confined(Path path) throws IOException {
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Saved generation refuses symbolic-link storage: " + cursor);
            }
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Saved generation file is missing or nonregular: " + path);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void scan(DataInputStream input, int type, int depth, Limits limits) throws IOException {
        if (depth > limits.depth()) {
            throw new IOException("Saved generation exceeds the NBT depth limit");
        }
        switch (type) {
            case 1 -> input.skipNBytes(1);
            case 2 -> input.skipNBytes(2);
            case 3, 5 -> input.skipNBytes(4);
            case 4, 6 -> input.skipNBytes(8);
            case 7, 11, 12 -> {
                int count = count(input.readInt(), limits);
                input.skipNBytes((long) count * (type == 7 ? 1 : type == 11 ? 4 : 8));
            }
            case 8 -> string(input, limits);
            case 9 -> {
                int element = input.readUnsignedByte();
                int count = count(input.readInt(), limits);
                if (element > 12 || (element == 0 && count != 0)) {
                    throw new IOException("Saved generation has an invalid NBT list type");
                }
                for (int index = 0; index < count; index++) {
                    scan(input, element, depth + 1, limits);
                }
            }
            case 10 -> {
                int entries = 0;
                Set<String> names = new HashSet<>();
                for (int child = input.readUnsignedByte(); child != 0; child = input.readUnsignedByte()) {
                    count(++entries, limits);
                    if (!names.add(string(input, limits))) {
                        throw new IOException("Saved generation has a duplicate NBT compound key");
                    }
                    scan(input, child, depth + 1, limits);
                }
            }
            default -> throw new IOException("Saved generation has an invalid NBT tag type: " + type);
        }
    }

    private static int count(int count, Limits limits) throws IOException {
        if (count < 0 || count > limits.collectionElements()) {
            throw new IOException("Saved generation exceeds the NBT collection limit");
        }
        return count;
    }

    private static String string(DataInputStream input, Limits limits) throws IOException {
        input.mark(2);
        int length = input.readUnsignedShort();
        if (length > limits.stringBytes()) {
            throw new IOException("Saved generation exceeds the NBT string limit");
        }
        input.reset();
        return input.readUTF();
    }

    record Read(Path path, String sha256, CompoundTag root) {
    }
}
