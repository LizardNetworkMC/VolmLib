package art.arcane.volmlib.nativelib.minecraft26_2.modded.mixin;

import art.arcane.volmlib.nativelib.modded.NativeMixinFlags;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeModdedLifecycle;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class ServerLevelsReadyMixin {
    @Inject(method = "loadLevel", at = @At("RETURN"))
    private void volmlib$levelsReady(CallbackInfo callback) {
        NativeMixinFlags.markLevelsReady();
        NativeModdedLifecycle.levelsReady((MinecraftServer) (Object) this);
    }
}
