package com.puffcompanion.menu;

import com.puffcompanion.entity.PuffEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Live view of Puff's equipment (main hand, off hand, helmet, chestplate, leggings, boots) as a container.
 * Writes go straight to the entity, so weapon / armor attribute modifiers (attack damage, armor, toughness,
 * knockback resistance, ApothicAttributes...) and armor enchantments apply through vanilla equipment handling.
 */
public class PuffEquipmentContainer implements Container {
    public static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    /** Container index of the first armor slot (helmet); armor runs to the end. */
    public static final int ARMOR_START = 2;

    private final PuffEntity puff;

    public PuffEquipmentContainer(PuffEntity puff) {
        this.puff = puff;
    }

    @Override
    public int getContainerSize() {
        return SLOTS.length;
    }

    @Override
    public boolean isEmpty() {
        for (EquipmentSlot slot : SLOTS) if (!this.puff.getItemBySlot(slot).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        return this.puff.getItemBySlot(SLOTS[index]);
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        ItemStack current = this.getItem(index);
        if (current.isEmpty() || count <= 0) return ItemStack.EMPTY;
        ItemStack split = current.split(count);
        this.setItem(index, current);
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack current = this.getItem(index);
        this.setItem(index, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        this.puff.setPanelEquipment(SLOTS[index], stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return this.puff.isAlive() && this.puff.distanceToSqr(player) <= PuffEntity.INTERACT_RANGE * PuffEntity.INTERACT_RANGE;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < SLOTS.length; i++) this.setItem(i, ItemStack.EMPTY);
    }
}
