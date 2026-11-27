package com.puffcompanion.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * Gate for the optional Refined Storage soft dependency; RS classes are only touched in
 * {@link com.puffcompanion.compat.rs.RsImpl}, loaded only when {@code refinedstorage} is installed.
 */
public final class RsCompat {
    public static final String MODID = "refinedstorage";

    private RsCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** Is there a running RS network at (controller / grid / any network node)? */
    public static boolean hasNetwork(ServerLevel level, BlockPos pos) {
        return isLoaded() && com.puffcompanion.compat.rs.RsImpl.hasNetwork(level, pos);
    }

    /**
     * Inserts {@code stack} into the network at {@code pos}; returns what did NOT fit (the whole stack when the
     * network is missing, unloaded or offline). The caller keeps the remainder, so nothing is ever lost.
     */
    public static ItemStack insert(ServerLevel level, BlockPos pos, ItemStack stack) {
        return isLoaded() ? com.puffcompanion.compat.rs.RsImpl.insert(level, pos, stack) : stack;
    }
}
