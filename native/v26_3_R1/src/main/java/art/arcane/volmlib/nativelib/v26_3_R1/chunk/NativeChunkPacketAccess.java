package art.arcane.volmlib.nativelib.v26_3_R1.chunk;

import art.arcane.volmlib.nativelib.chunk.ChunkPacketSnapshot;
import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.ChunkWorldContext;
import art.arcane.volmlib.nativelib.common.chunk.ReflectiveChunkPacketAccess;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Optional;

public final class NativeChunkPacketAccess extends ReflectiveChunkPacketAccess {
    private final Map<Registry<Biome>, String> registryFingerprints = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public boolean snapshotSupported() {
        try {
            return ClientboundLevelChunkWithLightPacket.STREAM_CODEC != null
                && CraftWorld.class.getMethod("getHandle").getReturnType() == ServerLevel.class;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    @Override
    public Optional<ChunkWorldContext> context(World world) {
        Objects.requireNonNull(world, "world");
        if (!snapshotSupported() || !(world instanceof CraftWorld craft)) {
            return Optional.empty();
        }
        ServerLevel level = craft.getHandle();
        return level.dimensionTypeRegistration().unwrapKey().map(type -> new ChunkWorldContext(
            level.dimension().identifier().toString(), type.identifier().toString(), BiomeManager.obfuscateSeed(level.getSeed()),
            level.isDebug(), level.isFlat(), level.getSeaLevel(), level.getMinY(), level.getHeight(),
            level.getGameTime(), level.getDefaultClockTime(), level.getRainLevel(1), level.getThunderLevel(1)));
    }

    @Override
    public Optional<ChunkPacketSnapshot> snapshot(World world, ChunkPosition position) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
        if (!snapshotSupported() || !(world instanceof CraftWorld craft)) {
            return Optional.empty();
        }
        ServerLevel level = craft.getHandle();
        LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
        if (chunk == null) {
            return Optional.empty();
        }
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        if (!snapshotSafe(level, packet)) {
            return Optional.empty();
        }
        ByteBuf storage = Unpooled.buffer(4096, ChunkPacketSnapshot.MAX_BYTES);
        try {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(storage, level.registryAccess());
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] payload = new byte[storage.readableBytes()];
            storage.readBytes(payload);
            return Optional.of(new ChunkPacketSnapshot(position, payload));
        } finally {
            storage.release();
        }
    }

    @Override
    public Optional<String> registryFingerprint(World world) {
        if (!snapshotSupported() || !(world instanceof CraftWorld craft)) {
            return Optional.empty();
        }
        Registry<Biome> biomes = craft.getHandle().registryAccess().lookupOrThrow(Registries.BIOME);
        String cached = registryFingerprints.get(biomes);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("biomes\n".getBytes(StandardCharsets.UTF_8));
            for (int id = 0; id < biomes.size(); id++) {
                Biome biome = biomes.byId(id);
                digest.update((Objects.requireNonNull(biomes.getKey(biome)).toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            digest.update("blockstates\n".getBytes(StandardCharsets.UTF_8));
            for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
                BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
                digest.update((Objects.requireNonNull(state).toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            registryFingerprints.put(biomes, fingerprint);
            return Optional.of(fingerprint);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
