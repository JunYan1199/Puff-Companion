package com.puffcompanion.client.gui;

import com.puffcompanion.compat.CuriosCompat;
import com.puffcompanion.network.PuffCurioExpandPacket;
import com.puffcompanion.network.PuffNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Locale;

/**
 * Slot picker of the star accessory expander (cream x sakura palette): a scrollable list of Curios slot types
 * with their localized names and "current -> after" slot counts; confirm sends the choice to the server.
 */
public class PuffCurioExpandScreen extends Screen {
    private static final int COL_SHADOW = 0x451D1218;
    private static final int COL_BG_TOP = 0xFAFFFDF9;
    private static final int COL_BG_BOT = 0xFAFFF1F5;
    private static final int COL_BORDER = 0xFFF2A0B8;
    private static final int COL_ACCENT = 0xFFF08CA8;
    private static final int COL_TEXT = 0xFF4A332D;
    private static final int COL_DIM = 0xFF9C848B;
    private static final int COL_TITLE = 0xFF543339;
    private static final int COL_ROW = 0xF2FFFFFF;
    private static final int COL_ROW_HOVER = 0xFAFFECF2;
    private static final int COL_ROW_SEL = 0xFAFCC8D8;
    private static final int COL_ROW_SEL_BORDER = 0xFFE87298;

    private static final int W = 236;
    private static final int ROW_H = 16;
    private static final int VISIBLE = 9;

    private final int entityId;
    private final List<CuriosCompat.SlotGroup> slots;
    private int selected = -1;
    private int scroll;
    private int left, top, listTop, listH;
    private PanelButton confirm;

    public PuffCurioExpandScreen(int entityId, List<CuriosCompat.SlotGroup> slots) {
        super(Component.translatable("gui.puffcompanion.expand.title"));
        this.entityId = entityId;
        this.slots = List.copyOf(slots);
    }

