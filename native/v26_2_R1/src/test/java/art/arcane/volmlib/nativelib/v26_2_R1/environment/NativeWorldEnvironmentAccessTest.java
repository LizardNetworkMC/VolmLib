package art.arcane.volmlib.nativelib.v26_2_R1.environment;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.NativeVersion;
import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.volmlib.nativelib.environment.WorldEnvironmentAccess;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftWorld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeWorldEnvironmentAccessTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void leafBuild123ResolvesTheEnvironmentCapability() {
        assertTrue(NativeAdapters.available(WorldEnvironmentAccess.class, NativeVersion.V26_2_R1));
        assertInstanceOf(NativeWorldEnvironmentAccess.class,
                NativeAdapters.require(WorldEnvironmentAccess.class, "26.2.build.123-alpha"));
    }

    @Test
    void sampleUsesRequestedPositionAndUnpacksNativeColors() {
        CraftWorld world = mock(CraftWorld.class);
        ServerLevel level = mock(ServerLevel.class);
        DimensionType dimension = mock(DimensionType.class);
        EnvironmentAttributeSystem attributes = mock(EnvironmentAttributeSystem.class);
        Vec3 eye = new Vec3(23.5, 91.25, -45.75);
        when(world.getHandle()).thenReturn(level);
        when(level.environmentAttributes()).thenReturn(attributes);
        when(level.getFluidState(any(BlockPos.class))).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.dimensionType()).thenReturn(dimension);
        when(level.getGameTime()).thenReturn(1234L);
        when(level.getRainLevel(1.0F)).thenReturn(0.25F);
        when(level.getThunderLevel(1.0F)).thenReturn(0.5F);
        when(dimension.skybox()).thenReturn(DimensionType.Skybox.OVERWORLD);
        when(dimension.cardinalLightType()).thenReturn(CardinalLighting.Type.DEFAULT);
        when(dimension.minY()).thenReturn(-64);
        when(dimension.height()).thenReturn(384);
        when(dimension.hasSkyLight()).thenReturn(true);
        when(attributes.getValue(any(), any(Vec3.class))).thenAnswer(invocation -> {
            assertEquals(eye, invocation.getArgument(1));
            EnvironmentAttribute<?> attribute = invocation.getArgument(0);
            return attribute.defaultValue();
        });
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_COLOR), eq(eye))).thenReturn(0x336699);
        when(attributes.getValue(eq(EnvironmentAttributes.SUNRISE_SUNSET_COLOR), eq(eye))).thenReturn(0x80402010);
        when(attributes.getValue(eq(EnvironmentAttributes.CLOUD_COLOR), eq(eye))).thenReturn(0x40FFFFFF);
        when(attributes.getValue(eq(EnvironmentAttributes.FOG_COLOR), eq(eye))).thenReturn(0x112233);
        when(attributes.getValue(eq(EnvironmentAttributes.WATER_FOG_COLOR), eq(eye))).thenReturn(0x224466);
        when(attributes.getValue(eq(EnvironmentAttributes.BLOCK_LIGHT_TINT), eq(eye))).thenReturn(0xFF8000);
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_LIGHT_COLOR), eq(eye))).thenReturn(0x00FFFF);
        when(attributes.getValue(eq(EnvironmentAttributes.AMBIENT_LIGHT_COLOR), eq(eye))).thenReturn(0x010203);
        when(attributes.getValue(eq(EnvironmentAttributes.SUN_ANGLE), eq(eye))).thenReturn(90.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.MOON_ANGLE), eq(eye))).thenReturn(180.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.STAR_ANGLE), eq(eye))).thenReturn(270.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.STAR_BRIGHTNESS), eq(eye))).thenReturn(0.75F);
        when(attributes.getValue(eq(EnvironmentAttributes.MOON_PHASE), eq(eye))).thenReturn(MoonPhase.values()[3]);
        when(attributes.getValue(eq(EnvironmentAttributes.FOG_START_DISTANCE), eq(eye))).thenReturn(8.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.FOG_END_DISTANCE), eq(eye))).thenReturn(96.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_FOG_END_DISTANCE), eq(eye))).thenReturn(192.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE), eq(eye))).thenReturn(256.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.WATER_FOG_START_DISTANCE), eq(eye))).thenReturn(2.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.WATER_FOG_END_DISTANCE), eq(eye))).thenReturn(24.0F);
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_LIGHT_FACTOR), eq(eye))).thenReturn(0.8F);
        when(attributes.getValue(eq(EnvironmentAttributes.CLOUD_HEIGHT), eq(eye))).thenReturn(220.0F);

        WorldEnvironmentAccess access = NativeAdapters.require(WorldEnvironmentAccess.class, "26.2.build.123-alpha");
        WorldEnvironment result = access.sample(world, new WorldEnvironmentAccess.Position(eye.x, eye.y, eye.z));

        assertEquals(new WorldEnvironment.Sky(WorldEnvironment.Skybox.OVERWORLD, 90.0F, 180.0F, 270.0F, 0.75F,
                new WorldEnvironment.ColorAlpha(64 / 255.0F, 32 / 255.0F, 16 / 255.0F, 128 / 255.0F),
                new WorldEnvironment.Color(0.2F, 0.4F, 0.6F), 3, 0.25F, 0.5F), result.sky());
        assertEquals(new WorldEnvironment.Fog(new WorldEnvironment.Color(17 / 255.0F, 34 / 255.0F, 51 / 255.0F),
                8.0F, 96.0F, 192.0F, 256.0F, new WorldEnvironment.Color(34 / 255.0F, 68 / 255.0F, 102 / 255.0F),
                2.0F, 24.0F), result.fog());
        assertEquals(new WorldEnvironment.Lighting(new WorldEnvironment.Color(1.0F, 128 / 255.0F, 0.0F), 0.8F,
                new WorldEnvironment.Color(0.0F, 1.0F, 1.0F),
                new WorldEnvironment.Color(1 / 255.0F, 2 / 255.0F, 3 / 255.0F)), result.lighting());
        assertEquals(new WorldEnvironment.Clouds(new WorldEnvironment.ColorAlpha(1.0F, 1.0F, 1.0F, 64 / 255.0F),
                220.0F), result.clouds());
        assertEquals(1234L, result.gameTime());
        assertTrue(result.dimension().hasSkyLight());
        assertEquals(63.0, result.dimension().horizonHeight());
        assertEquals(WorldEnvironment.EyeMedium.NONE, result.eyeMedium());
        when(level.isFlat()).thenReturn(true);
        assertEquals(-64.0, access.sample(world, new WorldEnvironmentAccess.Position(eye.x, eye.y, eye.z))
                .dimension().horizonHeight());
    }

    @Test
    void samplingFailurePropagatesToTheCaller() {
        CraftWorld world = mock(CraftWorld.class);
        IllegalStateException failure = new IllegalStateException("Destination world unavailable");
        when(world.getHandle()).thenThrow(failure);
        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> new NativeWorldEnvironmentAccess().sample(world, new WorldEnvironmentAccess.Position(0, 64, 0))));
    }

    @Test
    void samplesTrueEyeMediumAndDimensionSettings() {
        CraftWorld world = mock(CraftWorld.class);
        ServerLevel level = mock(ServerLevel.class);
        DimensionType dimension = mock(DimensionType.class);
        EnvironmentAttributeSystem attributes = mock(EnvironmentAttributeSystem.class);
        FluidState fluid = mock(FluidState.class);
        when(world.getHandle()).thenReturn(level);
        when(level.dimensionType()).thenReturn(dimension);
        when(level.environmentAttributes()).thenReturn(attributes);
        when(dimension.skybox()).thenReturn(DimensionType.Skybox.END);
        when(dimension.cardinalLightType()).thenReturn(CardinalLighting.Type.NETHER);
        when(dimension.minY()).thenReturn(-32);
        when(dimension.hasFixedTime()).thenReturn(true);
        when(dimension.height()).thenReturn(384);
        when(dimension.logicalHeight()).thenReturn(256);
        when(dimension.hasCeiling()).thenReturn(true);
        when(dimension.hasEndFlashes()).thenReturn(true);
        when(dimension.ambientLight()).thenReturn(0.1F);
        when(level.getFluidState(any(BlockPos.class))).thenReturn(fluid);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.AIR.defaultBlockState());
        when(attributes.getValue(any(), any(Vec3.class))).thenAnswer(call -> {
            EnvironmentAttribute<?> attribute = call.getArgument(0);
            return attribute.defaultValue();
        });
        when(fluid.is(FluidTags.WATER)).thenReturn(true);
        when(fluid.getHeight(eq(level), any(BlockPos.class))).thenReturn(0.25F);
        NativeWorldEnvironmentAccess access = new NativeWorldEnvironmentAccess();
        assertEquals(WorldEnvironment.EyeMedium.WATER, sample(access, world, 64.249).eyeMedium());
        assertEquals(WorldEnvironment.EyeMedium.NONE, sample(access, world, 64.25).eyeMedium());
        when(fluid.is(FluidTags.WATER)).thenReturn(false);
        when(fluid.is(FluidTags.LAVA)).thenReturn(true);
        when(fluid.getHeight(eq(level), any(BlockPos.class))).thenReturn(0.875F);
        assertEquals(WorldEnvironment.EyeMedium.LAVA, sample(access, world, 64.874).eyeMedium());
        assertEquals(WorldEnvironment.EyeMedium.NONE, sample(access, world, 64.875).eyeMedium());
        when(fluid.is(FluidTags.LAVA)).thenReturn(false);
        when(level.getBlockState(any(BlockPos.class))).thenReturn(Blocks.POWDER_SNOW.defaultBlockState());
        WorldEnvironment snow = sample(access, world, 64.5);
        assertEquals(WorldEnvironment.EyeMedium.POWDER_SNOW, snow.eyeMedium());
        assertEquals(new WorldEnvironment.Dimension(-32, 384, false, WorldEnvironment.CardinalLighting.NETHER,
                63.0D, true, true, 256, true, 0.1F), snow.dimension());
        assertEquals(WorldEnvironment.Skybox.END, snow.sky().skybox());
    }

    private static WorldEnvironment sample(NativeWorldEnvironmentAccess access, CraftWorld world, double y) {
        return access.sample(world, new WorldEnvironmentAccess.Position(3.5, y, -4.5));
    }
}
