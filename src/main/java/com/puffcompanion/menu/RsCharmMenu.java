package com.puffcompanion.menu;

import com.puffcompanion.item.RsDepositCharmItem;
import com.puffcompanion.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Filter editor of the RS deposit charm held in {@code hand}: 18 ghost slots (clicking with an item records a
 * copy, nothing is consumed; clicking with an empty hand clears) and a whitelist / blacklist toggle (menu
 * button 0). Changes are written straight into the charm's NBT.
 */
public class RsCharmMenu extends AbstractContainerMenu {
    public static final int GHOST_X = 8, GHOST_Y = 20, INV_Y = 84;

    private final InteractionHand hand;
    private final Player player;
    private final SimpleContainer filter;
    private final DataSlot whitelist = DataSlot.standalone();

    public RsCharmMenu(int id, Inventory inv, InteractionHand hand) {
        super(ModMenus.RS_CHARM.get(), id);
        this.hand = hand;
        this.player = inv.player;
        ItemStack charm = this.charm();
        this.filter = RsDepositCharmItem.readFilter(charm);
        this.whitelist.set(RsDepositCharmItem.isWhitelist(charm) ? 1 : 0);
        this.addDataSlot(this.whitelist);
        for (int i = 0; i < RsDepositCharmItem.FILTER_SIZE; i++) {
            this.addSlot(new GhostSlot(this.filter, i, GHOST_X + (i % 9) * 18, GHOST_Y + (i / 9) * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new LockedSlot(inv, col + row * 9 + 9, 8 + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) this.addSlot(new LockedSlot(inv, col, 8 + col * 18, INV_Y + 58));
    }

    public static RsCharmMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        return new RsCharmMenu(id, inv, buf.readEnum(InteractionHand.class));
    }

    private ItemStack charm() {
        ItemStack s = this.player.getItemInHand(this.hand);
        return s.getItem() instanceof RsDepositCharmItem ? s : ItemStack.EMPTY;
    }

    public boolean isWhitelist() {
        return this.whitelist.get() != 0;
    }

    private void save() {
        ItemStack charm = this.charm();
        if (charm.isEmpty()) return;
        RsDepositCharmItem.writeFilter(charm, this.filter);
        RsDepositCharmItem.setWhitelist(charm, this.isWhitelist());
    }

    @Override
    public void clicked(int slotId, int button, ClickType type, Player player) {
        if (slotId >= 0 && slotId < RsDepositCharmItem.FILTER_SIZE) {
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE || type == ClickType.SWAP) {
                ItemStack carried = this.getCarried();
                this.filter.setItem(slotId, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
                this.save();
            }
            return;
        }
        super.clicked(slotId, button, type, player);
    }

    /** Shift-click in the inventory: add that item to the filter (nothing moves). */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < RsDepositCharmItem.FILTER_SIZE) return ItemStack.EMPTY;
        ItemStack stack = this.slots.get(index).getItem();
        if (stack.isEmpty()) return ItemStack.EMPTY;
        for (int i = 0; i < RsDepositCharmItem.FILTER_SIZE; i++) {
            if (ItemStack.isSameItem(this.filter.getItem(i), stack)) return ItemStack.EMPTY;
        }
        for (int i = 0; i < RsDepositCharmItem.FILTER_SIZE; i++) {
            if (this.filter.getItem(i).isEmpty()) {
                this.filter.setItem(i, stack.copyWithCount(1));
                this.save();
                break;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != 0) return false;
        this.whitelist.set(this.isWhitelist() ? 0 : 1);
        this.save();
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return !this.charm().isEmpty();
    }

    /** Filter slot: shows a ghost copy; all clicks are handled by {@link #clicked}. */
    private static class GhostSlot extends Slot {
        GhostSlot(SimpleContainer c, int index, int x, int y) {
            super(c, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /** Player inventory; the charm being edited cannot be moved. */
    private class LockedSlot extends Slot {
        LockedSlot(Inventory inv, int index, int x, int y) {
            super(inv, index, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return !(RsCharmMenu.this.hand == InteractionHand.MAIN_HAND
                    && this.getContainerSlot() == RsCharmMenu.this.player.getInventory().selected);
        }
    }
}
