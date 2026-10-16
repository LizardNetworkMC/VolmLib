package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Unbreakable;

import java.util.List;

public final class NativeTaggedItems {
    private NativeTaggedItems() {
    }

    public static NativeItemStack create(Options options) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(options.itemKey())));
        stack.set(DataComponents.CUSTOM_NAME, options.name().component());
        stack.set(DataComponents.LORE, new ItemLore(options.lore().stream().map(NativeCommandText::component).toList()));
        if (options.unbreakable()) {
            stack.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
        }
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, options.glint());
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(options.flag(), true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return new NativeItemStack(stack);
    }

    public record Options(String itemKey, NativeCommandText name, List<NativeCommandText> lore,
                          String flag, boolean unbreakable, boolean glint) {
    }
}
