package com.puffcompanion.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.puffcompanion.client.PuffVoicePlayer;
import com.puffcompanion.entity.PuffCalls;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffExpression;
import com.puffcompanion.network.PuffNetwork;
import com.puffcompanion.network.PuffStoryChoicePacket;
import com.puffcompanion.story.PuffStory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Local Galgame dialogue screen: cream card with sakura-pink border and mouse-ear name tab, typewriter
 * text, click/Space/Enter to reveal the whole line or advance, and choice cards. The screen never changes
 * game state itself; choices are sent to the server ({@link PuffStoryChoicePacket}) which settles them.
 */
public class PuffDialogueScreen extends Screen {
    private static final float CHARS_PER_TICK = 1.6F;
    /** Fast-forward: one line every N ticks. */
    private static final int FAST_INTERVAL = 2;

    /** Auto mode is remembered between stories (like most visual novels). */
    private static boolean autoMode;

    // Palette shared with the head bubble
    private static final int COL_SHADOW = 0x552B1922;
    private static final int COL_BORDER = 0xFFF2A0B8;
    private static final int COL_BG = 0xFFFFFDF8;
    private static final int COL_HIGHLIGHT = 0xFFFFFFFF;
    private static final int COL_BOTTOM_TINT = 0xFFFCE8EE;
    private static final int COL_EAR_INNER = 0xFFFAD2DF;
    private static final int COL_TEXT = 0xFF4A332D;
    private static final int COL_ACTION = 0xFF9C7E76;
    private static final int COL_ACCENT = 0xFFD9668A;
    private static final int COL_HOVER = 0xFFFCE8EE;

    private static final int CARD_MAX_W = 380;
    private static final int PAD = 10;
    private static final int LINE_H = 10;

    private final PuffEntity puff;
    private final String storyKey;
    private final PuffStory story;

    private String nodeId;
    private int lineIndex;
    private float shown;
    private boolean choosing;
    private boolean finished;
    private int ticks;
    private boolean fastMode;
    private boolean skipping;
    /** Ticks since the current line finished typing (drives auto mode). */
    private int idleTicks;

    // cached resolved text of the current line
    @Nullable private String curText;
    @Nullable private String curAction;

    public PuffDialogueScreen(PuffEntity puff, String storyKey, PuffStory story) {
        super(Component.translatable(story.titleKey()));
        this.puff = puff;
        this.storyKey = storyKey;
        this.story = story;
        this.nodeId = story.start();
        this.puff.clearBubble();
        this.startLine();
    }

    // ---------------- Story flow ----------------

    @Nullable
    private PuffStory.Node node() {
        return this.story.node(this.nodeId);
    }

    @Nullable
    private PuffStory.Line line() {
        PuffStory.Node n = this.node();
        return n == null || this.lineIndex >= n.lines().size() ? null : n.lines().get(this.lineIndex);
    }

    private void startLine() {
        if (!this.skipping) {
            PuffVoicePlayer.stop();
        }
        PuffStory.Line line = this.line();
        this.shown = 0;
        this.idleTicks = 0;
        if (line == null) {
            // Keep the previous line on the card (fully shown) while choices / the end hint are up
            if (this.curText != null) this.shown = this.curText.length();
            this.endOfNode();
            return;
        }
        this.curText = this.resolve(line.text());
        this.curAction = line.action() != null ? this.resolve(line.action()) : null;
        if (line.expression() != null && !line.expression().isEmpty()) {
            String expr = PuffExpression.normalizeExpression(line.expression());
            String ear = (line.ear() != null && !line.ear().isEmpty())
                    ? PuffExpression.normalizeEar(line.ear())
                    : PuffExpression.defaultEarFor(expr);
            this.puff.setDialogueExpression(expr, ear);
        } else if (line.ear() != null && !line.ear().isEmpty()) {
            this.puff.setDialogueExpression(PuffExpression.NEUTRAL, PuffExpression.normalizeEar(line.ear()));
        }
        if (line.anim() != null && !line.anim().isEmpty()) {
            this.puff.triggerAnim(PuffEntity.ACTION_CONTROLLER, line.anim());
        }
        if (!this.skipping && PuffStory.SPEAKER_PUFF.equals(line.speaker())) {
            PuffVoicePlayer.playStory(this.puff, line.text());
        }
    }

