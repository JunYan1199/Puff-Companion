package com.puffcompanion.registry;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.menu.PuffMaidMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, PuffCompanion.MODID);

    public static final RegistryObject<MenuType<PuffMaidMenu>> PUFF_MAID =
            MENUS.register("puff_maid", () -> IForgeMenuType.create(PuffMaidMenu::fromNetwork));

    public static final RegistryObject<MenuType<com.puffcompanion.menu.RsCharmMenu>> RS_CHARM =
            MENUS.register("rs_charm", () -> IForgeMenuType.create(com.puffcompanion.menu.RsCharmMenu::fromNetwork));

    private ModMenus() {}
}
