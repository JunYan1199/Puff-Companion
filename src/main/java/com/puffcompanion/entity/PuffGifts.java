package com.puffcompanion.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/** Five-tier gift classification (doc 03, part 2.2). */
public final class PuffGifts {
    public enum Tier {
        //          lang base         variants affection hunger hearts
        BEST(      "puff.gift.best",     3,     8,       40,    7),
        GREAT(     "puff.gift.great",    3,     4,        0,    3),
        GOOD(      "puff.gift.good",     2,     2,       20,    2),
        CAT_ITEM(  "puff.gift.cat_item", 2,     0,        0,    0),
        NORMAL(    "puff.gift.normal",   2,     1,        0,    0);

        public final String langBase;
        public final int variants;
        public final int affection;
        public final int hunger;
        public final int hearts;

        Tier(String langBase, int variants, int affection, int hunger, int hearts) {
            this.langBase = langBase;
            this.variants = variants;
            this.affection = affection;
            this.hunger = hunger;
            this.hearts = hearts;
        }
    }

    /** Shiny things + sweet berries. (1.20.1 has no copper nugget, so copper ingot / raw copper stand in.) */
    private static final Set<Item> GREAT = Set.of(
            Items.GOLD_NUGGET, Items.IRON_NUGGET, Items.COPPER_INGOT, Items.RAW_COPPER,
            Items.EMERALD, Items.LAPIS_LAZULI, Items.AMETHYST_SHARD, Items.GLOWSTONE_DUST, Items.SWEET_BERRIES);

    /** Bread, desserts and other treats (cooked_* foods are matched separately). */
    private static final Set<Item> GOOD_FOOD = Set.of(
            Items.BREAD, Items.CAKE, Items.COOKIE, Items.PUMPKIN_PIE, Items.HONEY_BOTTLE, Items.APPLE,
            Items.GOLDEN_APPLE, Items.GOLDEN_CARROT, Items.BAKED_POTATO, Items.MUSHROOM_STEW,
            Items.RABBIT_STEW, Items.BEETROOT_SOUP, Items.GLOW_BERRIES);

    private static final String[] CAT_WORDS = {"cod", "salmon", "tropical_fish", "pufferfish", "catnip"};

    private PuffGifts() {}

    public static Tier classify(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath();
        if (path.contains("cheese")) return Tier.BEST;               // rats:cheese, rats:blue_cheese, ...
        for (String w : CAT_WORDS) if (path.contains(w)) return Tier.CAT_ITEM;
        if (GREAT.contains(stack.getItem())) return Tier.GREAT;
        if (stack.is(ItemTags.FLOWERS) || GOOD_FOOD.contains(stack.getItem())
                || (path.startsWith("cooked_") && stack.isEdible())) return Tier.GOOD;
        return Tier.NORMAL;
    }
}
