package art.arcane.volmlib.nativelib.minecraft26_2.modded.mixin;

import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativePersistedDimensions;
import net.minecraft.core.Registry;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsBakeMixin {
    @Inject(method = "bake", at = @At("HEAD"))
    private void volmlib$recordSavedDimensions(Registry<LevelStem> registry,
                                               CallbackInfoReturnable<WorldDimensions.Complete> callback) {
        NativePersistedDimensions.loaded((WorldDimensions) (Object) this);
    }
}
