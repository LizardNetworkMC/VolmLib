package art.arcane.volmlib.nativelib.common.terrain;

import net.minecraft.world.level.chunk.ChunkGenerator;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class NativeGeneratorChain {
    private static final int MAX_DEPTH = 8;
    private static final Field[] NO_LINKS = new Field[0];
    private static final ClassValue<Field[]> LINKS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            return linkFields(type);
        }
    };

    private NativeGeneratorChain() {
    }

    public static <T> T find(ChunkGenerator root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        return type.cast(walk(root, type::isInstance));
    }

    public static boolean contains(ChunkGenerator root, ChunkGenerator target) {
        return root == target || target != null && walk(root, generator -> generator == target) != null;
    }

    private static ChunkGenerator walk(ChunkGenerator root, Predicate<ChunkGenerator> match) {
        return walk(root, match, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static ChunkGenerator walk(ChunkGenerator generator, Predicate<ChunkGenerator> match,
                                       Set<ChunkGenerator> visited, int depth) {
        if (generator == null || depth > MAX_DEPTH || !visited.add(generator)) {
            return null;
        }
        if (match.test(generator)) {
            return generator;
        }
        for (Field link : LINKS.get(generator.getClass())) {
            ChunkGenerator found = walk(read(link, generator), match, visited, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ChunkGenerator read(Field link, ChunkGenerator owner) {
        try {
            return (ChunkGenerator) link.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read chunk generator link "
                    + link.getDeclaringClass().getName() + "." + link.getName(), e);
        }
    }

    private static Field[] linkFields(Class<?> type) {
        List<Field> links = new ArrayList<>(2);
        for (Class<?> current = type; current != null && current != ChunkGenerator.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && ChunkGenerator.class.isAssignableFrom(field.getType())
                        && field.trySetAccessible()) {
                    links.add(field);
                }
            }
        }
        return links.isEmpty() ? NO_LINKS : links.toArray(NO_LINKS);
    }
}
