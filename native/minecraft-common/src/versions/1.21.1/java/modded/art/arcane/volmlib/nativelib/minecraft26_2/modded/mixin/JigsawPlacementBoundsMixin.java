package art.arcane.volmlib.nativelib.minecraft26_2.modded.mixin;

import art.arcane.volmlib.nativelib.minecraft26_2.terrain.NativeJigsawBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(JigsawPlacement.class)
public abstract class JigsawPlacementBoundsMixin {
    @Inject(method = "addPieces(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;Lnet/minecraft/core/Holder;Ljava/util/Optional;ILnet/minecraft/core/BlockPos;ZLjava/util/Optional;ILnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/pools/DimensionPadding;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)Ljava/util/Optional;", at = @At("RETURN"))
    private static void volmlib$captureBounds(Structure.GenerationContext context, Holder<StructureTemplatePool> pool,
                                               Optional<ResourceLocation> startName, int depth, BlockPos start,
                                               boolean expansion, Optional<Heightmap.Types> heightmap, int distance,
                                               PoolAliasLookup aliases, DimensionPadding padding, LiquidSettings liquids,
                                               CallbackInfoReturnable<Optional<Structure.GenerationStub>> callback) {
        NativeJigsawBounds.heightLimits(context.heightAccessor().getMinBuildHeight() + padding.bottom(),
                context.heightAccessor().getMaxBuildHeight() - padding.top());
        callback.getReturnValue().ifPresent(stub -> NativeJigsawBounds.center(stub.position().getY()));
    }

    @ModifyArgs(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;<init>(DDDDDD)V"))
    private static void volmlib$verticalBounds(Args arguments) {
        arguments.set(1, NativeJigsawBounds.minimum(arguments.get(1)));
        arguments.set(4, NativeJigsawBounds.maximum(arguments.get(4)));
    }
}
