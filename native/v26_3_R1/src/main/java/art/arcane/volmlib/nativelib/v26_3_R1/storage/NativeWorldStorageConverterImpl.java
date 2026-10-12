package art.arcane.volmlib.nativelib.v26_3_R1.storage;

import art.arcane.volmlib.nativelib.storage.NativeWorldStorageConverter;
import io.papermc.paper.world.migration.WorldFolderMigration;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class NativeWorldStorageConverterImpl implements NativeWorldStorageConverter {
    @Override
    public Path convert(Server server, Conversion request) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(request, "request");
        if (!(server instanceof CraftServer craftServer)) {
            throw new IOException("World storage conversion requires a matching Paper server");
        }
        Path container = request.stagingContainer();
        Path source = request.stagedSource();
        if (!container.equals(source.getParent()) || Files.isSymbolicLink(container)
                || Files.isSymbolicLink(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World storage conversion requires a confined, staged world copy");
        }
        String sourceName = source.getFileName().toString();
        if (!sourceName.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IOException("Invalid staged world directory name: " + sourceName);
        }
        ResourceKey<LevelStem> stem = stem(request.environment());
        String rootName = sourceName.equals("world-conversion-root") ? "world-conversion-output" : "world-conversion-root";
        Path root = container.resolve(rootName);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World storage conversion output already exists: " + root);
        }
        HolderLookup.Provider registries = craftServer.getServer().registryAccess();
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(request.targetKey().toString()));
        LevelStorageSource storageSource = LevelStorageSource.createDefault(container);
        try (LevelStorageSource.LevelStorageAccess storage = storageSource.createAccess(rootName)) {
            WorldFolderMigration.migrateApiWorld(storage, registries, sourceName, stem, dimension);
            Path output = storage.getDimensionPath(dimension).toAbsolutePath().normalize();
            if (!output.startsWith(root) || Files.isSymbolicLink(output)) {
                throw new IOException("World storage conversion produced an invalid destination");
            }
            PaperWorldMetadata metadata = LevelStorageSource.readExistingSavedData(storage, dimension,
                    registries, PaperWorldMetadata.TYPE).result().orElseThrow(() ->
                    new IOException("Converted world storage has no valid Paper metadata"));
            if (!request.expectedUuid().equals(metadata.uuid())) {
                throw new IOException("World storage conversion did not preserve the source UUID");
            }
            return output;
        } catch (RuntimeException failure) {
            throw new IOException("Paper world storage conversion failed", failure);
        }
    }

    private static ResourceKey<LevelStem> stem(World.Environment environment) throws IOException {
        return switch (environment) {
            case NORMAL -> LevelStem.OVERWORLD;
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            case CUSTOM -> throw new IOException("Custom worlds require a registered native dimension stem");
        };
    }
}
