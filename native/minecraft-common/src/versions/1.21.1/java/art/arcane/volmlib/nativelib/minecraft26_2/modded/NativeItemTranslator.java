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

import art.arcane.volmlib.nativelib.item.NativeItemRecipe;
import art.arcane.volmlib.util.json.JSONObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class NativeItemTranslator {
    private NativeItemTranslator() {
    }

    public static NativeItemStack stack(NativeItemRecipe loot, NativeItemContext context) {
        ItemStack stack = baseStack(loot, context);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        applyComponents(loot, stack, context);
        applyCustomNbt(stack, loot.customNbt(), context);
        return new NativeItemStack(stack);
    }

    private static ItemStack baseStack(NativeItemRecipe loot, NativeItemContext context) {
        String raw = loot.typeKey();
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String key = raw.toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(key.contains(":") ? key : "minecraft:" + key);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            context.warn("item:" + key, "unknown item '" + raw + "'");
            return null;
        }

        Item item = BuiltInRegistries.ITEM.get(id);
        return new ItemStack(item, Math.max(1, loot.amount()));
    }

    private static void applyComponents(NativeItemRecipe loot, ItemStack stack, NativeItemContext context) {
        applyEnchantments(loot, stack, context);
        applyAttributes(loot, stack, context);

        if (loot.unbreakable()) {
            stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
        }

        if (loot.customModel() != null) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(loot.customModel()));
        }

        if (stack.getMaxDamage() > 0) {
            int max = stack.getMaxDamage();
            int damage = (int) Math.round(Math.max(0, Math.min(max, (1D - loot.durability()) * max)));
            stack.set(DataComponents.DAMAGE, damage);
        }

        if (loot.leatherColor() != null) {
            try {
                int rgb = Integer.decode(loot.leatherColor()) & 0xFFFFFF;
                stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, true));
            } catch (NumberFormatException e) {
                context.warn("leatherColor:" + loot.leatherColor(), "invalid leatherColor '" + loot.leatherColor() + "'");
            }
        }

        String dye = loot.dyeColor();
        if (dye != null) {
            applyDyeColor(stack, dye, context);
        }

        String displayName = loot.displayName();
        if (displayName != null) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(displayName));
        }

        applyLore(loot, stack);
        applyItemFlags(loot, stack, context);
    }

    private static void applyEnchantments(NativeItemRecipe loot, ItemStack stack, NativeItemContext context) {
        List<? extends NativeItemRecipe.Enchantment> enchantments = loot.enchantments();
        if (enchantments.isEmpty()) {
            return;
        }

        Registry<Enchantment> registry = context.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        DataComponentType<ItemEnchantments> component = stack.is(Items.ENCHANTED_BOOK) ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS;
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(stack.getOrDefault(component, ItemEnchantments.EMPTY));
        boolean changed = false;

        for (NativeItemRecipe.Enchantment enchantment : enchantments) {
            String name = enchantment.name();
            if (name == null || name.isBlank()) {
                continue;
            }
            // Same normalization as IrisEnchantment.resolve() on Bukkit: trim, lowercase, spaces to underscores.
            // Without it 'Fire Aspect' resolves on Bukkit and warns as unknown here, for the same pack.
            String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            ResourceLocation id = ResourceLocation.tryParse(key.contains(":") ? key : "minecraft:" + key);
            Optional<Holder.Reference<Enchantment>> holder = id == null ? Optional.empty() : registry.getHolder(id);
            if (holder.isEmpty()) {
                context.warn("enchantment:" + key, "unknown enchantment '" + name + "'");
                continue;
            }
            if (loot.chance() < enchantment.chance()) {
                mutable.set(holder.get(), enchantment.level());
                changed = true;
            }
        }

        if (changed) {
            stack.set(component, mutable.toImmutable());
        }
    }

    private static void applyAttributes(NativeItemRecipe loot, ItemStack stack, NativeItemContext context) {
        List<? extends NativeItemRecipe.Attribute> attributes = loot.attributes();
        if (attributes.isEmpty()) {
            return;
        }

        Registry<Attribute> registry = context.level().registryAccess().registryOrThrow(Registries.ATTRIBUTE);
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        boolean changed = false;

        for (NativeItemRecipe.Attribute attribute : attributes) {
            if (loot.chance() >= attribute.chance()) {
                continue;
            }
            double amount = attribute.amount();
            Holder<Attribute> holder = resolveAttribute(registry, attribute.attribute());
            if (holder == null) {
                context.warn("attribute:" + attribute.attribute(), "unknown attribute '" + attribute.attribute() + "'");
                continue;
            }
            ResourceLocation modifierId = attributeModifierId(context.modifierNamespace(), attribute.name());
            if (modifierId == null) {
                continue;
            }
            AttributeModifier modifier = new AttributeModifier(modifierId, amount, operationFor(attribute.operation()));
            builder.add(holder, modifier, EquipmentSlotGroup.ANY);
            changed = true;
        }

        if (changed) {
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        }
    }

    public static Holder<Attribute> resolveAttribute(Registry<Attribute> registry, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String value = key.trim().toLowerCase(Locale.ROOT);
        Holder<Attribute> direct = lookupAttribute(registry, ResourceLocation.tryParse(value.indexOf(':') >= 0 ? value : "minecraft:" + value));
        if (direct != null) {
            return direct;
        }
        if (value.startsWith("generic_")) {
            return lookupAttribute(registry, ResourceLocation.tryParse("minecraft:" + value.substring("generic_".length())));
        }
        if (value.startsWith("generic.")) {
            return lookupAttribute(registry, ResourceLocation.tryParse("minecraft:" + value.substring("generic.".length())));
        }
        return null;
    }

    private static Holder<Attribute> lookupAttribute(Registry<Attribute> registry, ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Optional<Holder.Reference<Attribute>> holder = registry.getHolder(id);
        if (holder.isPresent()) {
            return holder.get();
        }
        if (id.getNamespace().equals("minecraft") && !id.getPath().contains(".")) {
            for (String prefix : List.of("generic.", "player.", "zombie.")) {
                Optional<Holder.Reference<Attribute>> namespaced = registry.getHolder(
                        ResourceLocation.fromNamespaceAndPath("minecraft", prefix + id.getPath()));
                if (namespaced.isPresent()) {
                    return namespaced.get();
                }
            }
        }
        return null;
    }

    public static AttributeModifier.Operation operationFor(String operation) {
        if (operation == null || operation.isBlank()) {
            return AttributeModifier.Operation.ADD_VALUE;
        }
        return switch (operation.trim().toUpperCase(Locale.ROOT)) {
            case "ADD_SCALAR", "ADD_MULTIPLIED_BASE" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "MULTIPLY_SCALAR_1", "ADD_MULTIPLIED_TOTAL" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static ResourceLocation attributeModifierId(String namespace, String name) {
        String source = name == null || name.isBlank() ? "modifier" : name;
        String normalized = source.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
        if (normalized.isBlank()) {
            normalized = "modifier";
        }
        return ResourceLocation.tryParse(namespace + ":" + normalized);
    }

    private static void applyItemFlags(NativeItemRecipe loot, ItemStack stack, NativeItemContext context) {
        for (String flag : loot.itemFlags()) {
            if (flag == null || flag.isBlank()) {
                continue;
            }
            switch (flag.trim().toUpperCase(Locale.ROOT)) {
                case "HIDE_ENCHANTS" -> hideTooltip(stack, DataComponents.ENCHANTMENTS, value -> value.withTooltip(false));
                case "HIDE_STORED_ENCHANTS" -> hideTooltip(stack, DataComponents.STORED_ENCHANTMENTS, value -> value.withTooltip(false));
                case "HIDE_ATTRIBUTES" -> hideTooltip(stack, DataComponents.ATTRIBUTE_MODIFIERS, value -> value.withTooltip(false));
                case "HIDE_UNBREAKABLE" -> hideTooltip(stack, DataComponents.UNBREAKABLE, value -> value.withTooltip(false));
                case "HIDE_DESTROYS" -> hideTooltip(stack, DataComponents.CAN_BREAK, value -> value.withTooltip(false));
                case "HIDE_PLACED_ON" -> hideTooltip(stack, DataComponents.CAN_PLACE_ON, value -> value.withTooltip(false));
                case "HIDE_DYE" -> hideTooltip(stack, DataComponents.DYED_COLOR, value -> value.withTooltip(false));
                case "HIDE_ARMOR_TRIM" -> hideTooltip(stack, DataComponents.TRIM, value -> value.withTooltip(false));
                default -> context.warn("itemFlag:" + flag, "item flag '" + flag + "' has no modded tooltip equivalent; skipping");
            }
        }
    }

    private static <T> void hideTooltip(ItemStack stack, DataComponentType<T> type, UnaryOperator<T> hide) {
        T value = stack.get(type);
        if (value != null) {
            stack.set(type, hide.apply(value));
        }
    }

    private static void applyDyeColor(ItemStack stack, String dye, NativeItemContext context) {
        DyeColor color = DyeColor.byName(dye.trim().toLowerCase(Locale.ROOT), null);
        if (color == null) {
            context.warn("dyeColor:" + dye, "unknown dyeColor '" + dye + "'");
            return;
        }
        stack.set(DataComponents.BASE_COLOR, color);
    }

    private static void applyLore(NativeItemRecipe loot, ItemStack stack) {
        List<String> lore = loot.lore();
        if (lore.isEmpty()) {
            return;
        }

        List<Component> lines = new ArrayList<>(lore.size());
        for (String line : lore) {
            lines.add(Component.literal(line));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines));
    }

    private static void applyCustomNbt(ItemStack stack, Map<String, Object> customNbt, NativeItemContext context) {
        if (customNbt == null || customNbt.isEmpty()) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseTag(new JSONObject(customNbt).toString());
            tag.merge(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (CommandSyntaxException e) {
            context.warn("customNbt:" + e.getMessage(), "invalid customNbt: " + e.getMessage());
        }
    }

}
