package com.puffcompanion.compat.attributeslib.client;

import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.network.PuffStatsPacket;
import dev.shadowsoffire.attributeslib.ALConfig;
import dev.shadowsoffire.attributeslib.AttributesLib;
import dev.shadowsoffire.attributeslib.api.IFormattableAttribute;
import dev.shadowsoffire.attributeslib.client.AttributeModifierComponent;
import dev.shadowsoffire.attributeslib.client.AttributesGui;
import dev.shadowsoffire.attributeslib.client.ModifierSource;
import dev.shadowsoffire.attributeslib.client.ModifierSourceType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ApothicAttributes-style attribute side panel for Puff. AL's own {@link AttributesGui} is hard-wired to the
 * player's {@code InventoryScreen} and {@code Minecraft.player}, so it cannot show another entity. This panel
 * reuses AL's texture, value formatting ({@link IFormattableAttribute}), modifier sources
 * ({@link ModifierSourceType#extract}) and tooltip rows ({@link AttributeModifierComponent}), fed with a server
 * snapshot of Puff's real attribute instances (client copies of most attributes are not synced by vanilla).
 */
public class PuffAttributesPanel {
    public static final ResourceLocation TEXTURE = AttributesGui.TEXTURES;
    public static final int WIDTH = 131, HEIGHT = 166, ENTRY_HEIGHT = 22, MAX_ENTRIES = 6;
    private static final int LIST_Y = 16, SCROLL_X = 111, SCROLL_H = 132;

    // Static so the panel stays open / keeps its filter between panel openings, like AL's own GUI.
    private static boolean open;
    private static boolean hideUnchanged;
    private static float scrollOffset;

    private final Font font = Minecraft.getInstance().font;
    private final List<AttributeInstance> data = new ArrayList<>();
    private final Map<UUID, ItemStack> itemSources = new HashMap<>();
    @Nullable private PuffStatsPacket lastPacket;
    private int left, top, startIndex;
    private float scale = 1F;
    private boolean scrolling;

    public static boolean isOpen() { return open; }

    public void toggle() {
        open = !open;
    }

    /** Places the panel at (left, top) drawn at {@code scale} (shrunk to fit the space beside the window). */
    public void setPos(int left, int top, float scale) {
        this.left = left;
        this.top = top;
        this.scale = scale;
    }

    // Mouse -> unscaled panel space
    private double ux(double mx) { return this.left + (mx - this.left) / this.scale; }
    private double uy(double my) { return this.top + (my - this.top) / this.scale; }

    public boolean isInside(double mx, double my) {
        double x = this.ux(mx), y = this.uy(my);
        return open && x >= this.left && x < this.left + WIDTH && y >= this.top && y < this.top + HEIGHT;
    }

    private boolean overClose(double x, double y) {
        return x >= this.left + WIDTH - 16 && x < this.left + WIDTH - 5 && y >= this.top + 3 && y < this.top + 14;
    }

    // ---------------- Data ----------------

    /** Rebuilds client-side AttributeInstances from the latest server snapshot (only when it changed). */
    public void update(@Nullable PuffStatsPacket packet) {
        if (packet == this.lastPacket) return;
        this.lastPacket = packet;
        this.rebuild();
    }

    private void rebuild() {
        this.data.clear();
        this.itemSources.clear();
        if (this.lastPacket == null) return;
        for (PuffStatsPacket.AttrSnap snap : this.lastPacket.full()) {
            if (ALConfig.hiddenAttributes != null && ALConfig.hiddenAttributes.contains(snap.id())) continue;
            Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(snap.id());
            if (attr == null) continue;
            AttributeInstance inst = new AttributeInstance(attr, i -> {});
            inst.setBaseValue(snap.base());
            for (PuffStatsPacket.ModSnap m : snap.mods()) {
                AttributeModifier.Operation op = AttributeModifier.Operation.fromValue(Mth.clamp(m.op(), 0, 2));
                AttributeModifier mod = new AttributeModifier(m.id(), m.name(), m.amount(), op);
                if (inst.getModifier(m.id()) == null) inst.addTransientModifier(mod);
                if (!m.source().isEmpty()) this.itemSources.put(m.id(), m.source());
            }
            if (hideUnchanged && Math.abs(inst.getValue() - inst.getBaseValue()) < 1e-9) continue;
            this.data.add(inst);
        }
        this.data.sort(Comparator.<AttributeInstance, Boolean>comparing(i -> !"minecraft".equals(key(i).getNamespace()))
                .thenComparing(i -> key(i).getNamespace())
                .thenComparing(i -> I18n.get(i.getAttribute().getDescriptionId())));
        int max = this.offScreenRows();
        this.startIndex = max <= 0 ? 0 : (int) (scrollOffset * max + 0.5);
        if (max <= 0) scrollOffset = 0;
    }

    private static ResourceLocation key(AttributeInstance inst) {
        ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(inst.getAttribute());
        return id == null ? new ResourceLocation("minecraft", "unknown") : id;
    }

    private int offScreenRows() {
        return Math.max(0, this.data.size() - MAX_ENTRIES);
    }

    // ---------------- Rendering ----------------

    public void render(GuiGraphics g, int rawX, int rawY) {
        if (!open) return;
        int mouseX = (int) Math.floor(this.ux(rawX)), mouseY = (int) Math.floor(this.uy(rawY));
        g.pose().pushPose();
        g.pose().translate(this.left, this.top, 300);   // above the maid panel and its slots
        g.pose().scale(this.scale, this.scale, 1F);
        g.pose().translate(-this.left, -this.top, 0);
        g.blit(TEXTURE, this.left, this.top, 0, 0, WIDTH, HEIGHT);
        int barY = this.top + LIST_Y + (int) ((SCROLL_H - 15) * scrollOffset);
        g.blit(TEXTURE, this.left + SCROLL_X, barY, 244, this.offScreenRows() > 0 ? 0 : 15, 12, 15);

        if (this.lastPacket == null) {
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.stats.loading"),
                    this.left + 12, this.top + LIST_Y + 6, 0xE0E0E0, false);
        }
        for (int i = this.startIndex; i < this.startIndex + MAX_ENTRIES && i < this.data.size(); i++) {
            this.renderEntry(g, this.data.get(i), this.left + 8, this.top + LIST_Y + ENTRY_HEIGHT * (i - this.startIndex),
                    mouseX, mouseY);
        }
        g.drawString(this.font, Component.translatable("gui.puffcompanion.attributes.title"), this.left + 8, this.top + 5,
                0x404040, false);

        boolean boxHover = this.overHideBox(mouseX, mouseY);
        g.blit(TEXTURE, this.left + 8, this.top + 151, 131, 20 + (hideUnchanged ? 0 : 10) + (boxHover ? 20 : 0), 10, 10);
        g.drawString(this.font, Component.translatable("gui.puffcompanion.attributes.hide_unchanged"), this.left + 21,
                this.top + 152, 0x404040, false);
        boolean closeHover = this.overClose(mouseX, mouseY);
        g.drawString(this.font, "×", this.left + WIDTH - 14, this.top + 4, closeHover ? 0xD9668A : 0x404040, false);
        g.pose().popPose();
    }

    private void renderEntry(GuiGraphics g, AttributeInstance inst, int x, int y, int mouseX, int mouseY) {
        boolean hover = this.hovered(mouseX, mouseY) == inst;
        g.blit(TEXTURE, x, y, 142, hover ? ENTRY_HEIGHT : 0, 100, ENTRY_HEIGHT);

        Component name = Component.translatable(inst.getAttribute().getDescriptionId());
        List<FormattedCharSequence> lines = this.font.split(name, 60);
        if (lines.size() > 2) lines = lines.subList(0, 2);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            int w = this.font.width(line);
            int ty = lines.size() == 1 ? 7 : 2 + 10 * i;
            g.drawString(this.font, line, x + 1 + (68 - w) / 2, y + ty, 0x404040, false);
        }

        Attribute attr = inst.getAttribute();
        Component value = IFormattableAttribute.toValueComponent(attr, AttributeModifier.Operation.ADDITION,
                inst.getValue(), TooltipFlag.Default.NORMAL);
        int color = inst.getValue() > inst.getBaseValue() + 1e-9 ? 0x55FF55
                : inst.getValue() < inst.getBaseValue() - 1e-9 ? 0xFF6060 : 0xFFFFFF;
        int vw = this.font.width(value);
        float scale = vw > 27 ? 27F / vw : 1F;
        g.pose().pushPose();
        g.pose().translate(x + 85, y + 11, 0);
        g.pose().scale(scale, scale, 1F);
        g.drawString(this.font, value, -vw / 2, -4, color, true);
        g.pose().popPose();
    }

    /** Renders the hovered entry's tooltip; returns true when it drew one (the screen then skips its own). */
    public boolean renderTooltip(GuiGraphics g, int mouseX, int mouseY, @Nullable PuffEntity puff) {
        if (!open) return false;
        AttributeInstance inst = this.hovered(this.ux(mouseX), this.uy(mouseY));
        if (inst == null) return false;
        Attribute attr = inst.getAttribute();
        TooltipFlag flag = AttributesLib.getTooltipFlag();
        int maxW = Math.max(120, Minecraft.getInstance().getWindow().getGuiScaledWidth() / 3);
        List<ClientTooltipComponent> list = new ArrayList<>();

        Component title = Component.translatable(attr.getDescriptionId()).withStyle(ChatFormatting.GOLD, ChatFormatting.UNDERLINE);
        if (flag.isAdvanced()) {
            title = Component.empty().append(title).append(Component.literal(" [" + key(inst) + "]").withStyle(ChatFormatting.GRAY));
        }
        this.add(title, list, maxW);
        String descKey = attr.getDescriptionId() + ".desc";
        if (I18n.exists(descKey)) this.add(Component.translatable(descKey).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), list, maxW);

        this.add(Component.translatable("attributeslib.gui.current",
                IFormattableAttribute.toValueComponent(attr, AttributeModifier.Operation.ADDITION, inst.getValue(), flag))
                .withStyle(ChatFormatting.GRAY), list, maxW);
        this.add(Component.translatable("attributeslib.gui.base",
                IFormattableAttribute.toValueComponent(attr, AttributeModifier.Operation.ADDITION, inst.getBaseValue(), flag))
                .withStyle(ChatFormatting.GRAY), list, maxW);
        if (attr instanceof RangedAttribute ranged && Screen.hasShiftDown()) {
            this.add(Component.translatable("attributeslib.gui.min",
                    IFormattableAttribute.toValueComponent(attr, AttributeModifier.Operation.ADDITION, ranged.getMinValue(), flag))
                    .withStyle(ChatFormatting.GRAY), list, maxW);
            this.add(Component.translatable("attributeslib.gui.max",
                    IFormattableAttribute.toValueComponent(attr, AttributeModifier.Operation.ADDITION, ranged.getMaxValue(), flag))
                    .withStyle(ChatFormatting.GRAY), list, maxW);
        }

        List<AttributeModifier> mods = new ArrayList<>(inst.getModifiers());
        if (mods.isEmpty()) {
            this.add(Component.translatable("attributeslib.gui.no_modifiers").withStyle(ChatFormatting.GRAY), list, maxW);
        } else {
            this.add(Component.literal(" "), list, maxW);
            this.add(Component.translatable("attributeslib.gui.modifiers").withStyle(ChatFormatting.GOLD), list, maxW);
            Map<UUID, ModifierSource<?>> sources = this.collectSources(puff);
            mods.sort(Comparator.<AttributeModifier>comparingInt(m -> m.getOperation().ordinal())
                    .thenComparing(ModifierSourceType.compareBySource(sources)));
            for (AttributeModifier mod : mods) {
                list.add(new AttributeModifierComponent(sources.get(mod.getId()),
                        IFormattableAttribute.toComponent(attr, mod, flag), this.font, maxW));
            }
        }
        if (!Screen.hasShiftDown() && attr instanceof RangedAttribute) {
            this.add(Component.translatable("attributeslib.gui.press_shift").withStyle(ChatFormatting.DARK_GRAY), list, maxW);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 100);
        g.renderTooltipInternal(this.font, list, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE);
        g.pose().popPose();
        return true;
    }

    /** AL's registered source types (equipment, effects...) run on the client Puff, plus our server-side item map
     *  which also covers accessories worn through the Curios slots / fake player. */
    private Map<UUID, ModifierSource<?>> collectSources(@Nullable PuffEntity puff) {
        Map<UUID, ModifierSource<?>> map = new HashMap<>();
        if (puff != null) {
            for (ModifierSourceType<?> type : ModifierSourceType.getTypes()) {
                try {
                    type.extract(puff, (mod, src) -> map.put(mod.getId(), src));
                } catch (Throwable ignored) {
                    // a third-party source type that only understands players - skip it
                }
            }
        }
        this.itemSources.forEach((id, stack) -> map.putIfAbsent(id, new ModifierSource.ItemModifierSource(stack)));
        return map;
    }

    private void add(Component text, List<ClientTooltipComponent> list, int maxW) {
        for (FormattedCharSequence seq : this.font.split(text, maxW)) list.add(ClientTooltipComponent.create(seq));
    }

    // ---------------- Input ----------------

    @Nullable
    private AttributeInstance hovered(double mx, double my) {
        for (int i = 0; i < MAX_ENTRIES && this.startIndex + i < this.data.size(); i++) {
            int x = this.left + 8, y = this.top + LIST_Y + i * ENTRY_HEIGHT;
            if (mx >= x && mx < x + 100 && my >= y && my < y + ENTRY_HEIGHT) return this.data.get(this.startIndex + i);
        }
        return null;
    }

    private boolean overHideBox(double mx, double my) {
        return mx >= this.left + 8 && mx < this.left + 18 && my >= this.top + 151 && my < this.top + 161;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!this.isInside(mx, my)) return false;
        double rawY = my;
        mx = this.ux(mx);
        my = this.uy(my);
        if (button == 0 && this.overClose(mx, my)) {
            open = false;
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        } else if (button == 0 && this.overHideBox(mx, my)) {
            hideUnchanged = !hideUnchanged;
            this.rebuild();
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        } else if (button == 0 && this.offScreenRows() > 0 && mx >= this.left + SCROLL_X && mx < this.left + SCROLL_X + 12
                && my >= this.top + LIST_Y && my < this.top + LIST_Y + SCROLL_H) {
            this.scrolling = true;
            this.dragTo(rawY);
        }
        return true;   // swallow clicks on the panel so they don't reach slots / "click outside" handling
    }

    public boolean mouseReleased(double mx, double my, int button) {
        boolean was = this.scrolling;
        this.scrolling = false;
        return was || this.isInside(mx, my);
    }

    public boolean mouseDragged(double mx, double my, int button) {
        if (!open || !this.scrolling) return false;
        this.dragTo(my);
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!this.isInside(mx, my)) return false;
        int max = this.offScreenRows();
        if (max > 0) {
            scrollOffset = Mth.clamp((float) (scrollOffset - delta / max), 0F, 1F);
            this.startIndex = (int) (scrollOffset * max + 0.5);
        }
        return true;
    }

    private void dragTo(double rawY) {
        double my = this.uy(rawY);
        int max = this.offScreenRows();
        scrollOffset = Mth.clamp((float) ((my - this.top - LIST_Y - 7.5) / (SCROLL_H - 15)), 0F, 1F);
        this.startIndex = (int) (scrollOffset * max + 0.5);
    }
}
