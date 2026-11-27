package com.puffcompanion.client;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.client.render.PuffRenderer;
import com.puffcompanion.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.puffcompanion.registry.ModMenus.PUFF_MAID.get(), com.puffcompanion.client.gui.PuffMaidScreen::new);
            net.minecraft.client.gui.screens.MenuScreens.register(
                    com.puffcompanion.registry.ModMenus.RS_CHARM.get(), com.puffcompanion.client.gui.RsCharmScreen::new);
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.PUFF.get(), PuffRenderer::new);
    }
}
