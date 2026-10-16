package art.arcane.volmlib.nativelib.minecraft26_2.modded;

import art.arcane.volmlib.nativelib.terrain.NativeBlockPoint;
import art.arcane.volmlib.nativelib.terrain.NativeBlockState;
import art.arcane.volmlib.nativelib.terrain.NativeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NativeHarvestSession {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final int heldSlot;
    private final ItemStack toolBefore;
    private ItemStack expectedTool = ItemStack.EMPTY;
    private boolean toolBroken;

    public NativeHarvestSession(NativeWorld world, NativeProtocolPlayer player) {
        level = (ServerLevel) world.nativeHandle();
        this.player = player.player();
        heldSlot = this.player.getInventory().selected;
        toolBefore = this.player.getInventory().getSelected().copy();
    }

    public static boolean survival(NativeProtocolPlayer player) {
        return player.player().gameMode.getGameModeForPlayer().isSurvival();
    }

    public static boolean sneaking(NativeProtocolPlayer player) {
        return player.player().isShiftKeyDown();
    }

    public static boolean holdingAxe(NativeProtocolPlayer player) {
        return player.player().getInventory().getSelected().is(ItemTags.AXES);
    }

    public static boolean hasPermission(NativeModdedLoader loader, NativeProtocolPlayer player) {
        return loader.hasBlockBreakPermission(player.player());
    }

    public boolean canBreak(NativeModdedLoader loader, NativeBlockPoint point, NativeBlockState state) {
        return loader.canBreakBlock(level, player, position(point), (BlockState) state.nativeHandle());
    }

    public boolean survival() {
        return player.gameMode.getGameModeForPlayer().isSurvival();
    }

    public boolean sneaking() {
        return player.isShiftKeyDown();
    }

    public boolean holdingAxe() {
        return player.getInventory().getSelected().is(ItemTags.AXES);
    }

    public boolean toolBroken() {
        return toolBroken;
    }

    public boolean active() {
        if (player.isRemoved() || player.hasDisconnected() || player.level() != level
                || player.getInventory().selected != heldSlot || expectedTool.isEmpty()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(player.getInventory().getSelected(), expectedTool);
    }

    public boolean normalizeOriginTool(boolean preserve) {
        ItemStack current = player.getInventory().getItem(heldSlot);
        boolean matches = !current.isEmpty() && sameToolExceptDamage(toolBefore, current);
        if (!toolBefore.isDamageableItem()) {
            if (!matches || !ItemStack.isSameItemSameComponents(toolBefore, current)) {
                return false;
            }
            expectedTool = current.copy();
            return true;
        }
        int damage = toolBefore.getDamageValue() + (preserve ? 0 : 1);
        if (damage >= toolBefore.getMaxDamage()) {
            if (!current.isEmpty() && !matches) {
                return false;
            }
            ItemStack brokenItem = toolBefore.copy();
            player.getInventory().setItem(heldSlot, ItemStack.EMPTY);
            if (!current.isEmpty()) {
                player.onEquippedItemBroken(brokenItem.getItem(), EquipmentSlot.MAINHAND);
            }
            player.inventoryMenu.sendAllDataToRemote();
            expectedTool = ItemStack.EMPTY;
            toolBroken = true;
            return true;
        }
        if (!current.isEmpty() && !matches) {
            return false;
        }
        ItemStack normalized = toolBefore.copy();
        normalized.setDamageValue(damage);
        player.getInventory().setItem(heldSlot, normalized);
        player.inventoryMenu.sendAllDataToRemote();
        expectedTool = normalized.copy();
        return true;
    }

    public Reservation reserveToolDamage(boolean preserve) {
        ItemStack current = player.getInventory().getSelected();
        if (!ItemStack.isSameItemSameComponents(current, expectedTool) || !current.is(ItemTags.AXES)) {
            return null;
        }
        ItemStack before = current.copy();
        if (!current.isDamageableItem() || preserve) {
            return new Reservation(before, false, false);
        }
        int damage = current.getDamageValue() + 1;
        if (damage >= current.getMaxDamage()) {
            ItemStack brokenItem = current.copy();
            player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
            player.onEquippedItemBroken(brokenItem.getItem(), EquipmentSlot.MAINHAND);
            player.inventoryMenu.sendAllDataToRemote();
            expectedTool = ItemStack.EMPTY;
            return new Reservation(before, true, true);
        }
        current.setDamageValue(damage);
        player.getInventory().setItem(player.getInventory().selected, current);
        player.inventoryMenu.sendAllDataToRemote();
        expectedTool = current.copy();
        return new Reservation(before, true, false);
    }

    public void refundToolDamage(Reservation reservation) {
        if (!reservation.charged) {
            return;
        }
        ItemStack current = player.getInventory().getSelected();
        if (expectedTool.isEmpty() ? current.isEmpty() : ItemStack.isSameItemSameComponents(current, expectedTool)) {
            ItemStack restored = reservation.before.copy();
            player.getInventory().setItem(player.getInventory().selected, restored);
            player.inventoryMenu.sendAllDataToRemote();
            expectedTool = restored.copy();
        }
    }

    public boolean loaded(NativeBlockPoint point) {
        return level.isLoaded(position(point));
    }

    public boolean mayDestroy(NativeBlockPoint point, NativeBlockState state) {
        BlockPos position = position(point);
        return level.mayInteract(player, position) && !player.blockActionRestricted(level, position, player.gameMode.getGameModeForPlayer())
                && player.getInventory().getSelected().getItem().canAttackBlock((BlockState) state.nativeHandle(), level, position, player);
    }

    public List<NativeItemStack> drops(NativeBlockPoint point, NativeBlockState blockState) {
        BlockPos position = position(point);
        BlockState state = (BlockState) blockState.nativeHandle();
        ItemStack tool = player.getInventory().getSelected().copy();
        BlockEntity entity = state.hasBlockEntity() ? level.getBlockEntity(position) : null;
        List<ItemStack> drops = Block.getDrops(state, level, position, entity, player, tool);
        List<NativeItemStack> items = new ArrayList<>(drops.size());
        for (ItemStack drop : drops) {
            items.add(new NativeItemStack(drop));
        }
        return items;
    }

    public boolean destroy(NativeBlockPoint point) {
        return level.destroyBlock(position(point), false, player, 512);
    }

    public static boolean log(NativeBlockState state) {
        return ((BlockState) state.nativeHandle()).is(BlockTags.LOGS);
    }

    public static boolean air(NativeBlockState state) {
        return ((BlockState) state.nativeHandle()).isAir();
    }

    private static boolean sameToolExceptDamage(ItemStack before, ItemStack after) {
        if (!ItemStack.isSameItem(before, after) || before.getCount() != after.getCount()) {
            return false;
        }
        DataComponentMap previous = before.getComponents();
        DataComponentMap current = after.getComponents();
        int previousSize = previous.size() - (previous.has(DataComponents.DAMAGE) ? 1 : 0);
        int currentSize = current.size() - (current.has(DataComponents.DAMAGE) ? 1 : 0);
        if (previousSize != currentSize) {
            return false;
        }
        for (DataComponentType<?> type : previous.keySet()) {
            if (type != DataComponents.DAMAGE && !Objects.equals(previous.get(type), current.get(type))) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos position(NativeBlockPoint point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }

    public static final class Reservation {
        private final ItemStack before;
        private final boolean charged;
        private final boolean broke;

        private Reservation(ItemStack before, boolean charged, boolean broke) {
            this.before = before;
            this.charged = charged;
            this.broke = broke;
        }

        public boolean broke() {
            return broke;
        }
    }
}
