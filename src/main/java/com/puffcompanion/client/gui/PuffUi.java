package com.puffcompanion.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Shared cream x sakura drawing helpers and button for Puff's small screens (stage 6). */
public final class PuffUi {
    public static final int COL_SHADOW = 0x451D1218;
    public static final int COL_BG_TOP = 0xFAFFFDF9;
    public static final int COL_BG_BOT = 0xFAFFF1F5;
    public static final int COL_BORDER = 0xFFF2A0B8;
    public static final int COL_ACCENT = 0xFFF08CA8;
    public static final int COL_TEXT = 0xFF4A332D;
    public static final int COL_DIM = 0xFF9C848B;
    public static final int COL_TITLE = 0xFF543339;
    public static final int COL_SLOT_BG = 0xFFFFF6F8;
    public static final int COL_SLOT_BORDER = 0xFFEBC2CF;

    private PuffUi() {}

    /** Cream panel with soft shadow and sakura outline. */
    public static void panel(GuiGraphics g, int l, int t, int r, int b) {
        g.fill(l + 3, t + 3, r + 2, b + 3, COL_SHADOW);
        g.fillGradient(l + 1, t + 1, r - 1, b - 1, COL_BG_TOP, COL_BG_BOT);
        outline(g, l, t, r, b, COL_BORDER);
    }

    public static void outline(GuiGraphics g, int l, int t, int r, int b, int col) {
        g.fill(l + 1, t, r - 1, t + 1, col);
        g.fill(l + 1, b - 1, r - 1, b, col);
        g.fill(l, t + 1, l + 1, b - 1, col);
        g.fill(r - 1, t + 1, r, b - 1, col);
    }

    /** 18 x 18 slot frame around an item position (x, y = item corner). */
    public static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, COL_SLOT_BORDER);
        g.fill(x, y, x + 16, y + 16, COL_SLOT_BG);
    }

    /** Pink rounded button; {@code selected} draws it highlighted. */
    public static class Button extends AbstractButton {
        private final Runnable action;
        public boolean selected;

        public Button(int x, int y, int w, int h, Component msg, Runnable action, boolean selected) {
            super(x, y, w, h, msg);
            this.action = action;
            this.selected = selected;
        }

        @Override
        public void onPress() {
            this.action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int l = this.getX(), t = this.getY(), r = l + this.width, b = t + this.height;
            int top, bot, border, text;
            if (!this.active) {
                top = 0xE8F3ECEF; bot = 0xE8EAE0E4; border = 0xFFD9C7CE; text = 0xFFA29298;
            } else if (this.selected) {
                top = 0xFAFFE3EC; bot = 0xFAFCC8D8; border = 0xFFE87298; text = 0xFF6B2A3E;
            } else if (this.isHoveredOrFocused()) {
                top = 0xFAFFECF2; bot = 0xFAFDD6E2; border = 0xFFEB7D9F; text = 0xFF5A2A37;
            } else {
                top = 0xF2FFFFFF; bot = 0xF2FFF7FA; border = 0xFFF0BACB; text = COL_TEXT;
            }
            g.fillGradient(l + 1, t + 1, r - 1, b - 1, top, bot);
            outline(g, l, t, r, b, border);
            Font font = Minecraft.getInstance().font;
            String s = font.plainSubstrByWidth(this.getMessage().getString(), this.width - 4);
            g.drawString(font, s, l + (this.width - font.width(s)) / 2, t + (this.height - 8) / 2, text, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            this.defaultButtonNarrationText(out);
        }
    }
}
