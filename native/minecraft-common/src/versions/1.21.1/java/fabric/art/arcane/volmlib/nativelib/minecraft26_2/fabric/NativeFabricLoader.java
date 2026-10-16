package art.arcane.volmlib.nativelib.minecraft26_2.fabric;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModdedServer;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedServerAccess;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.ModdedServerLevels;
import art.arcane.volmlib.nativelib.terrain.NativeWorld;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModMetadata;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModdedLoader;
import art.arcane.volmlib.nativelib.modded.NativeLoaderOptions;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback;
import net.fabricmc.fabric.api.event.registry.DynamicRegistryView;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.io.File;
import java.nio.file.Path;

public final class NativeFabricLoader implements NativeModdedLoader {
    private final NativeLoaderOptions options;
    private final String blockBreakPermission;
    private final Map<Biome, Biome> originalBiomes = Collections.synchronizedMap(new WeakHashMap<>());

    public NativeFabricLoader(NativeLoaderOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        blockBreakPermission = options.modId() + "." + options.blockBreakPermission();
    }

    @Override
    public String platformName() {
        return "fabric";
    }

    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(options.modId())
                .map((ModContainer container) -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public List<NativeModMetadata> mods() {
        Collection<ModContainer> containers = FabricLoader.getInstance().getAllMods();
        List<NativeModMetadata> mods = new ArrayList<>(containers.size());
        for (ModContainer container : containers) {
            mods.add(new NativeModMetadata(container.getMetadata().getId(), container.getMetadata().getName(),
                    container.getMetadata().getVersion().getFriendlyString()));
        }
        return List.copyOf(mods);
    }

    @Override
    public NativeModdedServer currentServer() {
        Object instance = FabricLoader.getInstance().getGameInstance();
        return instance instanceof MinecraftServer server ? NativeModdedServer.fromHandle(server) : null;
    }

    @Override
    public ModdedServerAccess serverAccess() {
        return new ModdedServerLevels(this::invalidateLevelCache);
    }

    private void invalidateLevelCache(MinecraftServer server) {
    }

    @Override
    public void fireDynamicLevelLoad(NativeWorld world) {
        ServerLevel level = (ServerLevel) world.nativeHandle();
        MinecraftServer server = level.getServer();
        ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level);
    }

    @Override
    public void fireDynamicLevelUnload(NativeWorld world) {
        ServerLevel level = (ServerLevel) world.nativeHandle();
        MinecraftServer server = level.getServer();
        ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level);
    }

    @Override
    public boolean clientEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public File modJar() {
        return FabricLoader.getInstance().getModContainer(options.modId())
                .flatMap((ModContainer container) -> container.getOrigin().getPaths().stream().findFirst())
                .map((Path p) -> p.toFile())
                .orElse(null);
    }

    @Override
    public boolean hasBlockBreakPermission(ServerPlayer player) {
        return Permissions.check(player, blockBreakPermission, 2);
    }

    @Override
    public boolean canBreakBlock(
            ServerLevel level,
            ServerPlayer player,
            BlockPos position,
            BlockState state
    ) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(position) : null;
        return options.breakProbe().test(() -> PlayerBlockBreakEvents.BEFORE.invoker()
                .beforeBlockBreak(level, player, position, state, blockEntity));
    }

    @Override
    public boolean checkSpawnPosition(Mob mob, ServerLevelAccessor level, MobSpawnType reason) {
        return mob.checkSpawnRules(level, reason) && mob.checkSpawnObstruction(level);
    }

    public void captureOriginalBiomes() {
        DynamicRegistrySetupCallback.EVENT.register((DynamicRegistryView registries) -> registries.registerEntryAdded(
                Registries.BIOME,
                (int rawId, ResourceLocation id, Biome biome) -> recordOriginalBiome(biome)));
    }

    void recordOriginalBiome(Biome biome) {
        BiomeSpecialEffects original = biome.getSpecialEffects();
        BiomeSpecialEffects.Builder effects = new BiomeSpecialEffects.Builder()
                .fogColor(original.getFogColor())
                .waterColor(original.getWaterColor())
                .waterFogColor(original.getWaterFogColor())
                .skyColor(original.getSkyColor())
                .grassColorModifier(original.getGrassColorModifier());
        original.getFoliageColorOverride().ifPresent(effects::foliageColorOverride);
        original.getGrassColorOverride().ifPresent(effects::grassColorOverride);
        original.getAmbientParticleSettings().ifPresent(effects::ambientParticle);
        original.getAmbientLoopSoundEvent().ifPresent(effects::ambientLoopSound);
        original.getAmbientMoodSettings().ifPresent(effects::ambientMoodSound);
        original.getAmbientAdditionsSettings().ifPresent(effects::ambientAdditionsSound);
        original.getBackgroundMusic().ifPresent(effects::backgroundMusic);
        BiomeGenerationSettings generation = biome.getGenerationSettings();
        originalBiomes.put(biome, new Biome(biome.climateSettings, effects.build(),
                new BiomeGenerationSettings(generation.carvers, generation.features()), biome.getMobSettings()));
    }

    @Override
    public Biome unmodifiedBiome(Biome biome) {
        Biome original = originalBiomes.get(biome);
        return original == null ? biome : original;
    }
}
