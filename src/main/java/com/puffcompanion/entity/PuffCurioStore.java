package com.puffcompanion.entity;

import com.puffcompanion.compat.CuriosCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puff's own Curios storage: slot type -> stacks. Pure Minecraft types (no Curios classes), so items survive
 * even if Curios is removed later (they just stop doing anything). When a slot type loses slots, the
 * accessories in the removed slots are handed back (nest / dropped) by the caller - never deleted.
 */
public class PuffCurioStore {
    private final Map<String, List<ItemStack>> slots = new LinkedHashMap<>();

    public void ensureLayout(List<CuriosCompat.SlotGroup> layout) {
        for (CuriosCompat.SlotGroup g : layout) {
            List<ItemStack> list = this.slots.computeIfAbsent(g.id(), k -> new ArrayList<>());
            while (list.size() < g.count()) list.add(ItemStack.EMPTY);
        }
    }

    /** Grows (never shrinks) one slot type to at least {@code size} slots. */
    public void ensureSize(String type, int size) {
        List<ItemStack> list = this.slots.computeIfAbsent(type, k -> new ArrayList<>());
        while (list.size() < size) list.add(ItemStack.EMPTY);
    }

    /** Non-empty stacks at index >= {@code size} of {@code type} (they no longer have a slot). */
    public List<ItemStack> overflow(String type, int size) {
        List<ItemStack> out = new ArrayList<>();
        List<ItemStack> list = this.slots.get(type);
        if (list != null) for (int i = size; i < list.size(); i++) if (!list.get(i).isEmpty()) out.add(list.get(i));
        return out;
    }

    /** Cuts {@code type} down to {@code size} slots and returns the stacks that were in the removed slots. */
    public List<ItemStack> shrinkTo(String type, int size) {
        List<ItemStack> out = this.overflow(type, size);
        List<ItemStack> list = this.slots.get(type);
        if (list != null) while (list.size() > size) list.remove(list.size() - 1);
        return out;
    }

    public ItemStack get(String type, int index) {
        List<ItemStack> list = this.slots.get(type);
        return list == null || index >= list.size() ? ItemStack.EMPTY : list.get(index);
    }

    public void set(String type, int index, ItemStack stack) {
        List<ItemStack> list = this.slots.computeIfAbsent(type, k -> new ArrayList<>());
        while (list.size() <= index) list.add(ItemStack.EMPTY);
        list.set(index, stack);
    }

    public Map<String, List<ItemStack>> all() {
        return this.slots;
    }

    /** Removes and returns every non-empty stack (used when Puff dies / is packed into a snapshot copy). */
    public List<ItemStack> clear() {
        List<ItemStack> out = new ArrayList<>();
        this.slots.values().forEach(list -> {
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).isEmpty()) out.add(list.get(i));
                list.set(i, ItemStack.EMPTY);
            }
        });
        return out;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        this.slots.forEach((type, list) -> {
            ListTag items = new ListTag();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).isEmpty()) continue;
                CompoundTag t = list.get(i).save(new CompoundTag());
                t.putInt("Slot", i);
                items.add(t);
            }
            CompoundTag group = new CompoundTag();
            group.putInt("Size", list.size());
            group.put("Items", items);
            tag.put(type, group);
        });
        return tag;
    }

    public void load(CompoundTag tag) {
        this.slots.clear();
        for (String type : tag.getAllKeys()) {
            CompoundTag group = tag.getCompound(type);
            List<ItemStack> list = new ArrayList<>();
            for (int i = 0; i < group.getInt("Size"); i++) list.add(ItemStack.EMPTY);
            ListTag items = group.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag t = items.getCompound(i);
                int slot = t.getInt("Slot");
                while (list.size() <= slot) list.add(ItemStack.EMPTY);
                list.set(slot, ItemStack.of(t));
            }
            this.slots.put(type, list);
        }
    }
}
