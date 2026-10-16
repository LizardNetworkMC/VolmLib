/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import art.arcane.volmlib.util.collection.KMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class NativeTileReader {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setLenient().setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final int DYE_COLOR_COUNT = 16;
    private static final ResourceLocation DEFAULT_SPAWNER_ENTITY = ResourceLocation.parse("minecraft:pig");
    private static final ResourceLocation DEFAULT_BANNER_PATTERN = ResourceLocation.parse("minecraft:base");
    private static final List<ResourceLocation> LEGACY_BUKKIT_ENTITY_TYPES = createLegacyBukkitEntityTypes();
    private static final List<ResourceLocation> PAPER_26_2_BANNER_PATTERNS = List.of(
            ResourceLocation.parse("minecraft:small_stripes"),
            ResourceLocation.parse("minecraft:stripe_right"),
            ResourceLocation.parse("minecraft:diagonal_left"),
            ResourceLocation.parse("minecraft:stripe_middle"),
            ResourceLocation.parse("minecraft:square_bottom_right"),
            ResourceLocation.parse("minecraft:half_horizontal"),
            ResourceLocation.parse("minecraft:skull"),
            ResourceLocation.parse("minecraft:flow"),
            ResourceLocation.parse("minecraft:rhombus"),
            ResourceLocation.parse("minecraft:border"),
            ResourceLocation.parse("minecraft:gradient_up"),
            ResourceLocation.parse("minecraft:guster"),
            ResourceLocation.parse("minecraft:square_top_left"),
            ResourceLocation.parse("minecraft:triangle_bottom"),
            ResourceLocation.parse("minecraft:triangles_bottom"),
            ResourceLocation.parse("minecraft:half_horizontal_bottom"),
            ResourceLocation.parse("minecraft:gradient"),
            ResourceLocation.parse("minecraft:triangle_top"),
            ResourceLocation.parse("minecraft:piglin"),
            ResourceLocation.parse("minecraft:stripe_center"),
            ResourceLocation.parse("minecraft:circle"),
            ResourceLocation.parse("minecraft:stripe_left"),
            ResourceLocation.parse("minecraft:stripe_bottom"),
            ResourceLocation.parse("minecraft:square_top_right"),
            ResourceLocation.parse("minecraft:curly_border"),
            ResourceLocation.parse("minecraft:creeper"),
            ResourceLocation.parse("minecraft:square_bottom_left"),
            ResourceLocation.parse("minecraft:triangles_top"),
            ResourceLocation.parse("minecraft:half_vertical"),
            ResourceLocation.parse("minecraft:mojang"),
            ResourceLocation.parse("minecraft:diagonal_right"),
            ResourceLocation.parse("minecraft:cross"),
            ResourceLocation.parse("minecraft:straight_cross"),
            ResourceLocation.parse("minecraft:bricks"),
            ResourceLocation.parse("minecraft:diagonal_up_left"),
            ResourceLocation.parse("minecraft:base"),
            ResourceLocation.parse("minecraft:flower"),
            ResourceLocation.parse("minecraft:stripe_downleft"),
            ResourceLocation.parse("minecraft:diagonal_up_right"),
            ResourceLocation.parse("minecraft:stripe_downright"),
            ResourceLocation.parse("minecraft:stripe_top"),
            ResourceLocation.parse("minecraft:globe"),
            ResourceLocation.parse("minecraft:half_vertical_right"));

    private final Supplier<HolderLookup.Provider> registries;

    public NativeTileReader(Supplier<HolderLookup.Provider> registries) {
        this.registries = registries;
    }

    private static final class ReplayInputStream extends InputStream {
        private final InputStream source;
        private byte[] buffer = new byte[256];
        private int size = 0;
        private int position = 0;
        private int marked = 0;

        private ReplayInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            if (position < size) {
                int value = buffer[position] & 0xFF;
                position++;
                return value;
            }
            int value = source.read();
            if (value < 0) {
                return value;
            }
            if (size == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            buffer[size] = (byte) value;
            size++;
            position++;
            return value;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public synchronized void mark(int readLimit) {
            marked = position;
        }

        @Override
        public synchronized void reset() {
            position = marked;
        }

        private void rewind() {
            position = 0;
        }

        private int position() {
            return position;
        }

        private byte[] consumed() {
            return Arrays.copyOf(buffer, position);
        }
    }

    public NativeTileData read(DataInputStream in) throws IOException {
        if (!in.markSupported()) {
            throw new IOException("Mark not supported");
        }
        in.mark(Integer.MAX_VALUE);
        ReplayInputStream replay = new ReplayInputStream(in);
        DataInputStream din = new DataInputStream(replay);
        try {
            return parse(din, replay);
        } finally {
            in.reset();
            in.skipNBytes(replay.position());
            in.mark(0);
        }
    }

    private NativeTileData parse(DataInputStream din, ReplayInputStream replay) throws IOException {
        try {
            String materialKey = din.readUTF();
            boolean materialMatched = matchMaterial(materialKey);
            String json = din.readUTF();
            KMap<String, Object> properties = kmapFromJson(json);
            if (!materialMatched) {
                throw new NullPointerException("material is marked non-null but is null");
            }
            if (properties == null) {
                throw new NullPointerException("properties is marked non-null but is null");
            }
            return new NativeTileData(replay.consumed(), properties,
                    NativeTileData.normalizeBlockKey(materialKey), -1);
        } catch (Throwable e) {
            replay.rewind();
            return parseLegacy(din, replay);
        }
    }

    @SuppressWarnings("unchecked")
    private static KMap<String, Object> kmapFromJson(String json) {
        return GSON.fromJson(json, KMap.class);
    }

    private NativeTileData parseLegacy(DataInputStream din, ReplayInputStream replay) throws IOException {
        int id = din.readShort();
        String expectedBlockKey = null;
        KMap<String, Object> properties = switch (id) {
            case 0 -> readSign(din);
            case 1 -> readSpawner(din, replay);
            case 2 -> readBanner(din, replay);
            case 3 -> {
                expectedBlockKey = NativeTileData.normalizeBlockKey(din.readUTF());
                yield readLootable(din);
            }
            default -> throw new IOException("Unknown tile type: " + id);
        };
        return new NativeTileData(replay.consumed(), properties, expectedBlockKey, id);
    }

    private static KMap<String, Object> readSign(DataInputStream din) throws IOException {
        List<String> messages = List.of(din.readUTF(), din.readUTF(), din.readUTF(), din.readUTF());
        byte dye = din.readByte();
        if (dye < 0 || dye >= DYE_COLOR_COUNT) {
            throw new ArrayIndexOutOfBoundsException("Index " + dye + " out of bounds for length " + DYE_COLOR_COUNT);
        }
        KMap<String, Object> text = new KMap<>();
        text.put("messages", messages);
        text.put("color", DyeColor.byId(dye).getName());
        text.put("has_glowing_text", false);
        KMap<String, Object> properties = new KMap<>();
        properties.put("front_text", text);
        properties.put("back_text", text.copy());
        return properties;
    }

    private static KMap<String, Object> readSpawner(DataInputStream din, ReplayInputStream replay) throws IOException {
        ResourceLocation entityId = null;
        replay.mark(Integer.MAX_VALUE);

        try {
            String keyString = din.readUTF();
            ResourceLocation key = ResourceLocation.tryParse(keyString);
            if (key != null && BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                entityId = key;
            } else {
                replay.reset();
            }
        } catch (Throwable ignored) {
            replay.reset();
        }

        if (entityId == null) {
            entityId = legacySpawnerEntityId(din.readShort());
        }
        KMap<String, Object> entity = new KMap<>();
        entity.put("id", entityId.toString());
        KMap<String, Object> spawnData = new KMap<>();
        spawnData.put("entity", entity);
        KMap<String, Object> properties = new KMap<>();
        properties.put("SpawnData", spawnData);
        return properties;
    }

    public static ResourceLocation legacySpawnerEntityId(int legacyOrdinal) {
        if (legacyOrdinal < 0 || legacyOrdinal >= LEGACY_BUKKIT_ENTITY_TYPES.size()) {
            return DEFAULT_SPAWNER_ENTITY;
        }
        return LEGACY_BUKKIT_ENTITY_TYPES.get(legacyOrdinal);
    }

    private static List<ResourceLocation> createLegacyBukkitEntityTypes() {
        List<String> keys = List.of(
                "item", "experience_orb", "area_effect_cloud", "elder_guardian",
                "wither_skeleton", "stray", "egg", "leash_knot",
                "painting", "arrow", "snowball", "fireball",
                "small_fireball", "ender_pearl", "eye_of_ender", "potion",
                "experience_bottle", "item_frame", "wither_skull", "tnt",
                "falling_block", "firework_rocket", "husk", "spectral_arrow",
                "shulker_bullet", "dragon_fireball", "zombie_villager", "skeleton_horse",
                "zombie_horse", "armor_stand", "donkey", "mule",
                "evoker_fangs", "evoker", "vex", "vindicator",
                "illusioner", "command_block_minecart", "boat", "minecart",
                "chest_minecart", "furnace_minecart", "tnt_minecart", "hopper_minecart",
                "spawner_minecart", "creeper", "skeleton", "spider",
                "giant", "zombie", "slime", "ghast",
                "zombified_piglin", "enderman", "cave_spider", "silverfish",
                "blaze", "magma_cube", "ender_dragon", "wither",
                "bat", "witch", "endermite", "guardian",
                "shulker", "pig", "sheep", "cow",
                "chicken", "squid", "wolf", "mooshroom",
                "snow_golem", "ocelot", "iron_golem", "horse",
                "rabbit", "polar_bear", "llama", "llama_spit",
                "parrot", "villager", "end_crystal", "turtle",
                "phantom", "trident", "cod", "salmon",
                "pufferfish", "tropical_fish", "drowned", "dolphin",
                "cat", "panda", "pillager", "ravager",
                "trader_llama", "wandering_trader", "fox", "bee",
                "hoglin", "piglin", "strider", "zoglin",
                "piglin_brute", "axolotl", "glow_item_frame", "glow_squid",
                "goat", "marker", "allay", "chest_boat",
                "frog", "tadpole", "warden", "camel",
                "block_display", "interaction", "item_display", "sniffer",
                "text_display", "breeze", "wind_charge", "breeze_wind_charge",
                "armadillo", "bogged", "ominous_item_spawner", "fishing_bobber",
                "lightning_bolt", "player", "pig");
        List<ResourceLocation> entityTypes = new ArrayList<>(keys.size());
        for (String key : keys) {
            entityTypes.add(ResourceLocation.withDefaultNamespace(key));
        }
        return List.copyOf(entityTypes);
    }

    private KMap<String, Object> readBanner(DataInputStream din, ReplayInputStream replay) throws IOException {
        int baseColor = din.readUnsignedByte();
        int listSize = din.readUnsignedByte();
        replay.mark(Integer.MAX_VALUE);

        List<Object> layers = new ArrayList<>(listSize);
        try {
            for (int i = 0; i < listSize; i++) {
                int color = din.readUnsignedByte();
                ResourceLocation patternKey = ResourceLocation.tryParse(din.readUTF());
                if (patternKey == null || !bannerPatternExists(patternKey)) {
                    throw new IOException("Unknown banner pattern key");
                }
                layers.add(bannerLayer(patternKey, color));
            }
        } catch (Throwable ignored) {
            replay.reset();
            layers.clear();
        }

        if (layers.isEmpty() && listSize > 0) {
            for (int i = 0; i < listSize; i++) {
                int color = din.readUnsignedByte();
                int pattern = din.readUnsignedByte();
                layers.add(bannerLayer(legacyBannerPatternKey(pattern), color));
            }
        }
        KMap<String, Object> properties = new KMap<>();
        properties.put("patterns", layers);
        properties.put(NativeTileData.LEGACY_BANNER_COLOR_PROPERTY, DyeColor.byId(baseColor).getName());
        return properties;
    }

    private static KMap<String, Object> bannerLayer(ResourceLocation pattern, int color) {
        KMap<String, Object> layer = new KMap<>();
        layer.put("pattern", pattern.toString());
        layer.put("color", DyeColor.byId(color).getName());
        return layer;
    }

    public static ResourceLocation legacyBannerPatternKey(int legacyOrdinal) {
        if (legacyOrdinal < 0 || legacyOrdinal >= PAPER_26_2_BANNER_PATTERNS.size()) {
            return DEFAULT_BANNER_PATTERN;
        }
        return PAPER_26_2_BANNER_PATTERNS.get(legacyOrdinal);
    }

    private boolean bannerPatternExists(ResourceLocation key) {
        HolderLookup.Provider access = registries.get();
        if (access == null) {
            return true;
        }
        HolderLookup.RegistryLookup<BannerPattern> registry = access.lookupOrThrow(Registries.BANNER_PATTERN);
        return registry.get(ResourceKey.create(Registries.BANNER_PATTERN, key)).isPresent();
    }

    private static KMap<String, Object> readLootable(DataInputStream din) throws IOException {
        String lootTable = din.readUTF();
        long seed = din.readLong();
        KMap<String, Object> properties = new KMap<>();
        if (!lootTable.isBlank()) {
            properties.put("LootTable", lootTable);
            properties.put("LootTableSeed", seed);
        }
        return properties;
    }

    private static boolean matchMaterial(String name) {
        String filtered = name.trim().toLowerCase(Locale.ROOT);
        int bracket = filtered.indexOf('[');
        if (bracket >= 0) {
            filtered = filtered.substring(0, bracket);
        }
        if (!filtered.contains(":")) {
            filtered = "minecraft:" + filtered.replaceAll("\\s+", "_");
        }
        ResourceLocation identifier = ResourceLocation.tryParse(filtered);
        if (identifier == null) {
            return false;
        }
        return BuiltInRegistries.BLOCK.containsKey(identifier);
    }
}
