package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

public final class NativeProtocolWorld {
    private final ServerLevel level;
    private final String id;

    NativeProtocolWorld(ServerLevel level) {
        this.level = level;
        this.id = level.dimension().identifier().toString();
    }

    public String id() {
        return id;
    }

    public long seed() {
        return level.getSeed();
    }

    public int minHeight() {
        return level.getMinY();
    }

    public int maxHeight() {
        return level.getMinY() + level.getHeight();
    }

    public <T> T generator(Class<T> type) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (type.isInstance(generator)) {
            return type.cast(generator);
        }
        if (generator instanceof NativeGeneratorHandle nativeGenerator && type.isInstance(nativeGenerator.owner())) {
            return type.cast(nativeGenerator.owner());
        }
        return null;
    }
}
