package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import art.arcane.volmlib.nativelib.terrain.NativePregenRuntime;
import art.arcane.volmlib.nativelib.terrain.NativeWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.util.Comparator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;

public final class NativeModdedPregenRuntime implements NativePregenRuntime {
    private static final TicketType<ChunkPos> PREGEN_TICKET = TicketType.create("volmlib_iris_pregen",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final ChunkLoad SUCCESS = new ChunkLoad(true, null);
    private final ServerLevel level;
    private final MinecraftServer server;

    private NativeModdedPregenRuntime(ServerLevel level) {
        this.level = level;
        server = level.getServer();
    }

    public static NativePregenRuntime from(NativeWorld world) {
        return new NativeModdedPregenRuntime((ServerLevel) world.nativeHandle());
    }

    public String worldIdentity() {
        return level.dimension().location().toString();
    }

    public File worldFolder() {
        return DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).toFile();
    }

    public boolean isServerThread() {
        return server.isSameThread();
    }

    public boolean isRunning() {
        return !server.isStopped() && server.isRunning();
    }

    public void execute(Runnable action) {
        server.execute(action);
    }

    public void save() {
        level.save(null, false, false);
    }

    public CompletableFuture<ChunkLoad> loadChunk(int x, int z) {
        ChunkPos position = new ChunkPos(x, z);
        CompletableFuture<?> loaded = CompletableFuture.supplyAsync(() -> {
            level.getChunkSource().addRegionTicket(PREGEN_TICKET, position, 0, position);
            return level.getChunkSource().getChunkFutureMainThread(x, z, ChunkStatus.FULL, true);
        }, server).thenCompose(future -> future);
        return loaded.thenApply(NativeModdedPregenRuntime::result);
    }

    public void releaseChunk(int x, int z) {
        server.execute(() -> level.getChunkSource().removeRegionTicket(PREGEN_TICKET, new ChunkPos(x, z), 0, new ChunkPos(x, z)));
    }

    public String workerPoolDescription() {
        Executor executor = server.executor;
        if (executor == null) {
            return "unknown";
        }
        if (executor instanceof ThreadPoolExecutor pool) {
            return "ThreadPoolExecutor(core=" + pool.getCorePoolSize() + ",max=" + pool.getMaximumPoolSize() + ")";
        }
        if (executor instanceof ForkJoinPool pool) {
            return "ForkJoinPool(parallelism=" + pool.getParallelism() + ")";
        }
        return executor.getClass().getSimpleName();
    }

    public boolean supportsPauseWhenEmpty() {
        return false;
    }

    public int pauseWhenEmptySeconds() {
        return 0;
    }

    public void pauseWhenEmptySeconds(int seconds) {
    }

    public int playerCount() {
        return server.getPlayerCount();
    }

    private static ChunkLoad result(Object result) {
        return result instanceof ChunkResult<?> chunk && !chunk.isSuccess()
                ? new ChunkLoad(false, String.valueOf(chunk.getError())) : SUCCESS;
    }
}
