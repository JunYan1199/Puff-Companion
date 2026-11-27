package com.puffcompanion.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Gate for the optional Create soft dependency. Puff never touches Create classes: a Create schematic item
 * ({@code create:schematic}) only carries NBT ({@code File}, {@code Owner}, {@code Anchor}, {@code Rotation},
 * {@code Mirror}, {@code Deployed}) and the uploaded {@code .nbt} file is a vanilla structure template, which
 * {@link com.puffcompanion.compat.create.SchematicPlan} reads with vanilla code.
 */
public final class CreateCompat {
    public static final String MODID = "create";
    private static final ResourceLocation SCHEMATIC = new ResourceLocation(MODID, "schematic");

    private CreateCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** A Create schematic (blueprint) item? False without Create. */
    public static boolean isSchematic(ItemStack stack) {
        return isLoaded() && !stack.isEmpty() && SCHEMATIC.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }
}
