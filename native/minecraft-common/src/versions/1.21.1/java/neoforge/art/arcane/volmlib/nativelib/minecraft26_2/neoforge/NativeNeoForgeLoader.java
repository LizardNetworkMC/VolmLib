package art.arcane.volmlib.nativelib.minecraft26_2.neoforge;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModdedServer;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedServerAccess;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedServerLevels;
import art.arcane.volmlib.nativelib.terrain.NativeWorld;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModMetadata;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModdedLoader;
import art.arcane.volmlib.nativelib.modded.NativeLoaderOptions;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import net.neoforged.fml.ModContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.io.File;
import java.nio.file.Path;

public final class NativeNeoForgeLoader implements NativeModdedLoader {
    private final NativeLoaderOptions options;
    private final PermissionNode<Boolean> blockBreakPermission;

    public NativeNeoForgeLoader(NativeLoaderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        blockBreakPermission = new PermissionNode<>(
            options.modId(),
            options.blockBreakPermission(),
            PermissionTypes.BOOLEAN,
            (player, playerId, contexts) ->
                    player != null && player.hasPermissions(2)
    );
    }

    public PermissionNode<Boolean> blockBreakPermission() {
        return blockBreakPermission;
    }

    @Override
    public String platformName() {
        return "neoforge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }

    @Override
    public String modVersion() {
        return ModList.get().getModContainerById(options.modId())
                .map((ModContainer container) -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public List<NativeModMetadata> mods() {
        List<IModInfo> infos = ModList.get().getMods();
        List<NativeModMetadata> mods = new ArrayList<>(infos.size());
        for (IModInfo info : infos) {
            mods.add(new NativeModMetadata(info.getModId(), info.getDisplayName(), info.getVersion().toString()));
        }
        return List.copyOf(mods);
    }

    @Override
    public NativeModdedServer currentServer() {
        return NativeModdedServer.fromHandle(ServerLifecycleHooks.getCurrentServer());
    }

    @Override
    public ModdedServerAccess serverAccess() {
        return new ModdedServerLevels(this::invalidateLevelCache);
    }

    private void invalidateLevelCache(MinecraftServer server) {
        server.markWorldsDirty();
    }

    @Override
    public void fireDynamicLevelLoad(NativeWorld world) {
        ServerLevel level = (ServerLevel) world.nativeHandle();
        MinecraftServer server = level.getServer();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    @Override
    public void fireDynamicLevelUnload(NativeWorld world) {
        ServerLevel level = (ServerLevel) world.nativeHandle();
        MinecraftServer server = level.getServer();
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
    }

    @Override
    public boolean clientEnvironment() {
        return FMLEnvironment.dist.isClient();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public File modJar() {
        IModFileInfo info = ModList.get().getModFileById(options.modId());
        return info == null ? null : info.getFile().getFilePath().toFile();
    }

    @Override
    public boolean hasBlockBreakPermission(ServerPlayer player) {
        return PermissionAPI.getPermission(player, blockBreakPermission);
    }

    @Override
    public boolean canBreakBlock(
            ServerLevel level,
            ServerPlayer player,
            BlockPos position,
            BlockState state
    ) {
        return options.breakProbe().test(() -> {
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, position, state, player);
            NeoForge.EVENT_BUS.post(event);
            return !event.isCanceled();
        });
    }

    @Override
    public boolean checkSpawnPosition(Mob mob, ServerLevelAccessor level, MobSpawnType reason) {
        return EventHooks.checkSpawnPosition(mob, level, reason);
    }
    @Override
    public Biome unmodifiedBiome(Biome biome) {
        return biome;
    }
}
