package com.puffcompanion.client.gui;

import com.puffcompanion.client.render.PuffRenderer;
import com.puffcompanion.compat.attributeslib.client.PuffAttributesPanel;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffOutfit;
import com.puffcompanion.entity.PuffStories;
import com.puffcompanion.menu.PuffMaidMenu;
import com.puffcompanion.network.PuffInteractionPacket;
import com.puffcompanion.network.PuffInteractionPacket.Action;
import com.puffcompanion.network.PuffNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Puff's maid panel (Touhou Little Maid style), drawn entirely in code in the cream x sakura x amber palette:
 * live 3D preview (with a 4-slot armor column) + stats + 2 hand slots on the left, and three tabs on the right
 * (secret nest & inventory / wardrobe / story memories).
 */
public class PuffMaidScreen extends AbstractContainerScreen<PuffMaidMenu> {
    // Palette (shared with the dialogue / interaction UI)
    private static final int COL_SHADOW = 0x451D1218;
    private static final int COL_BG_TOP = 0xFAFFFDF9;
    private static final int COL_BG_BOT = 0xFAFFF1F5;
    private static final int COL_BORDER = 0xFFF2A0B8;
    private static final int COL_ACCENT = 0xFFF08CA8;
    private static final int COL_TEXT = 0xFF4A332D;
    private static final int COL_DIM = 0xFF9C848B;
    private static final int COL_TITLE = 0xFF543339;
    private static final int COL_WINDOW_TOP = 0xFFFDE7EF;
    private static final int COL_WINDOW_BOT = 0xFFFFF8E8;
    private static final int COL_SLOT_BG = 0xFFFFF6F8;
    private static final int COL_SLOT_BORDER = 0xFFEBC2CF;
    private static final int COL_STAGE_BG = 0xFFFFEAB3;
    private static final int COL_STAGE_BORDER = 0xFFE8B44C;
    private static final int COL_STAGE_TEXT = 0xFF7A4E10;
    private static final int COL_BAR_BG = 0xFFEED9E0;

    // Left column geometry (relative)
    private static final int WIN_X0 = 6, WIN_Y0 = 22, WIN_X1 = 106, WIN_Y1 = 96;
    private static final int RIGHT_X = 116;

    private int lastState;
    /** ApothicAttributes-style attribute side panel (left of the window). */
    private final PuffAttributesPanel attrPanel = new PuffAttributesPanel();

    public PuffMaidScreen(PuffMaidMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = PuffMaidMenu.WIDTH;
        this.imageHeight = PuffMaidMenu.HEIGHT;
    }

    // ---------------- Widgets ----------------

    @Override
    protected void init() {
        super.init();
        this.lastState = this.stateHash() * 31 + this.squadHash();
        java.util.List<Integer> tabList = new java.util.ArrayList<>(java.util.List.of(PuffMaidMenu.TAB_NEST,
                PuffMaidMenu.TAB_WARDROBE, PuffMaidMenu.TAB_MEMORIES, PuffMaidMenu.TAB_STATS));
        if (this.menu.hasCurios()) tabList.add(PuffMaidMenu.TAB_CURIOS);
        tabList.add(PuffMaidMenu.TAB_SQUAD);
        String[] keyByTab = {"gui.puffcompanion.maid.tab.nest", "gui.puffcompanion.maid.tab.wardrobe",
                "gui.puffcompanion.maid.tab.memories", "gui.puffcompanion.maid.tab.stats", "gui.puffcompanion.maid.tab.curios",
                "gui.puffcompanion.maid.tab.squad"};
        int[] tabs = tabList.stream().mapToInt(Integer::intValue).toArray();
        String[] keys = new String[tabs.length];
        for (int i = 0; i < tabs.length; i++) keys[i] = keyByTab[tabs[i]];
        int tw = (this.imageWidth - RIGHT_X - 6 - 3 * (tabs.length - 1)) / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            final int tab = tabs[i];
            this.addRenderableWidget(new PanelButton(this.leftPos + RIGHT_X + i * (tw + 3), this.topPos + 5, tw, 14,
                    Component.translatable(keys[i]), () -> this.setTab(tab), this.menu.tab == tab, false));
        }

        PuffEntity puff = this.menu.getPuff();
        if (puff == null) return;

        // Owner friendly-fire guard toggle (top-right corner of the preview window)
        boolean guard = puff.isOwnerGuard();
        PanelButton guardBtn = new PanelButton(this.leftPos + WIN_X1 - 18, this.topPos + WIN_Y0 + 2, 16, 12,
                Component.translatable(guard ? "gui.puffcompanion.maid.guard.on" : "gui.puffcompanion.maid.guard.off"),
                () -> this.send(Action.TOGGLE_GUARD, ""), guard, true);
        guardBtn.setTooltip(Tooltip.create(Component.translatable(
                guard ? "gui.puffcompanion.maid.guard.tooltip_on" : "gui.puffcompanion.maid.guard.tooltip_off")));
        this.addRenderableWidget(guardBtn);

        // ApothicAttributes panel toggle (AL's own sword icon), next to the guard toggle
        ImageButton attrBtn = new ImageButton(this.leftPos + WIN_X1 - 31, this.topPos + WIN_Y0 + 3, 10, 10, 131, 0, 10,
                PuffAttributesPanel.TEXTURE, 256, 256, b -> {
                    this.attrPanel.toggle();
                    this.rebuildWidgets();
                }, Component.translatable("gui.puffcompanion.attributes.toggle"));
        attrBtn.setTooltip(Tooltip.create(Component.translatable(PuffAttributesPanel.isOpen()
                ? "gui.puffcompanion.attributes.hide" : "gui.puffcompanion.attributes.show")));
        this.addRenderableWidget(attrBtn);

        boolean hasSpellMod = com.puffcompanion.compat.SpellCompat.isAnySpellModLoaded();
        if (hasSpellMod) {
            boolean spellOn = puff.isSpellCastEnabled();
            PanelButton spellBtn = new PanelButton(this.leftPos + WIN_X1 - 18, this.topPos + WIN_Y0 + 16, 16, 12,
                    Component.translatable(spellOn ? "gui.puffcompanion.maid.spell.on" : "gui.puffcompanion.maid.spell.off"),
                    () -> this.send(Action.TOGGLE_SPELL, ""), spellOn, true);
            spellBtn.setTooltip(Tooltip.create(Component.translatable(
                    spellOn ? "gui.puffcompanion.maid.spell.tooltip_on" : "gui.puffcompanion.maid.spell.tooltip_off")));
            this.addRenderableWidget(spellBtn);
        }

        boolean homeOn = puff.isHomeMode();
        int homeY = this.topPos + WIN_Y0 + (hasSpellMod ? 30 : 16);
        PanelButton homeBtn = new PanelButton(this.leftPos + WIN_X1 - 18, homeY, 16, 12,
                Component.translatable(homeOn ? "gui.puffcompanion.maid.home.on" : "gui.puffcompanion.maid.home.off"),
                () -> this.send(Action.TOGGLE_HOME, ""), homeOn, true);
        var homePos = puff.getHomePos();
        homeBtn.setTooltip(Tooltip.create(homeOn && homePos != null
                ? Component.translatable("gui.puffcompanion.maid.home.tooltip_on", homePos.getX(), homePos.getY(), homePos.getZ())
                : Component.translatable("gui.puffcompanion.maid.home.tooltip_off")));
        this.addRenderableWidget(homeBtn);

