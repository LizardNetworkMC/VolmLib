package art.arcane.volmlib.nativelib.v26_2_R1.environment;

import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.volmlib.nativelib.environment.WorldEnvironmentAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.Objects;

public final class NativeWorldEnvironmentAccess implements WorldEnvironmentAccess {
    @Override
    public WorldEnvironment sample(World world, Position position) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
        ServerLevel level = ((CraftWorld) world).getHandle();
        Vec3 eye = new Vec3(position.x(), position.y(), position.z());
        EnvironmentAttributeSystem attributes = level.environmentAttributes();
        DimensionType dimension = level.dimensionType();
        return new WorldEnvironment(level.getGameTime(), sky(level, attributes, dimension, eye),
                fog(attributes, eye), lighting(attributes, eye), clouds(attributes, eye),
                dimension(level, dimension), eyeMedium(level, eye));
    }

    private static WorldEnvironment.Sky sky(ServerLevel level, EnvironmentAttributeSystem attributes,
                                            DimensionType dimension, Vec3 eye) {
        return new WorldEnvironment.Sky(WorldEnvironment.Skybox.valueOf(dimension.skybox().name()),
                attributes.getValue(EnvironmentAttributes.SUN_ANGLE, eye),
                attributes.getValue(EnvironmentAttributes.MOON_ANGLE, eye),
                attributes.getValue(EnvironmentAttributes.STAR_ANGLE, eye),
                attributes.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, eye),
                colorAlpha(attributes.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, eye)),
                color(attributes.getValue(EnvironmentAttributes.SKY_COLOR, eye)),
                attributes.getValue(EnvironmentAttributes.MOON_PHASE, eye).ordinal(),
                level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
    }

    private static WorldEnvironment.Fog fog(EnvironmentAttributeSystem attributes, Vec3 eye) {
        return new WorldEnvironment.Fog(color(attributes.getValue(EnvironmentAttributes.FOG_COLOR, eye)),
                attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, eye),
                attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, eye),
                attributes.getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, eye),
                attributes.getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, eye),
                color(attributes.getValue(EnvironmentAttributes.WATER_FOG_COLOR, eye)),
                attributes.getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, eye),
                attributes.getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, eye));
    }

    private static WorldEnvironment.Lighting lighting(EnvironmentAttributeSystem attributes, Vec3 eye) {
        return new WorldEnvironment.Lighting(color(attributes.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, eye)),
                attributes.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, eye),
                color(attributes.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, eye)),
                color(attributes.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, eye)));
    }

    private static WorldEnvironment.Clouds clouds(EnvironmentAttributeSystem attributes, Vec3 eye) {
        return new WorldEnvironment.Clouds(colorAlpha(attributes.getValue(EnvironmentAttributes.CLOUD_COLOR, eye)),
                attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, eye));
    }

    private static WorldEnvironment.Dimension dimension(ServerLevel level, DimensionType dimension) {
        return new WorldEnvironment.Dimension(dimension.minY(), dimension.height(), dimension.hasSkyLight(),
                WorldEnvironment.CardinalLighting.valueOf(dimension.cardinalLightType().name()),
                level.isFlat() ? dimension.minY() : 63.0D, dimension.hasEndFlashes(), dimension.hasFixedTime(),
                dimension.logicalHeight(), dimension.hasCeiling(), dimension.ambientLight());
    }

    private static WorldEnvironment.EyeMedium eyeMedium(ServerLevel level, Vec3 eye) {
        BlockPos position = BlockPos.containing(eye);
        FluidState fluid = level.getFluidState(position);
        if (fluid.is(FluidTags.WATER) && eye.y < position.getY() + fluid.getHeight(level, position)) {
            return WorldEnvironment.EyeMedium.WATER;
        }
        if (fluid.is(FluidTags.LAVA) && eye.y < position.getY() + fluid.getHeight(level, position)) {
            return WorldEnvironment.EyeMedium.LAVA;
        }
        return level.getBlockState(position).is(Blocks.POWDER_SNOW)
                ? WorldEnvironment.EyeMedium.POWDER_SNOW : WorldEnvironment.EyeMedium.NONE;
    }

    private static WorldEnvironment.Color color(int value) {
        return new WorldEnvironment.Color(ARGB.redFloat(value), ARGB.greenFloat(value), ARGB.blueFloat(value));
    }

    private static WorldEnvironment.ColorAlpha colorAlpha(int value) {
        return new WorldEnvironment.ColorAlpha(ARGB.redFloat(value), ARGB.greenFloat(value), ARGB.blueFloat(value),
                ARGB.alphaFloat(value));
    }
}
