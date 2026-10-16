package art.arcane.volmlib.nativelib.minecraft26_2.fabric;

import art.arcane.volmlib.nativelib.client.ClientHudBinding;
import art.arcane.volmlib.nativelib.client.ClientKeyBinding;
import art.arcane.volmlib.nativelib.minecraft26_2.client.NativeClientAccess;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public final class NativeFabricClientHooks {
    private NativeFabricClientHooks() {
    }

    public static void install(ClientHudBinding binding, Runnable onJoin, Runnable onDisconnect) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin.run());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect.run());
        for (ClientKeyBinding key : binding.keys()) {
            KeyBindingHelper.registerKeyBinding(NativeClientAccess.keyHandle(key));
        }
        HudRenderCallback.EVENT.register((graphics, delta) -> NativeClientAccess.render(graphics, binding.render()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            binding.tick().run();
            binding.pollKeys().run();
        });
    }
}