    /** Localized slot name: Curios' own lang key, else a formatted id ("hands" -> "Hands"). */
    public static String slotName(String id) {
        String key = "curios.identifier." + id;
        if (I18n.exists(key)) return I18n.get(key);
        String[] parts = id.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.substring(0, 1).toUpperCase(Locale.ROOT)).append(p.substring(1));
        }
        return sb.toString();
    }

    @Override
    protected void init() {
        int rows = Math.min(VISIBLE, Math.max(1, this.slots.size()));
        this.listH = rows * ROW_H;
        int h = 44 + this.listH + 30;
        this.left = (this.width - W) / 2;
        this.top = (this.height - h) / 2;
        this.listTop = this.top + 40;
        int by = this.listTop + this.listH + 8;
        this.confirm = new PanelButton(this.left + W / 2 - 84, by, 80, 16,
                Component.translatable("gui.puffcompanion.expand.confirm"), this::confirm, true);
        this.confirm.active = this.selected >= 0;
        this.addRenderableWidget(this.confirm);
        this.addRenderableWidget(new PanelButton(this.left + W / 2 + 4, by, 80, 16,
                Component.translatable("gui.puffcompanion.expand.cancel"), this::onClose, false));
    }

    private void confirm() {
        if (this.selected < 0 || this.selected >= this.slots.size()) return;
        PuffNetwork.sendToServer(new PuffCurioExpandPacket(this.entityId, this.slots.get(this.selected).id()));
        this.onClose();
    }

    private int maxScroll() {
        return Math.max(0, this.slots.size() - VISIBLE);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        this.scroll = Mth.clamp(this.scroll - (int) Math.signum(delta), 0, this.maxScroll());
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int row = this.rowAt(mx, my);
        if (button == 0 && row >= 0) {
            this.selected = row;
            this.confirm.active = true;
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.2F));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private int rowAt(double mx, double my) {
        if (mx < this.left + 10 || mx >= this.left + W - 14 || my < this.listTop || my >= this.listTop + this.listH) return -1;
        int i = this.scroll + (int) ((my - this.listTop) / ROW_H);
        return i < this.slots.size() ? i : -1;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int l = this.left, t = this.top, r = l + W, b = this.listTop + this.listH + 32;
        fillRounded(g, l + 2, t + 3, r + 2, b + 3, COL_SHADOW);
        g.fillGradient(l + 1, t + 1, r - 1, b - 1, COL_BG_TOP, COL_BG_BOT);
        outline(g, l, t, r, b, COL_BORDER);
        Font font = this.font;
        g.drawCenteredString(font, this.title, l + W / 2, t + 8, COL_TITLE);
        g.drawString(font, Component.translatable("gui.puffcompanion.expand.hint"), l + 10, t + 24, COL_DIM, false);
        g.fill(l + 10, this.listTop - 3, r - 10, this.listTop - 2, 0x60F2A0B8);

        int hovered = this.rowAt(mouseX, mouseY);
        for (int k = 0; k < VISIBLE; k++) {
            int i = this.scroll + k;
            if (i >= this.slots.size()) break;
            CuriosCompat.SlotGroup sg = this.slots.get(i);
            int y = this.listTop + k * ROW_H;
            int x0 = l + 10, x1 = r - 14;
            boolean sel = i == this.selected;
            g.fill(x0, y + 1, x1, y + ROW_H - 1, sel ? COL_ROW_SEL : i == hovered ? COL_ROW_HOVER : COL_ROW);
            if (sel) outline(g, x0, y + 1, x1, y + ROW_H - 1, COL_ROW_SEL_BORDER);
            Component text = Component.translatable("gui.puffcompanion.expand.row", slotName(sg.id()), sg.count(), sg.count() + 1);
            g.drawString(font, font.plainSubstrByWidth(text.getString(), x1 - x0 - 8), x0 + 4, y + 4, sel ? 0xFF6B2A3E : COL_TEXT, false);
        }
        if (this.slots.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("message.puffcompanion.curio_expand.no_slots"), l + W / 2, this.listTop + 4, COL_DIM);
        }
        // scrollbar
        if (this.maxScroll() > 0) {
            int sx = r - 11, sy0 = this.listTop, sy1 = this.listTop + this.listH;
            g.fill(sx, sy0, sx + 3, sy1, 0x40F2A0B8);
            int thumb = Math.max(10, this.listH * VISIBLE / this.slots.size());
            int ty = sy0 + (this.listH - thumb) * this.scroll / this.maxScroll();
            g.fill(sx, ty, sx + 3, ty + thumb, COL_ACCENT);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void fillRounded(GuiGraphics g, int l, int t, int r, int b, int col) {
        g.fill(l + 1, t, r - 1, b, col);
        g.fill(l, t + 1, l + 1, b - 1, col);
        g.fill(r - 1, t + 1, r, b - 1, col);
    }

    private static void outline(GuiGraphics g, int l, int t, int r, int b, int col) {
        g.fill(l + 1, t, r - 1, t + 1, col);
        g.fill(l + 1, b - 1, r - 1, b, col);
        g.fill(l, t + 1, l + 1, b - 1, col);
        g.fill(r - 1, t + 1, r, b - 1, col);
    }

    private static final class PanelButton extends AbstractButton {
        private final Runnable action;
        private final boolean primary;

        PanelButton(int x, int y, int w, int h, Component msg, Runnable action, boolean primary) {
            super(x, y, w, h, msg);
            this.action = action;
            this.primary = primary;
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
            } else if (this.isHoveredOrFocused()) {
                top = 0xFAFFECF2; bot = 0xFAFDD6E2; border = 0xFFEB7D9F; text = 0xFF5A2A37;
            } else if (this.primary) {
                top = 0xFAFFE3EC; bot = 0xFAFCC8D8; border = 0xFFE87298; text = 0xFF6B2A3E;
            } else {
                top = 0xF2FFFFFF; bot = 0xF2FFF7FA; border = 0xFFF0BACB; text = COL_TEXT;
            }
            g.fillGradient(l + 1, t + 1, r - 1, b - 1, top, bot);
            outline(g, l, t, r, b, border);
            Font font = Minecraft.getInstance().font;
            g.drawString(font, this.getMessage(), l + (this.width - font.width(this.getMessage())) / 2, t + (this.height - 8) / 2, text, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            this.defaultButtonNarrationText(out);
        }
    }
}
