package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import java.util.function.LongPredicate;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;

public final class NativeGenerationHooks {
    private NativeGenerationHooks() {
    }

    public static void install(ClassReloadingStrategy strategy, String generatorClassName) {
        ByteBuddy buddy = new ByteBuddy();
        for (Class<?> clazz : List.of(ChunkAccess.class, ProtoChunk.class)) {
            buddy.redefine(clazz)
                    .visit(Advice.to(ChunkAccessAdvice.class).on(ElementMatchers.isMethod().and(ElementMatchers.takesArguments(ShortList.class, int.class))))
                    .make()
                    .load(clazz.getClassLoader(), strategy);
        }
        buddy.redefine(WorldGenRegion.class)
                .visit(Advice.withCustomMapping().bind(GeneratorClassName.class, generatorClassName).to(WorldGenerationWriteAdvice.class).on(ElementMatchers.named("ensureCanWrite")
                        .and(ElementMatchers.takesArguments(BlockPos.class))))
                .make()
                .load(WorldGenRegion.class.getClassLoader(), strategy);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    private @interface GeneratorClassName {
    }

    private static class WorldGenerationWriteAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.This WorldGenRegion region, @Advice.Argument(0) BlockPos position,
                         @Advice.Return(readOnly = false) boolean allowed,
                         @GeneratorClassName String generatorClassName) {
            if (!allowed) {
                return;
            }
            Object generator = region.getLevel().getChunkSource().getGenerator();
            if (!generator.getClass().getName().equals(generatorClassName)
                    && generator.getClass().getClassLoader() == ChunkGenerator.class.getClassLoader()) {
                return;
            }
            int chunkX = position.getX() >> 4;
            int chunkZ = position.getZ() >> 4;
            if (!region.getChunk(chunkX, chunkZ).getPersistedStatus().isOrAfter(ChunkStatus.FULL)) {
                return;
            }
            for (int depth = 0; depth < 8 && generator != null
                    && !generator.getClass().getName().equals(generatorClassName); depth++) {
                Object next = null;
                for (Class<?> type = generator.getClass(); next == null && type != null
                        && type != ChunkGenerator.class; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (next == null && !Modifier.isStatic(field.getModifiers())
                                && ChunkGenerator.class.isAssignableFrom(field.getType())
                                && field.trySetAccessible()) {
                            try {
                                next = field.get(generator);
                            } catch (IllegalAccessException e) {
                                throw new IllegalStateException("Could not read chunk generator link "
                                        + type.getName() + "." + field.getName(), e);
                            }
                        }
                    }
                }
                generator = next;
            }
            if (generator instanceof LongPredicate predicate
                    && generator.getClass().getName().equals(generatorClassName)) {
                allowed = predicate.test(ChunkPos.pack(chunkX, chunkZ));
            }
        }
    }

    private static class ChunkAccessAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This ChunkAccess access, @Advice.Argument(1) int index) {
            return index >= access.getPostProcessing().length;
        }
    }
}
