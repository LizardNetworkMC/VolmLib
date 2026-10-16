package art.arcane.volmlib.nativelib.minecraft26_2.modded.mixin;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativePersistedDimensions;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenSettings.class)
public abstract class WorldGenSettingsPersistenceMixin {
    @Inject(method = "encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/core/RegistryAccess;)Lcom/mojang/serialization/DataResult;",
            at = @At("HEAD"), cancellable = true)
    private static <T> void volmlib$persistSavedGenerators(DynamicOps<T> ops, WorldOptions options, RegistryAccess access,
                                                           CallbackInfoReturnable<DataResult<T>> callback) {
        WorldDimensions live = new WorldDimensions(access.registryOrThrow(Registries.LEVEL_STEM));
        WorldDimensions persisted = NativePersistedDimensions.persisted(live);
        if (persisted != live) {
            callback.setReturnValue(WorldGenSettings.encode(ops, options, persisted));
        }
    }
}
