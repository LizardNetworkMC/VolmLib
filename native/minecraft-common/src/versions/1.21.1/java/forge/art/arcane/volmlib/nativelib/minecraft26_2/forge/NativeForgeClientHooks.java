package art.arcane.volmlib.nativelib.minecraft26_2.forge;

import art.arcane.volmlib.nativelib.client.ClientHudBinding;
import art.arcane.volmlib.nativelib.client.ClientKeyBinding;
import art.arcane.volmlib.nativelib.minecraft26_2.client.NativeClientAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

public final class NativeForgeClientHooks {
    private NativeForgeClientHooks() {
    }

    public static void install(FMLJavaModLoadingContext context, ClientHudBinding binding, Runnable onJoin, Runnable onDisconnect) {
        context.getModEventBus().addListener((AddGuiOverlayLayersEvent event) ->
                event.getLayeredDraw().add(ResourceLocation.parse(binding.id()), (graphics, delta) -> NativeClientAccess.render(graphics, binding.render())));
        context.getModEventBus().addListener((RegisterKeyMappingsEvent event) -> {
            for (ClientKeyBinding key : binding.keys()) {
                event.register(NativeClientAccess.keyHandle(key));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> onJoin.run());
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> onDisconnect.run());
        MinecraftForge.EVENT_BUS.addListener((InputEvent.Key event) -> binding.pollKeys().run());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent.Post event) -> {
            binding.pollKeys().run();
            binding.tick().run();
        });
    }
}
