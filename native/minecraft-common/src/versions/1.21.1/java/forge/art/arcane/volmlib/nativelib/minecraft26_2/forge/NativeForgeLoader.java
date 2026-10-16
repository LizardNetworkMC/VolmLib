package art.arcane.volmlib.nativelib.minecraft26_2.forge;

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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import net.minecraftforge.fml.ModContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.io.File;
import java.nio.file.Path;

public final class NativeForgeLoader implements NativeModdedLoader {
    private final NativeLoaderOptions options;
    private final PermissionNode<Boolean> blockBreakPermission;

    public NativeForgeLoader(NativeLoaderOptions options) {
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
        return "forge";
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
        MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    @Override
    public void fireDynamicLevelUnload(NativeWorld world) {
        ServerLevel level = (ServerLevel) world.nativeHandle();
        MinecraftServer server = level.getServer();
        MinecraftForge.EVENT_BUS.post(new LevelEvent.Unload(level));
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
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
                    level,
                    position,
                    state,
                    player
            );
            MinecraftForge.EVENT_BUS.post(event);
            return !event.isCanceled();
        });
    }

    @Override
    public boolean checkSpawnPosition(Mob mob, ServerLevelAccessor level, MobSpawnType reason) {
        return ForgeEventFactory.checkSpawnPosition(mob, level, reason);
    }
    @Override
    public Biome unmodifiedBiome(Biome biome) {
        ModifiableBiomeInfo.BiomeInfo original = biome.modifiableBiomeInfo().getOriginalBiomeInfo();
        Biome.ClimateSettings climate = original.climateSettings();
        return new Biome.BiomeBuilder()
                .hasPrecipitation(climate.hasPrecipitation())
                .temperature(climate.temperature())
                .temperatureAdjustment(climate.temperatureModifier())
                .downfall(climate.downfall())
                .specialEffects(original.effects())
                .generationSettings(original.generationSettings())
                .mobSpawnSettings(original.mobSpawnSettings())
                .build();
    }
}
