package art.arcane.volmlib.nativelib.minecraft26_2.modded.mixin;

import art.arcane.volmlib.nativelib.terrain.NativeTerrainReceiptStorage;
import art.arcane.volmlib.nativelib.minecraft26_2.modded.NativeTerrainReceipts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
public abstract class SerializableTerrainReceiptMixin {
    @Inject(method = "read", at = @At("RETURN"))
    private static void volmlib$restoreTerrain(ServerLevel level, PoiManager points, RegionStorageInfo storage,
                                               ChunkPos position, CompoundTag tag,
                                               CallbackInfoReturnable<ProtoChunk> callback) {
        ProtoChunk chunk = callback.getReturnValue();
        NativeTerrainReceipts.set(chunk, tag.contains(NativeTerrainReceiptStorage.NBT_KEY, Tag.TAG_BYTE_ARRAY)
                ? tag.getByteArray(NativeTerrainReceiptStorage.NBT_KEY) : null);
        NativeTerrainReceipts.setStructureActivation(chunk,
                tag.getLong(NativeTerrainReceiptStorage.STRUCTURE_ACTIVATION_KEY));
    }

    @Inject(method = "write", at = @At("RETURN"))
    private static void volmlib$writeTerrain(ServerLevel level, ChunkAccess chunk,
                                             CallbackInfoReturnable<CompoundTag> callback) {
        long activation = NativeTerrainReceipts.structureActivation(chunk);
        if (activation > 0) {
            callback.getReturnValue().putLong(NativeTerrainReceiptStorage.STRUCTURE_ACTIVATION_KEY, activation);
        }
        byte[] receipt = NativeTerrainReceipts.get(chunk);
        if (receipt != null) {
            callback.getReturnValue().putByteArray(NativeTerrainReceiptStorage.NBT_KEY, receipt);
        }
    }
}
