package com.puffcompanion.client.gui;

import com.puffcompanion.item.LeaderCheeseStaffItem;
import com.puffcompanion.network.PuffNetwork;
import com.puffcompanion.network.PuffStaffModePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Mode picker of the leader cheese staff: work area / rat joins squad / rat leaves squad. */
public class PuffStaffModeScreen extends Screen {
    private static final int W = 200, H = 128;
    private final InteractionHand hand;
    private int left, top;

    public PuffStaffModeScreen(InteractionHand hand) {
        super(Component.translatable("gui.puffcompanion.staff.title"));
        this.hand = hand;
    }

    @Override
    protected void init() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        ItemStack stack = Minecraft.getInstance().player == null ? ItemStack.EMPTY : Minecraft.getInstance().player.getItemInHand(this.hand);
        int current = LeaderCheeseStaffItem.mode(stack);
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            PuffUi.Button b = new PuffUi.Button(this.left + 16, this.top + 30 + m * 26, W - 32, 20,
                    LeaderCheeseStaffItem.modeName(m), () -> this.pick(mode), m == current);
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("gui.puffcompanion.staff.mode_tip." + new String[]{"area", "join", "leave"}[m])));
            this.addRenderableWidget(b);
        }
    }

    private void pick(int mode) {
        PuffNetwork.sendToServer(new PuffStaffModePacket(this.hand, mode));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        PuffUi.panel(g, this.left, this.top, this.left + W, this.top + H);
        g.drawCenteredString(this.font, this.title, this.left + W / 2, this.top + 10, PuffUi.COL_TITLE);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
