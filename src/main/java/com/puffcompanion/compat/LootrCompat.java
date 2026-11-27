package com.puffcompanion.compat;

import com.puffcompanion.entity.PuffEntity;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Gate for the optional Lootr soft dependency; Lootr classes are only touched in
 * {@link com.puffcompanion.compat.lootr.LootrImpl}, which loads only when {@code lootr} is installed.
 */
public final class LootrCompat {
    public static final String MODID = "lootr";

    private LootrCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** Nearest Lootr container within {@code radius} that Puff has not opened yet. */
    @Nullable
    public static BlockPos findNear(PuffEntity puff, int radius) {
        return isLoaded() ? com.puffcompanion.compat.lootr.LootrImpl.findNear(puff, radius) : null;
    }

    /** Opens Puff's own share of the container into her nest; item count, or -1 if it is not a Lootr container. */
    public static int open(PuffEntity puff, BlockPos pos) {
        return isLoaded() ? com.puffcompanion.compat.lootr.LootrImpl.open(puff, pos) : -1;
    }
}
