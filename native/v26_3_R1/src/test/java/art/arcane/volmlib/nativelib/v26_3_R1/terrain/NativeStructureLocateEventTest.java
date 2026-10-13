package art.arcane.volmlib.nativelib.v26_3_R1.terrain;

import art.arcane.volmlib.nativelib.terrain.NativeBukkitGeneratorContext;
import art.arcane.volmlib.nativelib.terrain.NativeGenerationRoute;
import art.arcane.volmlib.nativelib.terrain.structure.StructureOwnershipRecordView;
import art.arcane.volmlib.nativelib.terrain.structure.StructureStartPlan;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Lifecycle;
import io.papermc.paper.registry.PaperRegistryAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import io.papermc.paper.event.world.StructuresLocateEvent;
import io.papermc.paper.math.Position;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.generator.structure.CraftStructure;
import org.bukkit.plugin.PluginManager;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class NativeStructureLocateEventTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider generated = VanillaRegistries.createWorldLookup();
        MappedRegistry<Structure> structures = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        for (Holder.Reference<Structure> holder : generated.lookupOrThrow(Registries.STRUCTURE).listElements().toList()) {
            structures.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN);
        }
        structures.bindAllTagsToEmpty();
        PaperRegistryAccess.instance().registerRegistry(structures.freeze());
    }

    @Test
    public void cancelledLocateDoesNotOpenTheRuntimeOrConsumeAResult() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.handler = event -> {
                event.setResult(new StructuresLocateEvent.Result(Position.block(25, 63, 41), fixture.bukkitTarget));
                event.setCancelled(true);
            };

            assertNull(fixture.locate());

            verify(fixture.context, never()).openRoute(anyInt(), anyInt(), any());
            verifyNoInteractions(fixture.stage);
        }
    }

    @Test
    public void replacementResultReturnsWithoutSearchingOrReferencing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.handler = event -> event.setResult(
                    new StructuresLocateEvent.Result(Position.fine(-24.2, 63.8, 41.4), fixture.bukkitTarget));

            Pair<BlockPos, Holder<Structure>> result = fixture.locate();

            assertEquals(new BlockPos(-25, 63, 41), result.getFirst());
            assertSame(fixture.target, result.getSecond());
            verify(fixture.context, never()).openRoute(anyInt(), anyInt(), any());
            verifyNoInteractions(fixture.stage);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void changedInputsReachBothTheRuntimeRouteAndStructureSearch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Holder<Structure> replacement = Holder.direct(mock(Structure.class));
            CraftStructure bukkitReplacement = mock(CraftStructure.class);
            fixture.mapping.when(() -> CraftStructure.bukkitToMinecraftHolder(bukkitReplacement)).thenReturn(replacement);
            fixture.handler = event -> {
                assertEquals(50, event.getRadius());
                assertTrue(event.shouldFindUnexplored());
                event.getOrigin().setX(-31.4);
                event.setRadius(7);
                event.setFindUnexplored(false);
                event.setStructures(List.of(bukkitReplacement));
            };

            fixture.locate();

            verify(fixture.context).openRoute(0, 0, "bukkit_nms_structure_locate");
            ArgumentCaptor<HolderSet<Structure>> targets = ArgumentCaptor.forClass(HolderSet.class);
            verify(fixture.stage).findNearestStructure(
                    eq(fixture.level), targets.capture(), eq(BlockPos.ZERO),
                    eq(7), eq(false), eq(fixture.current), isNull());
            assertEquals(List.of(replacement), targets.getValue().stream().toList());
            verify(fixture.stage).filterReachableStructures(fixture.level, targets.getValue());
        }
    }

    @Test
    public void emptyTargetsDoNotFireAnEvent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.generator.findNearestMapStructure(
                    fixture.level, HolderSet.direct(List.of()), BlockPos.ZERO, 50, true);

            verify(fixture.plugins, never()).callEvent(any());
        }
    }

    @Test
    public void missingPaperEventIsAnExplicitlyUnsupportedCapability() {
        ClassLoader absent = new ClassLoader(NativeChunkGenerator.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("io.papermc.paper.event.world.StructuresLocateEvent")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        assertFalse(NativeChunkGenerator.supportsStructureLocateEvent(absent));
        assertTrue(NativeChunkGenerator.supportsStructureLocateEvent(NativeChunkGenerator.class.getClassLoader()));
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        private final CraftWorld world = mock(CraftWorld.class);
        private final PluginManager plugins = mock(PluginManager.class);
        private final Holder<Structure> target = Holder.direct(mock(Structure.class));
        private final CraftStructure bukkitTarget = mock(CraftStructure.class);
        private final Object current = new Object();
        private final NativeChunkGenerator<Object, Object, StructureStartPlan, Ownership, NativeGenerationRoute> generator;
        private final NativeBukkitGeneratorContext<Object, Object, StructureStartPlan, Ownership, NativeGenerationRoute> context;
        private final NativeBukkitStructureStage<Object, Object, StructureStartPlan, Ownership> stage;
        private final MockedStatic<Bukkit> bukkit;
        private final MockedStatic<CraftStructure> mapping;
        private Consumer<StructuresLocateEvent> handler = event -> { };

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            generator = mock(NativeChunkGenerator.class, CALLS_REAL_METHODS);
            context = mock(NativeBukkitGeneratorContext.class);
            stage = mock(NativeBukkitStructureStage.class);
            set("runtimeLevel", level);
            set("context", context);
            set("nativeStructures", stage);
            when(level.structureManager().shouldGenerateStructures()).thenReturn(true);
            when(context.generateStructures()).thenReturn(true);
            when(context.current()).thenReturn(current);
            when(level.getChunkSource().getGenerator()).thenReturn(generator);
            when(level.getWorld()).thenReturn(world);
            when(stage.filterReachableStructures(any(), any())).thenReturn(HolderSet.direct(List.of()));
            bukkit = mockStatic(Bukkit.class);
            mapping = mockStatic(CraftStructure.class);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            mapping.when(() -> CraftStructure.minecraftHolderToBukkit(target)).thenReturn(bukkitTarget);
            mapping.when(() -> CraftStructure.bukkitToMinecraftHolder(bukkitTarget)).thenReturn(target);
            doAnswer(invocation -> {
                handler.accept(invocation.getArgument(0));
                return null;
            }).when(plugins).callEvent(any(StructuresLocateEvent.class));
        }

        private void set(String name, Object value) throws Exception {
            Field field = NativeChunkGenerator.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(generator, value);
        }

        private Pair<BlockPos, Holder<Structure>> locate() {
            return generator.findNearestMapStructure(level, HolderSet.direct(target), BlockPos.ZERO, 50, true);
        }

        @Override
        public void close() {
            mapping.close();
            bukkit.close();
        }
    }

    private interface Ownership extends StructureOwnershipRecordView<Ownership> {
    }
}
