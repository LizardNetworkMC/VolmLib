package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Consumer;

public final class NativeModdedLifecycle {
    private static volatile Consumer<NativeModdedServer> levelsReady;

    private NativeModdedLifecycle() {
    }

    public static void bind(Consumer<NativeModdedServer> handler) {
        levelsReady = Objects.requireNonNull(handler, "handler");
    }

    public static void levelsReady(MinecraftServer server) {
        Consumer<NativeModdedServer> handler = levelsReady;
        if (handler != null) {
            handler.accept(NativeModdedServer.fromHandle(server));
        }
    }
}