    private void endOfNode() {
        PuffStory.Node n = this.node();
        if (n != null && !n.choices().isEmpty()) {
            this.choosing = true;
            this.refreshWidgets();
        } else if (n != null && n.next() != null) {
            this.enterNode(n.next());
        } else {
            this.finish();
        }
    }

    private void enterNode(String id) {
        this.nodeId = id;
        this.lineIndex = 0;
        this.choosing = false;
        this.refreshWidgets();
        this.startLine();
    }

    private void finish() {
        if (this.finished) return;
        this.finished = true;
        this.choosing = false;
        PuffNetwork.sendToServer(new PuffStoryChoicePacket(this.puff.getId(), this.storyKey, this.nodeId, -1));
        this.refreshWidgets();
    }

    private void choose(int index) {
        PuffStory.Node n = this.node();
        if (!this.choosing || n == null || index >= n.choices().size()) return;
        PuffNetwork.sendToServer(new PuffStoryChoicePacket(this.puff.getId(), this.storyKey, this.nodeId, index));
        String next = n.choices().get(index).next();
        if (next != null) {
            this.enterNode(next);
        } else {
            this.finish();
        }
    }

    /** Click / Space / Enter: reveal the full line, otherwise advance; close once finished. */
    private void advance() {
        if (this.finished) {
            this.onClose();
            return;
        }
        if (this.choosing || this.curText == null) return;
        if (this.shown < this.curText.length()) {
            this.shown = this.curText.length();
            return;
        }
        this.lineIndex++;
        this.startLine();
    }

    /**
     * Skip: run through every remaining line up to the next choice (or the end of the story). Nodes are still
     * entered in order, so animations and the server-side settlement stay identical to reading normally.
     */
    private void skipToChoice() {
        if (this.finished || this.choosing) return;
        this.skipping = true;
        try {
            for (int guard = 0; guard < 512 && !this.choosing && !this.finished; guard++) {
                PuffStory.Node n = this.node();
                if (n == null) {
                    this.finish();
                    break;
                }
                if (!n.lines().isEmpty() && this.lineIndex < n.lines().size()) {
                    this.lineIndex = n.lines().size() - 1;   // show the node's last line while choosing
                    this.startLine();
                    if (this.curText != null) this.shown = this.curText.length();
                }
                this.lineIndex = Math.max(this.lineIndex + 1, n.lines().size());
                this.startLine();
            }
        } finally {
            this.skipping = false;
        }
        this.fastMode = false;
        PuffVoicePlayer.stop();
        if (!this.finished) {
            PuffStory.Node curNode = this.node();
            if (curNode != null && !curNode.lines().isEmpty()) {
                PuffStory.Line lastLine = curNode.lines().get(curNode.lines().size() - 1);
                if (PuffStory.SPEAKER_PUFF.equals(lastLine.speaker())) {
                    PuffVoicePlayer.playStory(this.puff, lastLine.text());
                }
            }
        }
    }

    /** Ticks a finished line waits in auto mode: a base pause plus reading time for longer lines. */
    private int autoDelay() {
        int len = this.curText == null ? 0 : this.curText.length();
        return 24 + Math.min(80, len * 2);
    }

    private String resolve(String key) {
        String player = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getName().getString()
                : net.minecraft.client.Minecraft.getInstance().player != null
                ? net.minecraft.client.Minecraft.getInstance().player.getName().getString() : "";
        String callKey = PuffCalls.callKey(this.puff.getGrowthStage(), this.puff.getCallMode());
        String call = callKey == null ? player : I18n.get(callKey);
        String sibling = I18n.get(PuffCalls.siblingKey(this.puff.getCallMode()));
        return PuffCalls.format(I18n.get(key), player, call, sibling);
    }

    /** Rebuild choice buttons, but only once the screen has been initialised. */
    private void refreshWidgets() {
        if (this.minecraft != null) this.rebuildWidgets();
    }

    // ---------------- Screen plumbing ----------------

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** No skipping the story with Esc before it is settled. */
    @Override
    public boolean shouldCloseOnEsc() {
        return this.finished;
    }

    @Override
    public void removed() {
        PuffVoicePlayer.stop();
        this.puff.clearDialogueExpression();
        super.removed();
    }

