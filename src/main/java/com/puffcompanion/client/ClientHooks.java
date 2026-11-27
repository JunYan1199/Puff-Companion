package com.puffcompanion.client;

import com.puffcompanion.client.gui.PuffInteractionScreen;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.client.Minecraft;

/** Client-only entry points called from common code through DistExecutor. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openStaffModeScreen(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) mc.setScreen(new com.puffcompanion.client.gui.PuffStaffModeScreen(hand));
    }

    public static void openInteractionScreen(PuffEntity puff) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new PuffInteractionScreen(puff));
        }
    }
}
