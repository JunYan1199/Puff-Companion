package com.puffcompanion.client.gui;

import com.puffcompanion.menu.RsCharmMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Cream x sakura filter editor of the RS deposit charm (ghost slots + whitelist / blacklist toggle). */
public class RsCharmScreen extends AbstractContainerScreen<RsCharmMenu> {
    private PuffUi.Button toggle;

    public RsCharmScreen(RsCharmMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = RsCharmMenu.INV_Y - 10;
    }

    @Override
    protected void init() {
        super.init();
        this.toggle = new PuffUi.Button(this.leftPos + 8, this.topPos + 58, 160, 13, Component.empty(), () -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
            }
        }, false);
        this.toggle.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.puffcompanion.rs_charm.hint")));
        this.addRenderableWidget(this.toggle);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean white = this.menu.isWhitelist();
        this.toggle.setMessage(Component.translatable(white ? "gui.puffcompanion.rs_charm.whitelist" : "gui.puffcompanion.rs_charm.blacklist"));
        this.toggle.selected = white;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int l = this.leftPos, t = this.topPos;
        PuffUi.panel(g, l, t, l + this.imageWidth, t + this.imageHeight);
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot s = this.menu.slots.get(i);
            PuffUi.slot(g, l + s.x, t + s.y);
            if (i < com.puffcompanion.item.RsDepositCharmItem.FILTER_SIZE) {
                g.fill(l + s.x, t + s.y, l + s.x + 16, t + s.y + 16, 0x30C9A6FF);   // ghost slots: soft lilac tint
            }
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, 8, 7, PuffUi.COL_TITLE, false);
        g.drawString(this.font, this.playerInventoryTitle, 8, this.inventoryLabelY, PuffUi.COL_TEXT, false);
    }
}