        if (this.menu.tab == PuffMaidMenu.TAB_CURIOS && this.menu.curioPages() > 1) {
            int px = this.leftPos + this.imageWidth - 50;
            PanelButton prev = new PanelButton(px, this.topPos + 23, 14, 10, Component.literal("<"),
                    () -> this.turnCurioPage(-1), false, false);
            prev.active = this.menu.curioPage > 0;
            PanelButton next = new PanelButton(px + 30, this.topPos + 23, 14, 10, Component.literal(">"),
                    () -> this.turnCurioPage(1), false, false);
            next.active = this.menu.curioPage < this.menu.curioPages() - 1;
            this.addRenderableWidget(prev);
            this.addRenderableWidget(next);
        }
        int x = this.leftPos + RIGHT_X + 4;
        int w = this.imageWidth - RIGHT_X - 14;
        if (this.menu.tab == PuffMaidMenu.TAB_NEST) {
            var mode = puff.getWorkMode();
            PanelButton modeBtn = new PanelButton(this.leftPos + 184, this.topPos + 21, 98, 11,
                    Component.translatable("gui.puffcompanion.mode_btn", Component.translatable(mode.langKey())),
                    () -> this.send(Action.CYCLE_MODE, ""), mode != com.puffcompanion.entity.PuffWorkMode.FOLLOW_GUARD, true);
            modeBtn.setTooltip(Tooltip.create(Component.translatable(mode.descKey())));
            this.addRenderableWidget(modeBtn);
        }
        if (this.menu.tab == PuffMaidMenu.TAB_SQUAD) this.initSquad();
        if (this.menu.tab == PuffMaidMenu.TAB_WARDROBE) {
            PuffOutfit[] outfits = PuffOutfit.values();
            for (int i = 0; i < outfits.length; i++) {
                PuffOutfit outfit = outfits[i];
                boolean unlocked = puff.isOutfitUnlocked(outfit);
                boolean current = outfit.id().equals(puff.getCurrentOutfit());
                Component label = current
                        ? Component.translatable("gui.puffcompanion.outfit.current_label", Component.translatable(outfit.nameKey()))
                        : unlocked ? Component.translatable(outfit.nameKey())
                        : Component.translatable("gui.puffcompanion.locked", Component.translatable(outfit.nameKey()));
                PanelButton b = new PanelButton(x, this.topPos + 36 + i * 24, w, 20, label,
                        () -> this.send(Action.OUTFIT, outfit.id()), current, true);
                b.active = unlocked && !current;
                b.setTooltip(Tooltip.create(unlocked
                        ? Component.translatable(current ? "gui.puffcompanion.outfit.current" : "gui.puffcompanion.outfit.switch",
                        Component.translatable(outfit.nameKey()))
                        : Component.translatable("puff.outfit." + outfit.id() + ".unlock")));
                this.addRenderableWidget(b);
            }
        } else if (this.menu.tab == PuffMaidMenu.TAB_MEMORIES) {
            this.initStoryTree(puff, x, w);
        }
    }

    // ---------------- Story Tree tab (Stage 8) ----------------

    private int memoryPage; // 0 = Main & 3 Endings Flowchart, 1 = Mod Crossovers
    private double storyPanX = 0;
    private double storyPanY = 0;
    private boolean draggingStoryTree = false;
    private double storyDragMoved;
    @Nullable
    private PuffStories.Entry hoveredStoryNode;
    @Nullable
    private PuffStories.Entry selectedStoryNode;

    private static final int NODE_W = 118;
    private static final int NODE_H = 34;

    private record TreeNode(PuffStories.Entry entry, int x, int y) {}
    private record TreeEdge(PuffStories.Entry from, PuffStories.Entry to) {}

    private static final java.util.List<TreeNode> MAIN_TREE_NODES = java.util.List.of(
            new TreeNode(PuffStories.PROLOGUE, 12, 8),
            new TreeNode(PuffStories.CHAPTER1, 12, 54),
            new TreeNode(PuffStories.SIDE1, 146, 54),
            new TreeNode(PuffStories.SIDE3_STARLIGHT, 280, 54),
            new TreeNode(PuffStories.SIDE4_CHEESE_FEAST, 414, 54),
            new TreeNode(PuffStories.CHAPTER2, 12, 102),
            new TreeNode(PuffStories.SIDE2, 146, 102),
            new TreeNode(PuffStories.SIDE5_RAT_PARADE, 280, 102),
            new TreeNode(PuffStories.CHAPTER3, 12, 150),
            new TreeNode(PuffStories.FINALE, 12, 202),
            new TreeNode(PuffStories.AFTER_MORNING, 146, 202),
            new TreeNode(PuffStories.ENDING_ADVENTURE, 12, 250),
            new TreeNode(PuffStories.ENDING_HEARTH, 146, 250),
            new TreeNode(PuffStories.ENDING_QUEEN, 280, 250),
            new TreeNode(PuffStories.ENDING_TRUE_VOW, 146, 298)
    );

    private static final java.util.List<TreeEdge> MAIN_TREE_EDGES = java.util.List.of(
            new TreeEdge(PuffStories.PROLOGUE, PuffStories.CHAPTER1),
            new TreeEdge(PuffStories.CHAPTER1, PuffStories.SIDE1),
            new TreeEdge(PuffStories.SIDE1, PuffStories.SIDE3_STARLIGHT),
            new TreeEdge(PuffStories.SIDE3_STARLIGHT, PuffStories.SIDE4_CHEESE_FEAST),
            new TreeEdge(PuffStories.CHAPTER1, PuffStories.CHAPTER2),
            new TreeEdge(PuffStories.CHAPTER2, PuffStories.SIDE2),
            new TreeEdge(PuffStories.SIDE2, PuffStories.SIDE5_RAT_PARADE),
            new TreeEdge(PuffStories.CHAPTER2, PuffStories.CHAPTER3),
            new TreeEdge(PuffStories.CHAPTER3, PuffStories.FINALE),
            new TreeEdge(PuffStories.FINALE, PuffStories.AFTER_MORNING),
            new TreeEdge(PuffStories.CHAPTER3, PuffStories.ENDING_ADVENTURE),
            new TreeEdge(PuffStories.ENDING_ADVENTURE, PuffStories.ENDING_HEARTH),
            new TreeEdge(PuffStories.ENDING_HEARTH, PuffStories.ENDING_QUEEN),
            new TreeEdge(PuffStories.ENDING_HEARTH, PuffStories.ENDING_TRUE_VOW)
    );

    private void initStoryTree(PuffEntity puff, int x, int w) {
        int resetW = 30;
        int avail = w - resetW - 6;
        int tab1W = (avail * 56) / 100;
        int tab2W = avail - tab1W;
        this.addRenderableWidget(new PanelButton(x, this.topPos + 25, tab1W, 12,
                Component.translatable("gui.puffcompanion.story.tree_tab"),
                () -> { this.memoryPage = 0; this.storyPanX = 0; this.storyPanY = 0; this.selectedStoryNode = null; this.rebuildWidgets(); },
                this.memoryPage == 0, false));
        this.addRenderableWidget(new PanelButton(x + tab1W + 3, this.topPos + 25, tab2W, 12,
                Component.translatable("gui.puffcompanion.story.crossover_tab"),
                () -> { this.memoryPage = 1; this.storyPanX = 0; this.storyPanY = 0; this.selectedStoryNode = null; this.rebuildWidgets(); },
                this.memoryPage == 1, false));
        this.addRenderableWidget(new PanelButton(x + tab1W + tab2W + 6, this.topPos + 25, resetW, 12,
                Component.translatable("gui.puffcompanion.story.reset_view"),
                () -> { this.storyPanX = 0; this.storyPanY = 0; }, false, false));
    }

    private java.util.List<TreeNode> activeStoryNodes() {
        if (this.memoryPage == 0) return MAIN_TREE_NODES;
        java.util.List<TreeNode> list = new java.util.ArrayList<>();
        int idx = 0;
        for (PuffStories.Entry e : PuffStories.ORDER) {
            if (e.category() != PuffStories.Category.CROSSOVER) continue;
            int col = idx % 2;
            int row = idx / 2;
            list.add(new TreeNode(e, 12 + col * 132, 8 + row * 46));
            idx++;
        }
        return list;
    }

    private int storyViewX() { return this.leftPos + RIGHT_X + 3; }
    private int storyViewY() { return this.topPos + 50; }
    private int storyViewW() { return this.imageWidth - RIGHT_X - 12; }
    private int storyViewH() { return this.imageHeight - 80; }

    private boolean isInsideStoryViewport(double mx, double my) {
        int vx = storyViewX(), vy = storyViewY(), vw = storyViewW(), vh = storyViewH();
        return mx >= vx && mx <= vx + vw && my >= vy && my <= vy + vh;
    }

    private void clampStoryPan() {
        int contentW = this.memoryPage == 0 ? 550 : 284;
        int contentH = this.memoryPage == 0 ? 350 : 160;
        int minX = Math.min(0, storyViewW() - contentW);
        int minY = Math.min(0, storyViewH() - contentH);
        this.storyPanX = Math.max(minX, Math.min(8, this.storyPanX));
        this.storyPanY = Math.max(minY, Math.min(8, this.storyPanY));
    }

    private void renderStoryTreeCanvas(GuiGraphics g, PuffEntity puff, int mouseX, int mouseY) {
        int vx = storyViewX(), vy = storyViewY(), vw = storyViewW(), vh = storyViewH();
        this.clampStoryPan();

        Component routeLine = this.memoryPage == 0
                ? Component.translatable("gui.puffcompanion.story.route_bar",
                puff.getAdventureRouteScore(), puff.getHearthRouteScore(),
                puff.getBondRouteScore(), puff.getCompletedSideCount())
                : Component.translatable("gui.puffcompanion.story.drag_hint");
        this.small(g, routeLine, vx + 1, this.topPos + 40, vw - 2, COL_ACCENT);

        g.fillGradient(vx, vy, vx + vw, vy + vh, 0xCCFFF8FB, 0xCCFDECF3);
        outline(g, vx - 1, vy - 1, vx + vw + 1, vy + vh + 1, 0x88E59BB5);

        g.enableScissor(vx, vy, vx + vw, vy + vh);
        int ox = vx + (int) Math.round(this.storyPanX);
        int oy = vy + (int) Math.round(this.storyPanY);

        int step = 18;
        int startDotX = vx + ((((int) Math.round(this.storyPanX)) % step) + step) % step;
        int startDotY = vy + ((((int) Math.round(this.storyPanY)) % step) + step) % step;
        for (int gx = startDotX; gx < vx + vw; gx += step) {
            for (int gy = startDotY; gy < vy + vh; gy += step) {
                g.fill(gx, gy, gx + 1, gy + 1, 0x35D97B9A);
            }
        }

        java.util.List<TreeNode> nodes = this.activeStoryNodes();
        java.util.Map<String, TreeNode> byId = new java.util.HashMap<>();
        for (TreeNode n : nodes) byId.put(n.entry().id(), n);

        if (this.memoryPage == 0) {
            int colOn = 0xFFD95C88;
            int colOff = 0x60C993A8;
            for (TreeEdge edge : MAIN_TREE_EDGES) {
                TreeNode a = byId.get(edge.from().id());
                TreeNode b = byId.get(edge.to().id());
                if (a == null || b == null) continue;
                boolean lit = storyDone(puff, edge.from()) && storyDone(puff, edge.to());
                int col = lit ? colOn : colOff;
                int ax = ox + a.x(), ay = oy + a.y();
                int bx = ox + b.x(), by = oy + b.y();
                if (a.y() == b.y()) {
                    int yMid = ay + NODE_H / 2;
                    g.fill(ax + NODE_W, yMid - 1, bx, yMid + 1, col);
                } else if (a.x() == b.x()) {
                    int xMid = ax + NODE_W / 2;
                    g.fill(xMid - 1, ay + NODE_H, xMid + 1, by, col);
                } else {
                    int xMid = ax + NODE_W / 2;
                    int yMid = by + NODE_H / 2;
                    g.fill(xMid - 1, ay + NODE_H, xMid + 1, yMid + 1, col);
                    g.fill(Math.min(xMid, bx), yMid - 1, Math.max(xMid, bx), yMid + 1, col);
                }
            }
        }

        this.hoveredStoryNode = null;
        boolean inView = isInsideStoryViewport(mouseX, mouseY);
        for (TreeNode n : nodes) {
            int nx = ox + n.x();
            int ny = oy + n.y();
            if (nx + NODE_W < vx || nx > vx + vw || ny + NODE_H < vy || ny > vy + vh) continue;
            boolean done = storyDone(puff, n.entry());
            boolean isTrueEnd = n.entry() == PuffStories.ENDING_TRUE_VOW;
            boolean hov = inView && mouseX >= nx && mouseX <= nx + NODE_W && mouseY >= ny && mouseY <= ny + NODE_H;
            boolean sel = this.selectedStoryNode == n.entry();
            if (hov) this.hoveredStoryNode = n.entry();

            int bgTop = done ? (isTrueEnd ? 0xFFFFF3CC : 0xFFFFEEF4) : 0xFFF3ECEF;
            int bgBot = done ? (isTrueEnd ? 0xFFFFDFEC : 0xFFFADAE6) : 0xFFE9DFE4;
            int border = hov ? 0xFFE84A80
                    : (sel ? 0xFFD9668A : (done ? (isTrueEnd ? 0xFFE6A82E : 0xFFE07DA0) : 0xFFC5B2BC));
            g.fillGradient(nx, ny, nx + NODE_W, ny + NODE_H, bgTop, bgBot);
            outline(g, nx, ny, nx + NODE_W, ny + NODE_H, border);
            if (hov || sel) {
                outline(g, nx + 1, ny + 1, nx + NODE_W - 1, ny + NODE_H - 1, 0x55FF8AB2);
            }

            int ix = nx + 4, iy = ny + 4;
            g.blit(n.entry().iconTexture(), ix, iy, 0, 0, 26, 26, 26, 26);
            if (!done) {
                g.fill(ix, iy, ix + 26, iy + 26, 0x99382834);
            }
            outline(g, ix - 1, iy - 1, ix + 27, iy + 27, done ? (isTrueEnd ? 0xFFE6A82E : 0xFFE58AA9) : 0x999B8892);

            String catKey = isTrueEnd ? "gui.puffcompanion.story.cat.true_ending"
                    : switch (n.entry().category()) {
                case MAIN -> "gui.puffcompanion.story.cat.main";
                case SIDE -> "gui.puffcompanion.story.cat.side";
                case ENDING -> "gui.puffcompanion.story.cat.ending";
                case CROSSOVER -> "gui.puffcompanion.story.cat.crossover";
            };
            int badgeCol = isTrueEnd ? 0xFFB87600 : switch (n.entry().category()) {
                case MAIN -> 0xFFC94A78;
                case SIDE -> 0xFF5D7EC9;
                case ENDING -> 0xFFD06834;
                case CROSSOVER -> 0xFF8A4BC2;
            };
            this.small(g, Component.translatable(catKey), nx + 34, ny + 4, 46, done ? badgeCol : COL_DIM);
            this.small(g, Component.literal(done ? "✓" : "×"), nx + NODE_W - 11, ny + 4, 10, done ? 0xFF2E9E5B : COL_DIM);

            String titleStr = Component.translatable(n.entry().titleKey()).getString();
            int textW = NODE_W - 38;
            int maxRaw = (int) (textW / 0.7F);
            String line1 = this.font.plainSubstrByWidth(titleStr, maxRaw);
            String rest = titleStr.length() > line1.length() ? titleStr.substring(line1.length()).trim() : "";
            int textCol = done ? COL_TEXT : COL_DIM;
            g.pose().pushPose();
            g.pose().translate(nx + 34, ny + 14, 0);
            g.pose().scale(0.7F, 0.7F, 1F);
            g.drawString(this.font, line1, 0, 0, textCol, false);
            if (!rest.isEmpty()) {
                String line2 = this.font.plainSubstrByWidth(rest, maxRaw);
                if (rest.length() > line2.length() && line2.length() > 1) {
                    line2 = line2.substring(0, line2.length() - 1) + "…";
                }
                g.drawString(this.font, line2, 0, 10, textCol, false);
            }
            g.pose().popPose();
        }
        g.disableScissor();

        int detailY = vy + vh + 3;
        int detailH = 19;
        g.fill(vx, detailY, vx + vw, detailY + detailH, 0xCCFFF2F7);
        outline(g, vx - 1, detailY - 1, vx + vw + 1, detailY + detailH + 1, 0x70E59BB5);
        PuffStories.Entry focus = this.hoveredStoryNode != null ? this.hoveredStoryNode : this.selectedStoryNode;
        if (focus != null) {
            boolean done = storyDone(puff, focus);
            Component head = Component.translatable(focus.titleKey());
            Component sub = done ? Component.translatable("gui.puffcompanion.maid.story.replay")
                    : Component.translatable(focus.hintKey());
            this.small(g, head, vx + 3, detailY + 2, vw - 6, done ? COL_ACCENT : COL_TEXT);
            this.small(g, sub, vx + 3, detailY + 11, vw - 6, COL_DIM);
        } else {
            this.small(g, Component.translatable("gui.puffcompanion.story.drag_hint"), vx + 3, detailY + 6, vw - 6, COL_DIM);
        }
    }

    private void renderStoryTreeTooltip(GuiGraphics g, PuffEntity puff, int mouseX, int mouseY) {
        if (this.hoveredStoryNode == null) return;
        boolean done = storyDone(puff, this.hoveredStoryNode);
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable(this.hoveredStoryNode.titleKey()));
        lines.add(Component.translatable(this.hoveredStoryNode.hintKey()));
        if (done) {
            lines.add(Component.translatable("gui.puffcompanion.maid.story.replay"));
        }
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    private static boolean storyDone(PuffEntity puff, PuffStories.Entry entry) {
        int idx = PuffStories.indexOf(entry.id());
        return idx >= 0 && puff.isStoryDone(idx);
    }

    // ---------------- Squad tab ----------------

    private static final int SQUAD_ROWS = 5;
    private static final int SQUAD_Y = 34;
    private int squadPage;
    @Nullable
    private java.util.UUID selectedSquadRat;

    @Nullable
    private com.puffcompanion.network.PuffSquadSyncPacket squad() {
        return com.puffcompanion.client.PuffSquadCache.get(this.menu.getPuffId());
    }

    private void openSquadDetail(java.util.UUID ratId) {
        this.selectedSquadRat = ratId;
        com.puffcompanion.client.PuffSquadCache.clearDetail();
        com.puffcompanion.network.PuffNetwork.sendToServer(
                new com.puffcompanion.network.PuffSquadDetailRequestPacket(this.menu.getPuffId(), ratId));
        this.lastState = this.stateHash() * 31 + this.squadHash();
        this.rebuildWidgets();
    }

    private void closeSquadDetail() {
        if (this.selectedSquadRat != null) {
            this.selectedSquadRat = null;
            com.puffcompanion.client.PuffSquadCache.clearDetail();
            this.lastState = this.stateHash() * 31 + this.squadHash();
            this.rebuildWidgets();
        }
    }

    private void kickSquadRat(java.util.UUID ratId) {
        com.puffcompanion.network.PuffNetwork.sendToServer(
                new com.puffcompanion.network.PuffSquadKickPacket(this.menu.getPuffId(), ratId));
        com.puffcompanion.client.PuffSquadCache.kick(this.menu.getPuffId(), ratId);
        if (ratId.equals(this.selectedSquadRat)) {
            this.selectedSquadRat = null;
        }
        this.lastState = this.stateHash() * 31 + this.squadHash();
        this.rebuildWidgets();
    }

    private void initSquad() {
        var data = this.squad();
        boolean create = com.puffcompanion.compat.CreateCompat.isLoaded();
        if (this.selectedSquadRat != null) {
            final java.util.UUID ratId = this.selectedSquadRat;
            PanelButton back = new PanelButton(this.leftPos + RIGHT_X, this.topPos + 22, 34, 12,
                    Component.translatable("gui.puffcompanion.squad.back"), this::closeSquadDetail, false, false);
            PanelButton refresh = new PanelButton(this.leftPos + this.imageWidth - 64, this.topPos + 22, 26, 12,
                    Component.translatable("gui.puffcompanion.squad.refresh"), () -> this.openSquadDetail(ratId), false, false);
            refresh.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.squad.refresh_tip")));
            PanelButton kick = new PanelButton(this.leftPos + this.imageWidth - 36, this.topPos + 22, 28, 12,
                    Component.translatable("gui.puffcompanion.squad.kick_btn"), () -> this.kickSquadRat(ratId), false, false);
            kick.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.squad.kick_tip")));
            this.addRenderableWidget(back);
            this.addRenderableWidget(refresh);
            this.addRenderableWidget(kick);
        } else if (data != null) {
            int pages = Math.max(1, (data.rows().size() + SQUAD_ROWS - 1) / SQUAD_ROWS);
            this.squadPage = Math.min(this.squadPage, pages - 1);
            for (int i = 0; i < SQUAD_ROWS; i++) {
                int idx = this.squadPage * SQUAD_ROWS + i;
                if (idx >= data.rows().size()) break;
                var row = data.rows().get(idx);
                var job = com.puffcompanion.compat.rats.SquadJob.byId(row.job());
                PanelButton b = new PanelButton(this.leftPos + this.imageWidth - 76, this.topPos + SQUAD_Y + i * 18 + 1, 48, 14,
                        Component.translatable(job.langKey()), () -> {
                    int next = job.next(create).ordinal();
                    com.puffcompanion.network.PuffNetwork.sendToServer(
                            new com.puffcompanion.network.PuffSquadJobPacket(this.menu.getPuffId(), row.id(), next));
                    com.puffcompanion.client.PuffSquadCache.setJob(this.menu.getPuffId(), row.id(), next);
                    this.lastState = this.stateHash() * 31 + this.squadHash();
                    this.rebuildWidgets();
                },
                        job != com.puffcompanion.compat.rats.SquadJob.NONE, false);
                b.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.squad.job_tip")));
                this.addRenderableWidget(b);
                PanelButton kick = new PanelButton(this.leftPos + this.imageWidth - 26, this.topPos + SQUAD_Y + i * 18 + 1, 18, 14,
                        Component.literal("×"), () -> this.kickSquadRat(row.id()), false, false);
                kick.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.squad.kick_tip")));
                this.addRenderableWidget(kick);
            }
            if (pages > 1) {
                int px = this.leftPos + this.imageWidth - 50;
                PanelButton prev = new PanelButton(px, this.topPos + 23, 14, 10, Component.literal("<"), () -> {
                    this.squadPage--;
                    this.rebuildWidgets();
                }, false, false);
                prev.active = this.squadPage > 0;
                PanelButton next = new PanelButton(px + 30, this.topPos + 23, 14, 10, Component.literal(">"), () -> {
                    this.squadPage++;
                    this.rebuildWidgets();
                }, false, false);
                next.active = this.squadPage < pages - 1;
                this.addRenderableWidget(prev);
                this.addRenderableWidget(next);
            }
        }
        boolean lootr = com.puffcompanion.compat.LootrCompat.isLoaded();
        boolean on = data == null || data.autoLootr();
        PanelButton loot = new PanelButton(this.leftPos + 146, this.topPos + PuffMaidMenu.BLUEPRINT_Y, this.imageWidth - 154, 16,
                Component.translatable(on ? "gui.puffcompanion.squad.lootr_on" : "gui.puffcompanion.squad.lootr_off"),
                () -> {
                    this.send(Action.TOGGLE_LOOTR, "");
                    com.puffcompanion.client.PuffSquadCache.toggleLootr(this.menu.getPuffId());
                    this.lastState = this.stateHash() * 31 + this.squadHash();
                    this.rebuildWidgets();
                }, on && lootr, true);
        loot.active = lootr;
        loot.setTooltip(Tooltip.create(Component.translatable(lootr ? "gui.puffcompanion.squad.lootr_tip" : "gui.puffcompanion.squad.lootr_missing")));
        this.addRenderableWidget(loot);
    }

    private int squadHash() {
        var data = this.squad();
        if (data == null || this.menu.tab != PuffMaidMenu.TAB_SQUAD) return 0;
        int h = Objects.hash(data.autoLootr(), data.rows().size(), this.selectedSquadRat,
                com.puffcompanion.client.PuffSquadCache.detailRevision());
        for (var r : data.rows()) h = 31 * h + Objects.hash(r.id(), r.job());
        return h;
    }

    private void renderSquadLabels(GuiGraphics g, int mouseX, int mouseY) {
        var data = this.squad();
        if (this.selectedSquadRat != null) {
            this.renderSquadDetailLabels(g);
            if (data != null) {
                this.renderSquadFooter(g, data);
            }
            return;
        }
        g.drawString(this.font, Component.translatable("gui.puffcompanion.squad.title"), RIGHT_X + 2, 24, COL_TEXT, false);
        if (data == null) return;
        int pages = Math.max(1, (data.rows().size() + SQUAD_ROWS - 1) / SQUAD_ROWS);
        if (pages > 1) {
            String page = (this.squadPage + 1) + "/" + pages;
            g.drawString(this.font, page, this.imageWidth - 33 - this.font.width(page) / 2 + 3, 24, COL_DIM, false);
        }
        if (data.rows().isEmpty()) {
            this.small(g, Component.translatable("gui.puffcompanion.squad.empty"), RIGHT_X + 4, SQUAD_Y + 4, 160, COL_DIM);
            this.small(g, Component.translatable("gui.puffcompanion.squad.empty2"), RIGHT_X + 4, SQUAD_Y + 14, 160, COL_DIM);
        }
        for (int i = 0; i < SQUAD_ROWS; i++) {
            int idx = this.squadPage * SQUAD_ROWS + i;
            if (idx >= data.rows().size()) break;
            var row = data.rows().get(idx);
            int y = SQUAD_Y + i * 18;
            Component name = row.online() ? Component.literal(row.name())
                    : Component.translatable("gui.puffcompanion.squad.offline", row.name());
            this.small(g, name, RIGHT_X + 4, y + 2, 64, row.online() ? COL_TEXT : COL_DIM);
            Component status = row.statusKey().isEmpty() ? Component.empty()
                    : Component.translatable(row.statusKey(), row.statusArg());
            this.small(g, status, RIGHT_X + 4, y + 10, 64, COL_ACCENT);
            // worn Rats upgrades as small icons
            for (int k = 0; k < row.upgrades().size() && k < 3; k++) {
                g.pose().pushPose();
                g.pose().translate(RIGHT_X + 64 + k * 10, y + 4, 0);
                g.pose().scale(0.6F, 0.6F, 1F);
                g.renderItem(row.upgrades().get(k), 0, 0);
                g.pose().popPose();
            }
        }
        this.renderSquadFooter(g, data);
    }

    private void renderSquadFooter(GuiGraphics g, com.puffcompanion.network.PuffSquadSyncPacket data) {
        String info = data.area().isEmpty() ? Component.translatable("gui.puffcompanion.squad.no_area").getString()
                : Component.translatable("gui.puffcompanion.squad.area", data.area()).getString();
        info += "  " + Component.translatable(data.hasStorage() ? "gui.puffcompanion.squad.storage_yes" : "gui.puffcompanion.squad.storage_no").getString();
        this.small(g, Component.literal(info), RIGHT_X + 2, 127, 168, COL_DIM);
        if (!com.puffcompanion.compat.CreateCompat.isLoaded()) {
            this.small(g, Component.translatable("gui.puffcompanion.squad.no_create"), RIGHT_X + 2, PuffMaidMenu.BLUEPRINT_Y + 5, 26, COL_DIM);
        } else if (!data.buildStatus().isEmpty()) {
            Component bs = "done".equals(data.buildStatus()) ? Component.translatable("gui.puffcompanion.squad.build_done")
                    : Component.translatable("gui.puffcompanion.squad.build_missing", data.buildStatus());
            this.small(g, bs, PuffMaidMenu.BLUEPRINT_X + 19, PuffMaidMenu.BLUEPRINT_Y + 5, 26, COL_ACCENT);
        }
    }

    private void renderSquadDetailLabels(GuiGraphics g) {
        var pkt = com.puffcompanion.client.PuffSquadCache.getDetail(this.menu.getPuffId(), this.selectedSquadRat);
        if (pkt == null) {
            this.small(g, Component.translatable("gui.puffcompanion.squad.detail.loading"), RIGHT_X + 6, 46, 156, COL_DIM);
            return;
        }
        var d = pkt.detail();
        int x = RIGHT_X + 5;
        int w = this.imageWidth - RIGHT_X - 14;
        Component badge = Component.translatable(d.online()
                ? "gui.puffcompanion.squad.detail.online" : "gui.puffcompanion.squad.detail.offline");
        this.small(g, Component.literal(d.name()), x, 40, w - 36, COL_TEXT);
        this.small(g, badge, this.imageWidth - 42, 40, 34, d.online() ? 0xFF2E8B57 : COL_DIM);

        var job = com.puffcompanion.compat.rats.SquadJob.byId(d.job());
        String jobLine = Component.translatable("gui.puffcompanion.squad.detail.job",
                Component.translatable(job.langKey())).getString();
        if (!d.statusKey().isEmpty()) {
            jobLine += " · " + Component.translatable(d.statusKey(), d.statusArg()).getString();
        }
        this.small(g, Component.literal(jobLine), x, 50, w, COL_ACCENT);

        if (!d.online()) {
            this.small(g, Component.translatable("gui.puffcompanion.squad.detail.pos_unloaded"), x, 64, w, COL_DIM);
            this.small(g, Component.translatable("gui.puffcompanion.squad.detail.pos_hint"), x, 75, w, COL_DIM);
            return;
        }

        String hpText = String.format(java.util.Locale.ROOT, "%.1f / %.1f", d.hp(), d.maxHp());
        StringBuilder state = new StringBuilder();
        if (d.inCage()) state.append("  ").append(Component.translatable("gui.puffcompanion.squad.detail.caged").getString());
        else if (d.sitting()) state.append("  ").append(Component.translatable("gui.puffcompanion.squad.detail.sitting").getString());
        this.small(g, Component.translatable("gui.puffcompanion.squad.detail.hp", hpText + state), x, 60, w, COL_TEXT);

        this.small(g, Component.translatable("gui.puffcompanion.squad.detail.pos", d.x(), d.y(), d.z()), x, 70, w, COL_TEXT);
        String dimName = formatDimensionName(d.dimension());
        Component dimLine = d.distance() >= 0
                ? Component.translatable("gui.puffcompanion.squad.detail.dim_dist", dimName, d.distance())
                : Component.translatable("gui.puffcompanion.squad.detail.dim_other", dimName);
        this.small(g, dimLine, x, 80, w, COL_DIM);

        this.small(g, Component.translatable("gui.puffcompanion.squad.detail.held"), x, 93, 36, COL_DIM);
        if (d.heldItem().isEmpty()) {
            this.small(g, Component.translatable("gui.puffcompanion.squad.detail.none"), x + 32, 93, 40, COL_DIM);
        } else {
            g.pose().pushPose();
            g.pose().translate(x + 32, 90, 0);
            g.pose().scale(0.7F, 0.7F, 1F);
            g.renderItem(d.heldItem(), 0, 0);
            g.pose().popPose();
            this.small(g, d.heldItem().getHoverName(), x + 46, 93, w - 48, COL_TEXT);
        }

        this.small(g, Component.translatable("gui.puffcompanion.squad.detail.upgrades"), x, 108, 36, COL_DIM);
        if (d.upgrades().isEmpty()) {
            this.small(g, Component.translatable("gui.puffcompanion.squad.detail.none"), x + 32, 108, 40, COL_DIM);
        } else {
            for (int k = 0; k < d.upgrades().size() && k < 9; k++) {
                g.pose().pushPose();
                g.pose().translate(x + 32 + k * 13, 105, 0);
                g.pose().scale(0.7F, 0.7F, 1F);
                g.renderItem(d.upgrades().get(k), 0, 0);
                g.pose().popPose();
            }
        }
    }

    private static String formatDimensionName(String dimId) {
        return switch (dimId) {
            case "minecraft:overworld" -> Component.translatable("gui.puffcompanion.squad.dim.overworld").getString();
            case "minecraft:the_nether" -> Component.translatable("gui.puffcompanion.squad.dim.nether").getString();
            case "minecraft:the_end" -> Component.translatable("gui.puffcompanion.squad.dim.end").getString();
            default -> dimId;
        };
    }

    /** Row hover: worn upgrades by name. */
    private void renderSquadTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int mx = mouseX - this.leftPos, my = mouseY - this.topPos;
        if (this.selectedSquadRat != null) {
            var pkt = com.puffcompanion.client.PuffSquadCache.getDetail(this.menu.getPuffId(), this.selectedSquadRat);
            if (pkt == null || !pkt.detail().online()) return;
            var d = pkt.detail();
            int x = RIGHT_X + 5;
            if (!d.heldItem().isEmpty() && mx >= x + 32 && mx < x + 44 && my >= 90 && my < 102) {
                g.renderTooltip(this.font, d.heldItem(), mouseX, mouseY);
                return;
            }
            for (int k = 0; k < d.upgrades().size() && k < 9; k++) {
                int ix = x + 32 + k * 13;
                if (mx >= ix && mx < ix + 12 && my >= 105 && my < 117) {
                    g.renderTooltip(this.font, d.upgrades().get(k), mouseX, mouseY);
                    return;
                }
            }
            return;
        }
        var data = this.squad();
        if (data == null) return;
        if (mx < RIGHT_X || mx > this.imageWidth - 78 || my < SQUAD_Y) return;
        int i = (my - SQUAD_Y) / 18, idx = this.squadPage * SQUAD_ROWS + i;
        if (i >= SQUAD_ROWS || idx >= data.rows().size()) return;
        var row = data.rows().get(idx);
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.literal(row.name()));
        if (!row.statusKey().isEmpty()) lines.add(Component.translatable(row.statusKey(), row.statusArg()));
        if (row.upgrades().isEmpty()) lines.add(Component.translatable("gui.puffcompanion.squad.no_upgrades"));
        for (var u : row.upgrades()) lines.add(Component.literal("- ").append(u.getHoverName()));
        lines.add(Component.translatable("gui.puffcompanion.squad.click_detail").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    private void small(GuiGraphics g, Component text, int x, int y, int width, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.7F, 0.7F, 1F);
        g.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), (int) (width / 0.7F)), 0, 0, color, false);
        g.pose().popPose();
    }

    private void setTab(int tab) {
        this.menu.tab = tab;
        if (tab != PuffMaidMenu.TAB_SQUAD && this.selectedSquadRat != null) {
            this.selectedSquadRat = null;
            com.puffcompanion.client.PuffSquadCache.clearDetail();
        }
        this.rebuildWidgets();
    }

    @Override
    public void removed() {
        this.selectedSquadRat = null;
        com.puffcompanion.client.PuffSquadCache.clearDetail();
        super.removed();
    }

    private void turnCurioPage(int delta) {
        this.menu.curioPage = Math.max(0, Math.min(this.menu.curioPages() - 1, this.menu.curioPage + delta));
        this.rebuildWidgets();
    }

    private void send(Action action, String arg) {
        PuffNetwork.sendToServer(new PuffInteractionPacket(this.menu.getPuffId(), action, arg));
    }

    private int stateHash() {
        PuffEntity puff = this.menu.getPuff();
        if (puff == null) return 0;
        int storyBits = 0;
        for (int i = 0; i < PuffStories.ORDER.size(); i++) {
            if (puff.isStoryDone(i)) storyBits |= (1 << i);
        }
        return Objects.hash(puff.getCurrentOutfit(), puff.getUnlockMask(), puff.isOwnerGuard(),
                puff.isSpellCastEnabled(), puff.getWorkMode(), puff.isHomeMode(), puff.getHomePos(), storyBits);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int state = this.stateHash() * 31 + this.squadHash();
        if (state != this.lastState) this.rebuildWidgets();
    }

    // ---------------- Rendering ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        // Side panel: always in the free space left of the window, shrunk to fit (never covers the window)
        float sc = Math.min(1F, Math.min((this.leftPos - 4) / (float) PuffAttributesPanel.WIDTH,
                (this.height - 8) / (float) PuffAttributesPanel.HEIGHT));
        sc = Math.max(0.3F, sc);
        int pw = Math.round(PuffAttributesPanel.WIDTH * sc), ph = Math.round(PuffAttributesPanel.HEIGHT * sc);
        int py = Math.max(4, Math.min(this.height - ph - 4, this.topPos + (this.imageHeight - ph) / 2));
        this.attrPanel.setPos(Math.max(2, this.leftPos - pw - 2), py, sc);
        this.attrPanel.update(com.puffcompanion.client.PuffStatsCache.get(this.menu.getPuffId()));
        this.attrPanel.render(g, mouseX, mouseY);
        if (!this.attrPanel.renderTooltip(g, mouseX, mouseY, this.menu.getPuff())
                && !this.attrPanel.isInside(mouseX, mouseY)) {
            this.renderTooltip(g, mouseX, mouseY);
            if (this.menu.tab == PuffMaidMenu.TAB_SQUAD && this.hoveredSlot == null) this.renderSquadTooltip(g, mouseX, mouseY);
            if (this.menu.tab == PuffMaidMenu.TAB_MEMORIES && this.menu.getPuff() != null) {
                this.renderStoryTreeTooltip(g, this.menu.getPuff(), mouseX, mouseY);
            }
            // Empty curio slot: name its slot type, like the player's Curios screen
            if (this.hoveredSlot instanceof PuffMaidMenu.CurioSlot cs && !cs.hasItem() && this.menu.getCarried().isEmpty()) {
                g.renderTooltip(this.font, Component.translatable("curios.identifier." + cs.type), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics g, int x, int y) {
        if (this.menu.getCarried().isEmpty() && this.hoveredSlot != null && this.hoveredSlot.hasItem()) {
            net.minecraft.world.item.ItemStack stack = this.hoveredSlot.getItem();
            var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && "enigmaticlegacy".equals(id.getNamespace()) && "cursed_scroll".equals(id.getPath())) {
                java.util.List<Component> tip = new java.util.ArrayList<>(this.getTooltipFromContainerItem(stack));
                if (this.hoveredSlot instanceof PuffMaidMenu.CurioSlot) {
                    int totalCurses = countPuffSharedCurses();
                    if (totalCurses > 0) {
                        tip.add(Component.empty());
                        tip.add(Component.translatable("tooltip.puffcompanion.cursed_scroll.header"));
                        tip.add(Component.translatable("tooltip.puffcompanion.cursed_scroll.damage", totalCurses * 4));
                        tip.add(Component.translatable("tooltip.puffcompanion.cursed_scroll.mining", totalCurses * 7));
                        tip.add(Component.translatable("tooltip.puffcompanion.cursed_scroll.regen", totalCurses * 4));
                    }
                    tip.add(Component.translatable("tooltip.puffcompanion.cursed_scroll_pact"));
                }
                g.renderTooltip(this.font, tip, stack.getTooltipImage(), stack, x, y);
                return;
            }
        }
        super.renderTooltip(g, x, y);
    }

    private int countPuffSharedCurses() {
        boolean puffHasRing = false;
        int gearCurses = 0;
        for (Slot s : this.menu.slots) {
            if (!(s instanceof PuffMaidMenu.CurioSlot || s instanceof PuffMaidMenu.ArmorSlot
                    || s.container instanceof com.puffcompanion.menu.PuffEquipmentContainer)) continue;
            net.minecraft.world.item.ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            var itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem());
            if (itemId != null && "enigmaticlegacy".equals(itemId.getNamespace()) && "cursed_ring".equals(itemId.getPath())) {
                puffHasRing = true;
            }
            for (var e : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(st).keySet()) {
                if (e.isCurse()) gearCurses++;
            }
        }
        boolean ownerHasRing = this.minecraft != null && this.minecraft.player != null
                && com.puffcompanion.compat.CuriosCompat.hasCurioItem(this.minecraft.player, "enigmaticlegacy", "cursed_ring");
        return (puffHasRing || ownerHasRing) ? (7 + gearCurses) : 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (this.menu.tab == PuffMaidMenu.TAB_MEMORIES && button == 0 && isInsideStoryViewport(mx, my)) {
            this.draggingStoryTree = true;
            this.storyDragMoved = 0;
            return true;
        }
        if (this.menu.tab == PuffMaidMenu.TAB_SQUAD && this.selectedSquadRat == null && button == 0) {
            var data = this.squad();
            if (data != null) {
                int relX = (int) mx - this.leftPos;
                int relY = (int) my - this.topPos;
                if (relX >= RIGHT_X && relX < this.imageWidth - 78 && relY >= SQUAD_Y && relY < SQUAD_Y + SQUAD_ROWS * 18) {
                    int i = (relY - SQUAD_Y) / 18;
                    int idx = this.squadPage * SQUAD_ROWS + i;
                    if (i >= 0 && i < SQUAD_ROWS && idx < data.rows().size()) {
                        this.openSquadDetail(data.rows().get(idx).id());
                        return true;
                    }
                }
            }
        }
        return this.attrPanel.mouseClicked(mx, my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (this.draggingStoryTree && button == 0) {
            this.draggingStoryTree = false;
            if (this.storyDragMoved < 5.0 && isInsideStoryViewport(mx, my)) {
                PuffEntity puff = this.menu.getPuff();
                if (puff != null) {
                    int ox = storyViewX() + (int) Math.round(this.storyPanX);
                    int oy = storyViewY() + (int) Math.round(this.storyPanY);
                    for (TreeNode n : this.activeStoryNodes()) {
                        int nx = ox + n.x(), ny = oy + n.y();
                        if (mx >= nx && mx <= nx + NODE_W && my >= ny && my <= ny + NODE_H) {
                            this.selectedStoryNode = n.entry();
                            if (storyDone(puff, n.entry())) {
                                this.send(Action.STORY_REPLAY, n.entry().key());
                            }
                            break;
                        }
                    }
                }
            }
            return true;
        }
        return this.attrPanel.mouseReleased(mx, my, button) || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (this.draggingStoryTree && button == 0) {
            this.storyPanX += dx;
            this.storyPanY += dy;
            this.storyDragMoved += Math.hypot(dx, dy);
            this.clampStoryPan();
            return true;
        }
        return this.attrPanel.mouseDragged(mx, my, button) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (this.menu.tab == PuffMaidMenu.TAB_MEMORIES && isInsideStoryViewport(mx, my)) {
            if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
                this.storyPanX += delta * 24.0;
            } else {
                this.storyPanY += delta * 24.0;
            }
            this.clampStoryPan();
            return true;
        }
        return this.attrPanel.mouseScrolled(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    protected boolean hasClickedOutside(double mx, double my, int left, int top, int button) {
        return !this.attrPanel.isInside(mx, my) && super.hasClickedOutside(mx, my, left, top, button);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int l = this.leftPos, t = this.topPos, r = l + this.imageWidth, b = t + this.imageHeight;
        roundedFill(g, l + 2, t + 3, r + 2, b + 3, COL_SHADOW);
        g.fillGradient(l + 1, t + 1, r - 1, b - 1, COL_BG_TOP, COL_BG_BOT);
        outline(g, l, t, r, b, COL_BORDER);
        // little mouse ears on top of the panel
        ears(g, l + 18, t);
        ears(g, l + 88, t);

        // Divider between columns
        g.fill(l + RIGHT_X - 5, t + 22, l + RIGHT_X - 4, b - 8, 0x40F2A0B8);

        // 3D preview window
        int wx0 = l + WIN_X0, wy0 = t + WIN_Y0, wx1 = l + WIN_X1, wy1 = t + WIN_Y1;
        g.fillGradient(wx0 + 1, wy0 + 1, wx1 - 1, wy1 - 1, COL_WINDOW_TOP, COL_WINDOW_BOT);
        outline(g, wx0, wy0, wx1, wy1, COL_BORDER);
        PuffEntity puff = this.menu.getPuff();
        if (puff != null) {
            g.enableScissor(wx0 + 1, wy0 + 1, wx1 - 1, wy1 - 1);
            PuffRenderer.guiPreview = true;
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, (wx0 + wx1) / 2, wy1 - 4, 34,
                        (float) ((wx0 + wx1) / 2 - mouseX), (float) (wy0 + 22 - mouseY), puff);
            } finally {
                PuffRenderer.guiPreview = false;
            }
            g.disableScissor();
        }

        // Slot frames (only on the nest page, matching the active slots)
        if (this.menu.tab == PuffMaidMenu.TAB_SQUAD) {
            if (this.selectedSquadRat != null) {
                g.fillGradient(l + RIGHT_X, t + 36, r - 6, t + 124, 0x38FFFFFF, 0x26F8CAD8);
                outline(g, l + RIGHT_X, t + 36, r - 6, t + 124, 0x70F2A0B8);
            } else {
                // one soft card per squad row
                int relX = mouseX - l, relY = mouseY - t;
                for (int i = 0; i < SQUAD_ROWS; i++) {
                    int y = t + SQUAD_Y + i * 18;
                    boolean hovered = relX >= RIGHT_X && relX < this.imageWidth - 78
                            && relY >= SQUAD_Y + i * 18 && relY < SQUAD_Y + i * 18 + 16;
                    int col = hovered ? 0x4CF8BBD0 : (i % 2 == 0 ? 0x30F8CAD8 : 0x18F8CAD8);
                    g.fill(l + RIGHT_X, y, r - 6, y + 16, col);
                }
            }
        }
        if (this.menu.tab == PuffMaidMenu.TAB_NEST || this.menu.tab == PuffMaidMenu.TAB_CURIOS || this.menu.tab == PuffMaidMenu.TAB_SQUAD) {
            for (Slot slot : this.menu.slots) {
                if (!slot.isActive()) continue;
                int sx = l + slot.x - 1, sy = t + slot.y - 1;
                boolean armor = slot instanceof PuffMaidMenu.ArmorSlot;   // inside the preview: lighter frame
                g.fill(sx, sy, sx + 18, sy + 18, armor ? 0xB0EBC2CF : COL_SLOT_BORDER);
                g.fill(sx + 1, sy + 1, sx + 17, sy + 17, armor ? 0x90FFF6F8 : COL_SLOT_BG);
            }
        } else {
            // soft content card for wardrobe / memories pages
            g.fillGradient(l + RIGHT_X, t + 23, r - 6, b - 8, 0x30FFFFFF, 0x20F8CAD8);
            outline(g, l + RIGHT_X, t + 23, r - 6, b - 8, 0x60F2A0B8);
        }
        if (this.menu.tab == PuffMaidMenu.TAB_MEMORIES && puff != null) {
            this.renderStoryTreeCanvas(g, puff, mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        PuffEntity puff = this.menu.getPuff();
        // Name + stage badge
        Component name = puff != null ? puff.getDisplayName() : this.title;
        g.drawString(this.font, name, WIN_X0 + 2, 8, COL_TITLE, false);
        if (puff != null) {
            int stage = Math.max(1, Math.min(4, puff.getGrowthStage()));
            Component badge = Component.translatable("gui.puffcompanion.maid.stage", stage);
            int bx = WIN_X0 + 4 + this.font.width(name);
            int bw = this.font.width(badge) + 8;
            g.fill(bx, 6, bx + bw, 17, COL_STAGE_BORDER);
            g.fill(bx + 1, 7, bx + bw - 1, 16, COL_STAGE_BG);
            g.drawString(this.font, badge, bx + 4, 8, COL_STAGE_TEXT, false);

            // Stat bars
            int y = WIN_Y1 + 5;
            y = this.bar(g, y, "gui.puffcompanion.maid.health", Math.round(puff.getHealth()), Math.round(puff.getMaxHealth()),
                    0xFF9BE3A4, 0xFF4CB86A);
            y = this.bar(g, y, "gui.puffcompanion.maid.affection", puff.getAffection(), 100, 0xFFFAA7C0, 0xFFE86A92);
            this.bar(g, y, "gui.puffcompanion.maid.hunger", puff.getPuffHunger(), 100, 0xFFFFD86B, 0xFFF39C2E);
            if (com.puffcompanion.compat.SpellCompat.isAnySpellModLoaded()) {
                this.small(g, Component.translatable("gui.puffcompanion.maid.mana",
                        puff.getSpellMana(), puff.getMaxSpellMana()), WIN_X0 + 3, WIN_Y1 - 9, 76, 0xFF7C4DFF);
            }
        }

        if (this.menu.tab == PuffMaidMenu.TAB_NEST) {
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.mainhand"),
                    PuffMaidMenu.EQUIP_X - 4, PuffMaidMenu.EQUIP_Y - 10, COL_DIM, false);
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.offhand"),
                    PuffMaidMenu.EQUIP_X + PuffMaidMenu.EQUIP_GAP - 4, PuffMaidMenu.EQUIP_Y - 10, COL_DIM, false);
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.nest"), RIGHT_X + 2, 24, COL_TEXT, false);
            g.drawString(this.font, this.playerInventoryTitle, PuffMaidMenu.INV_X, PuffMaidMenu.INV_Y - 10, COL_TEXT, false);
        } else if (this.menu.tab == PuffMaidMenu.TAB_WARDROBE) {
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.wardrobe.hint"), RIGHT_X + 4, 26, COL_DIM, false);
        } else if (this.menu.tab == PuffMaidMenu.TAB_STATS) {
            this.renderStats(g);
        } else if (this.menu.tab == PuffMaidMenu.TAB_SQUAD) {
            this.renderSquadLabels(g, mouseX, mouseY);
        } else if (this.menu.tab == PuffMaidMenu.TAB_CURIOS) {
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.curios.title"), RIGHT_X + 2, 24, COL_TEXT, false);
            if (this.menu.curioPages() > 1) {
                String page = (this.menu.curioPage + 1) + "/" + this.menu.curioPages();
                g.drawString(this.font, page, this.imageWidth - 33 - this.font.width(page) / 2 + 3, 24, COL_DIM, false);
            }
            for (PuffMaidMenu.CurioRow row : this.menu.curioRows()) {
                if (row.page() != this.menu.curioPage || row.firstIndex() != 0) continue;
                Component rowName = Component.translatable("curios.identifier." + row.type());
                int y = PuffMaidMenu.CURIO_Y + row.rowInPage() * 18 + 4;
                g.pose().pushPose();
                g.pose().translate(RIGHT_X + 1, y, 0);
                g.pose().scale(0.7F, 0.7F, 1.0F);
                g.drawString(this.font, this.font.plainSubstrByWidth(rowName.getString(), 56), 0, 0, COL_ACCENT, false);
                g.pose().popPose();
            }
            g.drawString(this.font, this.playerInventoryTitle, PuffMaidMenu.INV_X, PuffMaidMenu.INV_Y - 10, COL_TEXT, false);
        }
    }

    /** Two small-print columns: growth on the left, live attribute values on the right. */
    private void renderStats(GuiGraphics g) {
        com.puffcompanion.network.PuffStatsPacket st = com.puffcompanion.client.PuffStatsCache.get(this.menu.getPuffId());
        float sc = 0.7F;
        int lh = 9;
        if (st == null) {
            g.drawString(this.font, Component.translatable("gui.puffcompanion.maid.stats.loading"), RIGHT_X + 4, 28, COL_DIM, false);
            return;
        }
        java.util.List<Component> left = new java.util.ArrayList<>();
        left.add(Component.translatable("gui.puffcompanion.maid.stats.level", st.level(), st.levelCap()));
        String[] growthKeys = {"health", "damage", "armor", "speed"};
        for (int i = 0; i < growthKeys.length; i++) {
            left.add(Component.translatable("gui.puffcompanion.maid.stats.growth." + growthKeys[i],
                    fmt(st.growthNow()[i]), fmt(st.growthCap()[i])));
        }
        left.add(Component.translatable("gui.puffcompanion.maid.stats.regen", fmt(st.regen())));
        left.add(Component.translatable("gui.puffcompanion.maid.stats.genes"));
        com.puffcompanion.entity.PuffProgression.Gene[] genes = com.puffcompanion.entity.PuffProgression.Gene.values();
        for (int i = 0; i < genes.length && i < st.genes().length; i++) {
            left.add(Component.translatable("gui.puffcompanion.maid.stats.gene", Component.translatable("puff.gene." + genes[i].key()),
                    String.format(java.util.Locale.ROOT, "%.2f", st.genes()[i])));
        }
        java.util.List<Component> right = new java.util.ArrayList<>();
        right.add(Component.translatable("gui.puffcompanion.maid.stats.attributes"));
        st.attributes().forEach((id, v) -> {
            net.minecraft.world.entity.ai.attributes.Attribute attr = net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(id);
            Component name = attr != null ? Component.translatable(attr.getDescriptionId()) : Component.literal(id.getPath());
            String bonus = Math.abs(v[0] - v[1]) > 1e-4 ? " (" + (v[0] >= v[1] ? "+" : "") + fmt(v[0] - v[1]) + ")" : "";
            right.add(Component.empty().append(name).append(": " + fmt(v[0]) + bonus));
        });
        this.column(g, left, RIGHT_X + 4, 27, 74, sc, lh);
        this.column(g, right, RIGHT_X + 84, 27, 92, sc, lh);
    }

    private void column(GuiGraphics g, java.util.List<Component> lines, int x, int y, int width, float sc, int lh) {
        int max = (int) ((this.imageHeight - 8 - y) / (lh * sc));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(sc, sc, 1.0F);
        int maxW = (int) (width / sc);
        for (int i = 0; i < lines.size() && i < max; i++) {
            boolean header = i == 0 || lines.get(i).getString().startsWith("\u2014");
            String text = this.font.plainSubstrByWidth(lines.get(i).getString(), maxW);
            g.drawString(this.font, text, 0, i * lh, header ? COL_ACCENT : COL_TEXT, false);
        }
        if (lines.size() > max) {
            g.drawString(this.font, "+" + (lines.size() - max), 0, max * lh, COL_DIM, false);
        }
        g.pose().popPose();
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, Math.abs(v) < 1 ? "%.3f" : "%.1f", v);
    }

    /** Label + gradient bar; returns next y. */
    private int bar(GuiGraphics g, int y, String key, int value, int max, int cL, int cR) {
        Component label = Component.translatable(key, value, max);
        g.drawString(this.font, label, WIN_X0 + 1, y, COL_TEXT, false);
        int by = y + 9;
        int bw = WIN_X1 - WIN_X0 - 2;
        g.fill(WIN_X0 + 1, by, WIN_X0 + 1 + bw, by + 3, COL_BAR_BG);
        int fw = max <= 0 ? 0 : Math.round(bw * Math.max(0, Math.min(max, value)) / (float) max);
        if (fw > 0) g.fillGradient(WIN_X0 + 1, by, WIN_X0 + 1 + fw, by + 3, cL, cR);
        return by + 5;
    }

    private static void roundedFill(GuiGraphics g, int l, int t, int r, int b, int col) {
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

    private static void ears(GuiGraphics g, int cx, int top) {
        for (int x : new int[]{cx - 6, cx + 6}) {
            g.fill(x - 3, top - 4, x + 3, top + 1, COL_BORDER);
            g.fill(x - 2, top - 5, x + 2, top - 4, COL_BORDER);
            g.fill(x - 2, top - 3, x + 2, top, 0xFFFAD2DF);
        }
    }

    /** Cream / sakura / amber button used for tabs, outfits and memories. */
    private static final class PanelButton extends AbstractButton {
        private final Runnable action;
        private final boolean highlighted;
        private final boolean amber;

        PanelButton(int x, int y, int w, int h, Component msg, Runnable action, boolean highlighted, boolean amber) {
            super(x, y, w, h, msg);
            this.action = action;
            this.highlighted = highlighted;
            this.amber = amber;
        }

        @Override
        public void onPress() {
            this.action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int l = this.getX(), t = this.getY(), r = l + this.width, b = t + this.height;
            int top, bot, border, text;
            if (this.highlighted) {
                top = this.amber ? 0xFAFFF1C2 : 0xFAFFE3EC;
                bot = this.amber ? 0xFAFFE18C : 0xFAFCC8D8;
                border = this.amber ? 0xFFE5A93B : 0xFFE87298;
                text = this.amber ? 0xFF7A4E10 : 0xFF6B2A3E;
            } else if (!this.active) {
                top = 0xE8F3ECEF; bot = 0xE8EAE0E4; border = 0xFFD9C7CE; text = 0xFFA29298;
            } else if (this.isHoveredOrFocused()) {
                top = 0xFAFFECF2; bot = 0xFAFDD6E2; border = 0xFFEB7D9F; text = 0xFF5A2A37;
            } else {
                top = 0xF2FFFFFF; bot = 0xF2FFF7FA; border = 0xFFF0BACB; text = COL_TEXT;
            }
            g.fillGradient(l + 1, t + 1, r - 1, b - 1, top, bot);
            outline(g, l, t, r, b, border);
            if (this.active && this.isHoveredOrFocused() && !this.highlighted) {
                g.fill(l + 2, t + 3, l + 4, b - 3, 0xFFF3A833);
            }
            Font font = Minecraft.getInstance().font;
            Component msg = this.getMessage();
            int tw = font.width(msg);
            g.drawString(font, msg, l + Math.max(4, (this.width - tw) / 2), t + (this.height - 8) / 2, text, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            this.defaultButtonNarrationText(out);
        }
    }

    @Nullable
    public PuffEntity puff() {
        return this.menu.getPuff();
    }
}
