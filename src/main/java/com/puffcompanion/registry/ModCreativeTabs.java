package com.puffcompanion.registry;

import com.puffcompanion.PuffCompanion;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Puff Companion's own creative tab: every item of the mod, in registration order (new items join automatically). */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PuffCompanion.MODID);

    public static final RegistryObject<CreativeModeTab> PUFF = TABS.register("puff_companion", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.puffcompanion"))
            .icon(() -> new ItemStack(ModItems.CHEESE_CORE.get()))
            .displayItems((params, output) -> ModItems.ITEMS.getEntries().forEach(item -> output.accept(item.get())))
            .build());

    private ModCreativeTabs() {}
}
