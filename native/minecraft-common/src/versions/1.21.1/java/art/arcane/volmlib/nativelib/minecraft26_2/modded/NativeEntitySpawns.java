package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import art.arcane.volmlib.nativelib.entity.NativeEntityType;
import art.arcane.volmlib.nativelib.terrain.NativeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;

public final class NativeEntitySpawns {
    private NativeEntitySpawns() {
    }

    public static boolean command(NativeWorld world, NativeEntityType type, double x, double y, double z) {
        if (!(world.nativeHandle() instanceof ServerLevel level)) {
            return false;
        }
        return spawn((EntityType<?>) type.nativeHandle(), level, BlockPos.containing(x, y, z), MobSpawnType.COMMAND) != null;
    }

    public static Entity spawn(EntityType<?> type, ServerLevel level, BlockPos position, MobSpawnType reason) {
        if (level.getDifficulty() == Difficulty.PEACEFUL && !type.getCategory().isFriendly()
                && type != EntityType.PIGLIN && type != EntityType.HOGLIN
                && type != EntityType.SHULKER && type != EntityType.ENDER_DRAGON) {
            return null;
        }
        return type.spawn(level, position, reason);
    }
}
