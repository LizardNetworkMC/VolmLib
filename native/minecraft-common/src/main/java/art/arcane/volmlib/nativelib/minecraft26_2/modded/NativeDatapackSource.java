package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NativeDatapackSource implements RepositorySource {
    private static final Set<String> PINNED_LAST = ConcurrentHashMap.newKeySet();

    private final Supplier<PackHandle> pack;
    private final Runnable loaded;

    public NativeDatapackSource(Supplier<PackHandle> pack, Runnable loaded) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    public static PackHandle read(Options options) {
        PackLocationInfo location = new PackLocationInfo(options.id(), Component.literal(options.title()),
                PackSource.BUILT_IN, Optional.empty());
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.TOP, true);
        PathPackResources.PathResourcesSupplier supplier = new PathPackResources.PathResourcesSupplier(options.directory());
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.SERVER_DATA, selection);
        if (pack == null) {
            throw new IllegalStateException("Datapack " + options.id() + " at " + options.directory()
                    + " produced no readable pack metadata");
        }
        return new PackHandle(pack);
    }

    public static Set<String> pinnedIds() {
        return Set.copyOf(PINNED_LAST);
    }

    public static <T> List<T> orderPinnedLast(List<T> selected, Function<T, String> id, Set<String> pinnedIds) {
        if (pinnedIds.isEmpty()) {
            return selected;
        }
        List<T> ordered = new ArrayList<>(selected.size());
        List<T> pinned = new ArrayList<>(pinnedIds.size());
        for (T entry : selected) {
            (pinnedIds.contains(id.apply(entry)) ? pinned : ordered).add(entry);
        }
        ordered.addAll(pinned);
        return ordered.equals(selected) ? selected : ordered;
    }

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        Pack loadedPack = pack.get().pack;
        PINNED_LAST.add(loadedPack.getId());
        consumer.accept(loadedPack);
        loaded.run();
    }

    public record Options(String id, String title, Path directory) {
        public Options {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(directory, "directory");
        }
    }

    public static final class PackHandle {
        private final Pack pack;

        private PackHandle(Pack pack) {
            this.pack = pack;
        }
    }
}
