package com.puffcompanion.registry;

import com.puffcompanion.PuffCompanion;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, PuffCompanion.MODID);

    // Milk-white base with sakura-pink spots
    public static final RegistryObject<Item> PUFF_SPAWN_EGG = ITEMS.register("puff_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.PUFF, 0xF7F1EC, 0xF4B6C2, new Item.Properties()));

    public static final RegistryObject<Item> CHEESE_CORE = ITEMS.register("cheese_core",
            () -> new com.puffcompanion.item.CheeseCoreItem(new Item.Properties()));

    public static final RegistryObject<Item> RAT_SPIRIT_BELL = ITEMS.register("rat_spirit_bell",
            () -> new com.puffcompanion.item.RatSpiritBellItem(new Item.Properties()));

    public static final RegistryObject<Item> CURIO_SLOT_EXPANDER = ITEMS.register("curio_slot_expander",
            () -> new com.puffcompanion.item.CurioSlotExpanderItem(new Item.Properties().rarity(net.minecraft.world.item.Rarity.RARE)));

    public static final RegistryObject<Item> LEADER_CHEESE_STAFF = ITEMS.register("leader_cheese_staff",
            () -> new com.puffcompanion.item.LeaderCheeseStaffItem(new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.RARE)));

    public static final RegistryObject<Item> RS_DEPOSIT_CHARM = ITEMS.register("rs_deposit_charm",
            () -> new com.puffcompanion.item.RsDepositCharmItem(new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.UNCOMMON)));

    public static final RegistryObject<Item> GOLDEN_CHEESE_NEST = ITEMS.register("golden_cheese_nest",
            () -> new net.minecraft.world.item.BlockItem(ModBlocks.GOLDEN_CHEESE_NEST.get(), new Item.Properties()));

    private ModItems() {}
}
