package art.arcane.volmit.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAccessRulesTest {
    @TempDir
    Path temporary;

    @Test
    void preparesBundledRulesAndPreservesUnchangedInputs() throws Exception {
        Path fabric = NativeAccessRules.prepare("minecraft26_3", "fabric", temporary.toFile()).toPath();
        assertEquals("accessWidener v2 official", Files.readAllLines(fabric).get(0));
        FileTime timestamp = FileTime.fromMillis(1000L);
        Files.setLastModifiedTime(fabric, timestamp);
        assertEquals(fabric, NativeAccessRules.prepare("minecraft26_3", "fabric", temporary.toFile()).toPath());
        assertEquals(timestamp, Files.getLastModifiedTime(fabric));
        Files.writeString(fabric, "stale");
        NativeAccessRules.prepare("minecraft26_3", "fabric", temporary.toFile());
        assertEquals("accessWidener v2 official", Files.readAllLines(fabric).get(0));
    }

    @Test
    void packagesEachLoaderAccessTransformer() throws Exception {
        for (String loader : new String[]{"forge", "neoforge"}) {
            Path rules = NativeAccessRules.prepare("minecraft26_3", loader, temporary.resolve(loader).toFile()).toPath();
            assertTrue(Files.readString(rules).contains("public net.minecraft.server.MinecraftServer levels"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NativeAccessRules.prepare("minecraft26_3", "../unknown", temporary.toFile()));
    }

    @Test
    void preparesMinecraft1211RulesWithoutReplacingOtherTargets() throws Exception {
        Map<String, String> expected = Map.of(
                "fabric", "accessWidener v2 named\n",
                "forge", "public net.minecraft.server.level.ServerChunkCache getChunkFutureMainThread(",
                "neoforge", "public net.minecraft.server.level.ServerChunkCache getChunkFutureMainThread("
        );
        for (Map.Entry<String, String> loader : expected.entrySet()) {
            Path directory = temporary.resolve(loader.getKey());
            Path current = NativeAccessRules.prepare("minecraft26_3", loader.getKey(), directory.resolve("26.3").toFile()).toPath();
            String currentRules = Files.readString(current);
            Path older = NativeAccessRules.prepare("minecraft1_21_1", loader.getKey(), directory.resolve("1.21.1").toFile()).toPath();
            assertTrue(Files.readString(older).replace("\r\n", "\n").contains(loader.getValue()));
            assertEquals(currentRules, Files.readString(current));
            FileTime timestamp = FileTime.fromMillis(1000L);
            Files.setLastModifiedTime(older, timestamp);
            NativeAccessRules.prepare("minecraft1_21_1", loader.getKey(), older.getParent().toFile());
            assertEquals(timestamp, Files.getLastModifiedTime(older));
        }
    }

    @Test
    void preparesRulesForEachMinecraftOverlay() throws Exception {
        Path biome = NativeAccessRules.prepare("minecraft26_2", "fabric", temporary.resolve("26.2").toFile()).toPath();
        assertTrue(Files.readString(biome).contains("Lnet/minecraft/world/level/biome/MobSpawnSettings;)V"));
        for (String loader : new String[]{"fabric", "forge", "neoforge"}) {
            Path rules = NativeAccessRules.prepare("minecraft26_1_2", loader, temporary.resolve("26.1.2").resolve(loader).toFile()).toPath();
            assertTrue(Files.readString(rules).contains("goalSelector"));
        }
    }
}
