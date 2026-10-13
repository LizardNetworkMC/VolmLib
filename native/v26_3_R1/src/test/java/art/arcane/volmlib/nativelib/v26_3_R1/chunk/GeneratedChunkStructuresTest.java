package art.arcane.volmlib.nativelib.v26_3_R1.chunk;

import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.GeneratedChunkStructures;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedChunkStructuresTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void unloadedChunksReturnEmptyMetadataWithoutLoadingThem() {
        CraftChunk chunk = mock(CraftChunk.class);
        CraftWorld world = mock(CraftWorld.class);
        ServerLevel level = mock(ServerLevel.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getCraftWorld()).thenReturn(world);
        when(world.getHandle()).thenReturn(level);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 0, 0)).thenReturn(true);
            GeneratedChunkStructures.Snapshot snapshot = new GeneratedChunkStructuresImpl().read(chunk);
            assertTrue(snapshot.starts().isEmpty());
            assertTrue(snapshot.references().isEmpty());
            verify(level).getChunkIfLoaded(0, 0);
        }
    }

    @Test
    void foreignOwnersCannotReadTheNativeChunk() {
        CraftChunk chunk = mock(CraftChunk.class);
        World world = mock(World.class);
        when(chunk.getWorld()).thenReturn(world);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertThrows(IllegalStateException.class, () -> new GeneratedChunkStructuresImpl().read(chunk));
            verify(chunk, never()).getCraftWorld();
        }
    }

    @Test
    void referencesAreCoordinatesOnlyAndNeverTraverseAnotherChunk() {
        CraftChunk chunk = mock(CraftChunk.class);
        CraftWorld world = mock(CraftWorld.class);
        ServerLevel level = mock(ServerLevel.class);
        LevelChunk loaded = mock(LevelChunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getCraftWorld()).thenReturn(world);
        when(world.getHandle()).thenReturn(level);
        when(level.getChunkIfLoaded(0, 0)).thenReturn(loaded);
        when(loaded.getAllStarts()).thenReturn(Map.of());
        long coordinates = ((long) -11 << 32) | (7L & 0xffffffffL);
        when(loaded.getAllReferences()).thenReturn(Map.of(mock(Structure.class),
                new LongOpenHashSet(new long[] { coordinates })));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(world, 0, 0)).thenReturn(true);
            GeneratedChunkStructures.Snapshot snapshot = new GeneratedChunkStructuresImpl().read(chunk);
            assertEquals(List.of(new ChunkPosition(7, -11)), snapshot.references());
            verify(level).getChunkIfLoaded(0, 0);
            verify(level, never()).getChunkIfLoaded(7, -11);
        }
    }
}
