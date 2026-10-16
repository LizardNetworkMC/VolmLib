package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.lang.reflect.Method;
import java.util.function.Supplier;

public enum NativeMixinTarget {
    SERVER(() -> MinecraftServer.class),
    ENTITY(() -> Entity.class),
    REGISTRY_PACKET(() -> ClientboundRegistryDataPacket.class),
    LIVING_ENTITY(() -> LivingEntity.class),
    MOB(() -> Mob.class),
    JIGSAW_PLACEMENT(() -> JigsawPlacement.class),
    STRUCTURE_PALETTE(() -> StructureTemplate.Palette.class),
    WORLD_OPEN_FLOWS(() -> WorldOpenFlows.class),
    WORLD_TYPE_ENTRY(() -> WorldCreationUiState.WorldTypeEntry.class),
    PACK_REPOSITORY(() -> PackRepository.class);

    private final Supplier<Class<?>> target;

    NativeMixinTarget(Supplier<Class<?>> target) {
        this.target = target;
    }

    public String className() {
        return target.get().getName();
    }

    public boolean hasInjectedHandler(String handlerMethod) {
        for (Method method : target.get().getDeclaredMethods()) {
            String name = method.getName();
            if (name.equals(handlerMethod) || name.endsWith('$' + handlerMethod)) {
                return true;
            }
        }
        return false;
    }
}
