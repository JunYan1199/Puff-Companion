package com.puffcompanion.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** What Puff considers "shiny little treasures" worth hoarding in her secret nest. */
public final class PuffHoard {
    public static final int SIZE = 18;   // 3 x 6; older 9-slot saves load into the first slots

    /** Explicit shinies. Nuggets / seeds / cheese are matched by tag or id below. */
    private static final Set<Item> SHINY = Set.of(
            Items.IRON_NUGGET, Items.GOLD_NUGGET, Items.RAW_COPPER, Items.COPPER_INGOT,
            Items.GLOWSTONE_DUST, Items.AMETHYST_SHARD, Items.EMERALD, Items.LAPIS_LAZULI, Items.SWEET_BERRIES);

    private PuffHoard() {}

    public static boolean isHoardable(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (SHINY.contains(stack.getItem())) return true;
        if (stack.is(Tags.Items.NUGGETS) || stack.is(Tags.Items.SEEDS)) return true;   // incl. modded copper nuggets
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.getPath().contains("cheese");
    }
}
