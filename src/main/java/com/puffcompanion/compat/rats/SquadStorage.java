package com.puffcompanion.compat.rats;

import com.puffcompanion.entity.PuffEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Item flow of the squad: work output goes into the bound supply / storage container, then Puff's secret
 * nest, and only if both are full it drops at the worker's feet - never lost. Supplies (cheese, seeds,
 * saplings, building blocks, animal food) are taken from the container first, then from the nest.
 */
public final class SquadStorage {
    private SquadStorage() {}

    /** Item handler of a container block (chest, barrel, modded storage...), or null. */
    @Nullable
    public static IItemHandler handlerAt(Level level, @Nullable BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
    }

    public static boolean isContainer(Level level, BlockPos pos) {
        return handlerAt(level, pos) != null;
    }

    /** Stores work output; whatever does not fit anywhere drops at {@code worker}. */
    public static void deposit(PuffEntity puff, Entity worker, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack rest = stack.copy();
        IItemHandler h = handlerAt(worker.level(), puff.getSquad().storage());
        if (h != null) rest = ItemHandlerHelper.insertItemStacked(h, rest, false);
        if (!rest.isEmpty()) rest = puff.getHoard().addItem(rest);
        if (!rest.isEmpty() && worker.level() instanceof ServerLevel) {
            ItemEntity drop = new ItemEntity(worker.level(), worker.getX(), worker.getY() + 0.3, worker.getZ(), rest);
            drop.setDefaultPickUpDelay();
            worker.level().addFreshEntity(drop);
        }
    }

    /** Takes one item matching {@code filter} from the container, else the nest. EMPTY if there is none. */
    public static ItemStack takeOne(PuffEntity puff, Level level, Predicate<ItemStack> filter) {
        IItemHandler h = handlerAt(level, puff.getSquad().storage());
        if (h != null) {
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack s = h.getStackInSlot(i);
                if (!s.isEmpty() && filter.test(s)) {
                    ItemStack got = h.extractItem(i, 1, false);
                    if (!got.isEmpty()) return got;
                }
            }
        }
        net.minecraft.world.SimpleContainer hoard = puff.getHoard();
        for (int i = 0; i < hoard.getContainerSize(); i++) {
            ItemStack s = hoard.getItem(i);
            if (!s.isEmpty() && filter.test(s)) {
                ItemStack got = s.split(1);
                hoard.setChanged();
                return got;
            }
        }
        return ItemStack.EMPTY;
    }

    /** True if the container or the nest holds at least one matching item (nothing is taken). */
    public static boolean has(PuffEntity puff, Level level, Predicate<ItemStack> filter) {
        IItemHandler h = handlerAt(level, puff.getSquad().storage());
        if (h != null) {
            for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty() && filter.test(h.getStackInSlot(i))) return true;
        }
        for (int i = 0; i < puff.getHoard().getContainerSize(); i++) {
            ItemStack s = puff.getHoard().getItem(i);
            if (!s.isEmpty() && filter.test(s)) return true;
        }
        return false;
    }
}
