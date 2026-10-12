package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import art.arcane.volmlib.nativelib.common.terrain.ReflectiveWorldRuntime;
import art.arcane.volmlib.nativelib.terrain.WorldRuntimeOptions;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;

import java.util.List;
import java.util.Optional;

public final class NativeWorldRuntimeImpl extends ReflectiveWorldRuntime {
    public NativeWorldRuntimeImpl() {
    }

    @Override
    protected Object readExistingWorldDataAndSettings(WorldRuntimeOptions options) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath(options.worldKey().getNamespace(), options.worldKey().getKey()));
        WorldGenSettings settings = LevelStorageSource.readExistingSavedData(server.storageSource, key,
                server.registryAccess(), WorldGenSettings.TYPE).getOrThrow(message ->
                new IllegalStateException("Cannot read saved generation settings for " + options.worldKey() + ": " + message));
        return new LevelDataAndDimensions.WorldDataAndGenSettings(server.getWorldData(), settings);
    }

    @Override
    protected Object createLevelStem(Object registryAccess, NamespacedKey dimensionTypeKey) {
        RegistryAccess access = (RegistryAccess) registryAccess;
        Identifier key = Identifier.fromNamespaceAndPath(dimensionTypeKey.getNamespace(), dimensionTypeKey.getKey());
        Holder.Reference<DimensionType> dimensionType = access.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, key));
        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(Optional.empty(),
                access.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID), List.of());
        settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
        settings.updateLayers();
        return new LevelStem(dimensionType, new FlatLevelSource(settings));
    }
}