    @Override
    public void tick() {
        this.ticks++;
        if (!this.puff.isAlive()) {
            this.onClose();
            return;
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            this.puff.faceEntityClient(this.minecraft.player);
        }
        boolean fast = this.fastMode || Screen.hasControlDown();   // hold Ctrl = fast-forward
        if (this.curText != null && this.shown < this.curText.length()) {
            this.shown = fast ? this.curText.length() : Math.min(this.curText.length(), this.shown + CHARS_PER_TICK);
        }
        if (this.finished || this.choosing || this.curText == null || this.shown < this.curText.length()) {
            if (this.choosing || this.finished) this.fastMode = false;   // fast-forward always stops at choices
            return;
        }
        this.idleTicks++;
        boolean voiceHold = !fast && autoMode && PuffVoicePlayer.isPlaying() && this.idleTicks < 20 * 18;
        if (!voiceHold && (fast ? this.idleTicks >= FAST_INTERVAL : autoMode && this.idleTicks >= this.autoDelay())) {
            this.advance();
        }
    }

    @Override
    protected void init() {
        // Visual-novel controls, top-right above the card: Auto / Fast-forward / Skip
        int cw = 34, ch = 12, cg = 3;
        int x1 = (this.width + this.cardWidth()) / 2;
        int by = this.cardTop() - ch - 4;
        int bx = x1 - 3 * cw - 2 * cg;
        ControlButton auto = new ControlButton(bx, by, cw, ch, Component.translatable("gui.puffcompanion.story.auto"),
                () -> autoMode = !autoMode, () -> autoMode);
        ControlButton fast = new ControlButton(bx + cw + cg, by, cw, ch, Component.translatable("gui.puffcompanion.story.fast"),
                () -> this.fastMode = !this.fastMode, () -> this.fastMode || Screen.hasControlDown());
        ControlButton skip = new ControlButton(bx + 2 * (cw + cg), by, cw, ch, Component.translatable("gui.puffcompanion.story.skip"),
                this::skipToChoice, () -> false);
        auto.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.puffcompanion.story.auto.tooltip")));
        fast.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.puffcompanion.story.fast.tooltip")));
        skip.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.puffcompanion.story.skip.tooltip")));
        skip.active = !this.finished && !this.choosing;
        fast.active = !this.finished;
        this.addRenderableWidget(auto);
        this.addRenderableWidget(fast);
        this.addRenderableWidget(skip);

        if (!this.choosing) return;
        PuffStory.Node n = this.node();
        if (n == null) return;
        int cardTop = this.cardTop();
        int bw = Math.min(this.cardWidth() - 60, 300);
        int bh = 20;
        int gap = 5;
        int total = n.choices().size() * (bh + gap) - gap;
        int y = cardTop - 22 - 16 - total;   // leave room for the control row
        for (int i = 0; i < n.choices().size(); i++) {
            final int index = i;
            Component label = Component.literal(this.resolve(n.choices().get(i).text()));
            this.addRenderableWidget(new ChoiceButton((this.width - bw) / 2, y + i * (bh + gap), bw, bh, label,
                    () -> this.choose(index)));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            this.advance();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_SPACE || keyCode == InputConstants.KEY_RETURN
                || keyCode == InputConstants.KEY_NUMPADENTER) {
            this.advance();
            return true;
        }
        if (keyCode == InputConstants.KEY_A) {
            autoMode = !autoMode;
            return true;
        }
        if (keyCode == InputConstants.KEY_S && !this.choosing) {
            this.skipToChoice();
            return true;
        }
        if (this.choosing && keyCode >= InputConstants.KEY_1 && keyCode <= InputConstants.KEY_9) {
            this.choose(keyCode - InputConstants.KEY_1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------- Layout ----------------

    private int cardWidth() {
        return Math.min(this.width - 24, CARD_MAX_W);
    }

    private int textWidth() {
        return this.cardWidth() - PAD * 2;
    }

    /** Card height is based on the full line (not the typed part) so it never jumps while typing. */
    private int cardHeight() {
        int lines = 0;
        if (this.curAction != null) lines += this.font.split(Component.literal(this.curAction), this.textWidth()).size();
        if (this.curText != null) lines += this.font.split(Component.literal(this.curText), this.textWidth()).size();
        int body = Math.max(3, lines) * LINE_H + (this.curAction != null ? 3 : 0);
        return body + PAD * 2 + 4 + LINE_H;   // extra row for the ▼ / end / choose hint
    }

    private int cardTop() {
        return this.height - this.cardHeight() - 12;
    }

    // ---------------- Rendering ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Soft vignette at the bottom so the card reads over any scenery
        g.fillGradient(0, this.height / 2, this.width, this.height, 0x00000000, 0x99000000);

        int w = this.cardWidth();
        int h = this.cardHeight();
        int x0 = (this.width - w) / 2;
        int y0 = this.height - h - 12;
        int x1 = x0 + w;
        int y1 = y0 + h;

        this.renderTitle(g);

        // Card: shadow, border, body, highlight, bottom tint
        roundedBox(g, x0 + 1, y0 + 2, x1 + 1, y1 + 2, COL_SHADOW);
        roundedBox(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, COL_BORDER);
        roundedBox(g, x0, y0, x1, y1, COL_BG);
        g.fill(x0 + 2, y0, x1 - 2, y0 + 1, COL_HIGHLIGHT);
        g.fill(x0 + 2, y1 - 2, x1 - 2, y1, COL_BOTTOM_TINT);

        PuffStory.Line line = this.line();
        boolean narrator = line != null && PuffStory.SPEAKER_NARRATOR.equals(line.speaker());

        // Name tab with mouse ears
        if (line != null && !narrator) {
            Component name = this.speakerName(line);
            Component expr = line.expression() != null
                    ? Component.translatable("puff.expression." + line.expression()) : null;
            int nameW = this.font.width(name) + (expr != null ? this.font.width(" · ") + this.font.width(expr) : 0);
            int tx0 = x0 + 12, ty0 = y0 - 15, tx1 = tx0 + nameW + 16, ty1 = y0 + 2;
            roundedBox(g, tx0 - 1, ty0 - 1, tx1 + 1, ty1, COL_BORDER);
            roundedBox(g, tx0, ty0, tx1, ty1, COL_BG);
            if (PuffStory.SPEAKER_PUFF.equals(line.speaker())) {
                ears(g, tx0 + 4, ty0, tx1 - 4);
            }
            g.drawString(this.font, name, tx0 + 8, ty0 + 4, COL_TEXT, false);
            if (expr != null) {
                int ex = tx0 + 8 + this.font.width(name);
                g.drawString(this.font, " · ", ex, ty0 + 4, COL_ACTION, false);
                g.drawString(this.font, expr, ex + this.font.width(" · "), ty0 + 4, COL_ACCENT, false);
            }
        }

        // Body text
        int ty = y0 + PAD + 2;
        if (this.curAction != null) {
            for (FormattedCharSequence seq : this.font.split(Component.literal(this.curAction), this.textWidth())) {
                g.drawString(this.font, seq, x0 + PAD, ty, COL_ACTION, false);
                ty += LINE_H;
            }
            ty += 3;
        }
        if (this.curText != null) {
            String typed = this.curText.substring(0, Mth.clamp((int) this.shown, 0, this.curText.length()));
            int color = narrator ? COL_ACTION : COL_TEXT;
            for (FormattedCharSequence seq : this.font.split(Component.literal(typed), this.textWidth())) {
                g.drawString(this.font, seq, x0 + PAD, ty, color, false);
                ty += LINE_H;
            }
        }

        // Prompt indicator / end hint
        boolean lineDone = this.curText != null && this.shown >= this.curText.length();
        if (this.finished) {
            Component end = Component.translatable("gui.puffcompanion.story.end");
            g.drawString(this.font, end, x1 - PAD - this.font.width(end), y1 - PAD - 4, COL_ACCENT, false);
        } else if (lineDone && !this.choosing) {
            int bob = (this.ticks / 6) % 2;
            g.drawString(this.font, "▼", x1 - PAD - 6, y1 - PAD - 4 + bob, COL_ACCENT, false);
        } else if (this.choosing) {
            Component hint = Component.translatable("gui.puffcompanion.story.choose");
            g.drawString(this.font, hint, x1 - PAD - this.font.width(hint), y1 - PAD - 4, COL_ACCENT, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderTitle(GuiGraphics g) {
        Component title = this.getTitle();
        int tw = this.font.width(title) + 20;
        int x0 = (this.width - tw) / 2, y0 = 10, x1 = x0 + tw, y1 = y0 + 15;
        roundedBox(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, COL_BORDER);
        roundedBox(g, x0, y0, x1, y1, COL_BG);
        ears(g, x0 + 4, y0, x1 - 4);
        g.drawCenteredString(this.font, title, this.width / 2, y0 + 4, COL_ACCENT);
    }

    private Component speakerName(PuffStory.Line line) {
        if (PuffStory.SPEAKER_PLAYER.equals(line.speaker()) && this.minecraft != null && this.minecraft.player != null) {
            return this.minecraft.player.getName();
        }
        if (PuffStory.SPEAKER_PUFF.equals(line.speaker())) {
            return this.puff.getDisplayName();
        }
        return Component.translatable("puff.speaker." + line.speaker());
    }

    /** 1px-cornered rounded rectangle. */
    private static void roundedBox(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0 + 2, y0, x1 - 2, y1, color);
        g.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 1, color);
        g.fill(x0, y0 + 2, x0 + 1, y1 - 2, color);
        g.fill(x1 - 2, y0 + 1, x1 - 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 2, x1, y1 - 2, color);
    }

    /** Two little round mouse ears poking out of the top edge at x-positions {@code left} and {@code right}. */
    private static void ears(GuiGraphics g, int left, int top, int right) {
        for (int cx : new int[]{left + 3, right - 3}) {
            g.fill(cx - 3, top - 4, cx + 3, top + 1, COL_BORDER);
            g.fill(cx - 2, top - 5, cx + 2, top - 4, COL_BORDER);
            g.fill(cx - 2, top - 3, cx + 2, top, COL_EAR_INNER);
        }
    }

    /** Small pill toggle for Auto / Fast-forward / Skip; lit (accent) while its mode is on. */
    private static final class ControlButton extends AbstractButton {
        private final Runnable action;
        private final java.util.function.BooleanSupplier lit;

        ControlButton(int x, int y, int w, int h, Component message, Runnable action, java.util.function.BooleanSupplier lit) {
            super(x, y, w, h, message);
            this.action = action;
            this.lit = lit;
        }

        @Override
        public void onPress() {
            this.action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x0 = this.getX(), y0 = this.getY(), x1 = x0 + this.width, y1 = y0 + this.height;
            boolean on = this.lit.getAsBoolean();
            boolean hot = this.active && this.isHoveredOrFocused();
            roundedBox(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, on || hot ? COL_ACCENT : COL_BORDER);
            roundedBox(g, x0, y0, x1, y1, on ? COL_ACCENT : hot ? COL_HOVER : COL_BG);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            int color = !this.active ? 0xFFC8B4AE : on ? 0xFFFFFFFF : COL_TEXT;
            String text = (on ? "▶ " : "") + this.getMessage().getString();
            g.drawString(font, text, (x0 + x1) / 2 - font.width(text) / 2, y0 + (this.height - 8) / 2 + 1, color, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    /** Choice card in the same cream/sakura style. */
    private static final class ChoiceButton extends AbstractButton {
        private final Runnable action;

        ChoiceButton(int x, int y, int w, int h, Component message, Runnable action) {
            super(x, y, w, h, message);
            this.action = action;
        }

        @Override
        public void onPress() {
            this.action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x0 = this.getX(), y0 = this.getY(), x1 = x0 + this.width, y1 = y0 + this.height;
            boolean hot = this.isHoveredOrFocused();
            roundedBox(g, x0 + 1, y0 + 2, x1 + 1, y1 + 2, COL_SHADOW);
            roundedBox(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, hot ? COL_ACCENT : COL_BORDER);
            roundedBox(g, x0, y0, x1, y1, hot ? COL_HOVER : COL_BG);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            String marker = hot ? "♥ " : "♡ ";
            Component label = Component.literal(marker).append(this.getMessage());
            int tw = font.width(label);
            g.drawString(font, label, (x0 + x1) / 2 - tw / 2, y0 + (this.height - 8) / 2, COL_TEXT, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
