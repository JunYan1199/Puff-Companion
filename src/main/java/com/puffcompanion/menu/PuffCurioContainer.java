package com.puffcompanion.menu;

import com.puffcompanion.compat.CuriosCompat;
import com.puffcompanion.entity.PuffCurioStore;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Flat container view over Puff's curio store, in layout order (type by type). */
public class PuffCurioContainer implements Container {
    private record Ref(String type, int index) {}

    private final PuffCurioStore store;
    private final List<Ref> refs = new ArrayList<>();

    public PuffCurioContainer(PuffCurioStore store, List<CuriosCompat.SlotGroup> layout) {
        this.store = store;
        for (CuriosCompat.SlotGroup g : layout) {
            for (int i = 0; i < g.count(); i++) this.refs.add(new Ref(g.id(), i));
        }
    }

    @Override
    public int getContainerSize() {
        return Math.max(1, this.refs.size());
    }

    @Override
    public boolean isEmpty() {
        for (Ref r : this.refs) if (!this.store.get(r.type(), r.index()).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot >= this.refs.size()) return ItemStack.EMPTY;
        Ref r = this.refs.get(slot);
        return this.store.get(r.type(), r.index());
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack cur = this.getItem(slot);
        if (cur.isEmpty() || count <= 0) return ItemStack.EMPTY;
        ItemStack split = cur.split(count);
        this.setItem(slot, cur.isEmpty() ? ItemStack.EMPTY : cur);
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack cur = this.getItem(slot);
        this.setItem(slot, ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= this.refs.size()) return;
        Ref r = this.refs.get(slot);
        this.store.set(r.type(), r.index(), stack);
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
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < this.refs.size(); i++) this.setItem(i, ItemStack.EMPTY);
    }
}
