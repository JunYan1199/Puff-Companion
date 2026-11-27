package com.puffcompanion.client.gui;

import com.puffcompanion.client.PuffVoicePlayer;
import com.puffcompanion.client.ai.PuffAiChatService;
import com.puffcompanion.client.ai.PuffAiConfig;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffOutfit;
import com.puffcompanion.entity.PuffTopic;
import com.puffcompanion.network.PuffInteractionPacket;
import com.puffcompanion.network.PuffInteractionPacket.Action;
import com.puffcompanion.network.PuffNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Galgame-style interaction overlay for an owned Puff.
 * Leaves the center/upper viewport completely clear so the player can watch Puff directly
 * while talking, petting, gifting, or changing outfits via the bottom dialogue box and
 * floating right-side choice menu.
 */
public class PuffInteractionScreen extends Screen {
    private enum MenuTab {
        NONE, ACTIONS, TOPICS, OUTFITS, FREE_CHAT
    }

    private static final int BOX_MAX_W = 380;
    private static final int BOX_H = 68;
    private static final int BOTTOM_MARGIN = 8;
    private static final int MENU_COL_W = 82;
    private static final int MENU_COL_GAP = 3;
    private static final int MENU_W = 122;
    private static final int MENU_BTN_H = 14;
    private static final int MENU_STEP = 16;

    // Cream + Sakura Pink + Warm Amber Gold palette (translucent glass style)
    private static final int COL_SHADOW = 0x2A1D1218;
    private static final int COL_BOX_TOP = 0xC6FFFDF9;
    private static final int COL_BOX_BOT = 0xC6FFF3F6;
    private static final int COL_BORDER = 0xCCF2A0B8;
    private static final int COL_ACCENT = 0xDCF08CA8;
    private static final int COL_NAME_TOP = 0xCCFDE2EB;
    private static final int COL_NAME_BOT = 0xCCF8CAD8;
    private static final int COL_TITLE = 0xFF543339;
    private static final int COL_TEXT = 0xFF4A332D;
    private static final int COL_DIM = 0xFF9C848B;
    private static final int COL_STAGE_BG = 0xFFFFEAB3;
    private static final int COL_STAGE_BORDER = 0xFFE8B44C;
    private static final int COL_STAGE_TEXT = 0xFF7A4E10;
    private static final int COL_BAR_BG = 0xFFEED9E0;
    private static final int COL_AFFECTION_L = 0xFFFAA7C0;
    private static final int COL_AFFECTION_R = 0xFFE86A92;
    private static final int COL_HUNGER_L = 0xFFFFD86B;
    private static final int COL_HUNGER_R = 0xFFF39C2E;

    private final PuffEntity puff;
    private MenuTab activeTab = MenuTab.ACTIONS;
    private boolean aiSettingsOpen = false;
    private String editingProvider = PuffAiConfig.PROVIDER_GEMINI;
    @Nullable private EditBox chatInput;
    @Nullable private EditBox endpointInput;
    @Nullable private EditBox apiKeyInput;
    @Nullable private EditBox modelInput;
    @Nullable private EditBox proxyInput;
    private int boxLeft;
    private int boxTop;
    private int boxW;
    private int lastState;

    // Typewriter state for dialogue text
    private String trackedLine = "";
    private int shownChars = 0;
    private int tickCounter = 0;

    public PuffInteractionScreen(PuffEntity puff) {
        super(Component.translatable("gui.puffcompanion.title", puff.getDisplayName()));
        this.puff = puff;
        String initial = puff.getBubbleText();
        if (initial != null) {
            this.trackedLine = initial;
            this.shownChars = initial.length(); // already spoken before opening: show immediately
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        this.boxW = Math.min(this.width - 28, BOX_MAX_W);
        this.boxLeft = (this.width - this.boxW) / 2;
        this.boxTop = this.height - BOX_H - BOTTOM_MARGIN;
        this.lastState = this.stateHash();

        int boxRight = this.boxLeft + this.boxW;

        // Top-right floating tab strip just above the dialogue box
        int tabY = this.boxTop - 19;
        int closeW = 16;
        int callW = 58;
        int outfitW = 36;
        int topicW = 46;
        int actionW = 36;
        int freeChatW = 44;
        int gap = 3;

        int x = boxRight - closeW;
        this.addRenderableWidget(new GalButton(
                x, tabY, closeW, 16, Component.literal("×"),
                b -> this.onClose(), false, false, null));

        x -= (callW + gap);
        String callKey = PuffEntity.CALL_SISTER.equals(this.puff.getCallMode()) ? "puff.call.sister" : "puff.call.brother";
        GalButton callBtn = new GalButton(
                x, tabY, callW, 16,
                Component.translatable("gui.puffcompanion.call", Component.translatable(callKey)),
                b -> this.send(Action.CALL_MODE, ""), false, false, null);
        callBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.call.tooltip")));
        this.addRenderableWidget(callBtn);

        x -= (outfitW + gap);
        this.addRenderableWidget(new GalButton(
                x, tabY, outfitW, 16, Component.translatable("gui.puffcompanion.section.outfit"),
                b -> this.toggleTab(MenuTab.OUTFITS), this.activeTab == MenuTab.OUTFITS, false, null));

        x -= (topicW + gap);
        this.addRenderableWidget(new GalButton(
                x, tabY, topicW, 16, Component.translatable("gui.puffcompanion.section.chat"),
                b -> this.toggleTab(MenuTab.TOPICS), this.activeTab == MenuTab.TOPICS, false, null));

        x -= (actionW + gap);
        this.addRenderableWidget(new GalButton(
                x, tabY, actionW, 16, Component.translatable("gui.puffcompanion.section.actions"),
                b -> this.toggleTab(MenuTab.ACTIONS), this.activeTab == MenuTab.ACTIONS, false, null));

        x -= (freeChatW + gap);
        GalButton freeChatBtn = new GalButton(
                x, tabY, freeChatW, 16, Component.translatable("gui.puffcompanion.section.free_chat"),
                b -> this.toggleTab(MenuTab.FREE_CHAT), this.activeTab == MenuTab.FREE_CHAT, false, null);
        freeChatBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.free_chat.tooltip")));
        this.addRenderableWidget(freeChatBtn);

        // Voice volume slider nestled cleanly inside the top-left header of the dialogue box
        GalVolumeSlider volSlider = new GalVolumeSlider(
                this.boxLeft + 13, this.boxTop + 5, 104, 12, PuffAiConfig.get().voiceVolume);
        volSlider.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.voice.tooltip")));
        this.addRenderableWidget(volSlider);

        this.chatInput = null;
        this.endpointInput = null;
        this.apiKeyInput = null;
        this.modelInput = null;
        this.proxyInput = null;

        // Floating right-side Galgame choice stack (leaves center Puff completely unobstructed)
        int menuX = boxRight - MENU_W;
        int menuAnchorBottom = tabY - 6;

        if (this.activeTab == MenuTab.ACTIONS) {
            this.buildActionChoices(menuX, menuAnchorBottom);
        } else if (this.activeTab == MenuTab.TOPICS) {
            this.buildTopicChoices(menuX, menuAnchorBottom);
        } else if (this.activeTab == MenuTab.OUTFITS) {
            this.buildOutfitChoices(menuX, menuAnchorBottom);
        } else if (this.activeTab == MenuTab.FREE_CHAT) {
            this.buildFreeChatBar(tabY);
        }
    }

    private void buildFreeChatBar(int tabY) {
        PuffAiConfig cfg = PuffAiConfig.get();
        if (!cfg.hasKey()) {
            this.aiSettingsOpen = true;
        }
        int barY = tabY - 22;
        if (!this.aiSettingsOpen) {
            int cfgW = 24;
            int modelW = 58;
            int sendW = 40;
            int inputW = Math.max(60, this.boxW - cfgW - modelW - sendW - 9);
            this.chatInput = new EditBox(this.font, this.boxLeft, barY, inputW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.placeholder"));
            this.chatInput.setMaxLength(200);
            this.chatInput.setHint(Component.translatable("gui.puffcompanion.free_chat.placeholder"));
            this.addRenderableWidget(this.chatInput);
            this.setInitialFocus(this.chatInput);

            GalButton sendBtn = new GalButton(
                    this.boxLeft + inputW + 3, barY, sendW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.send"),
                    b -> this.submitFreeChat(), true, false, null);
            this.addRenderableWidget(sendBtn);

            GalButton modelBtn = new GalButton(
                    this.boxLeft + inputW + sendW + 6, barY, modelW, 16,
                    Component.literal(PuffAiConfig.shortModelLabel(cfg.model)),
                    b -> {
                        cfg.model = PuffAiConfig.nextPresetModel(cfg.provider, cfg.model);
                        cfg.save();
                        this.puff.showBubble(net.minecraft.client.resources.language.I18n.get("puff.ai.model_switched", cfg.model), 100);
                        PuffVoicePlayer.playBubble(this.puff, "puff.ai.model_switched");
                        this.rebuildWidgets();
                    }, false, false, null);
            modelBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.free_chat.model_tip", cfg.model)));
            this.addRenderableWidget(modelBtn);

            GalButton cfgBtn = new GalButton(
                    this.boxLeft + inputW + sendW + modelW + 9, barY, cfgW, 16,
                    Component.literal("⚙"),
                    b -> { this.aiSettingsOpen = true; this.rebuildWidgets(); }, false, false, null);
            cfgBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.free_chat.config_tip")));
            this.addRenderableWidget(cfgBtn);
        } else {
            this.editingProvider = PuffAiConfig.normalizeProvider(cfg.provider);

            int providerY = tabY - 82;
            int providerW = 72;
            int endpointW = Math.max(80, this.boxW - providerW - 3);
            GalButton providerBtn = new GalButton(
                    this.boxLeft, providerY, providerW, 16,
                    Component.literal(PuffAiConfig.providerLabel(this.editingProvider)),
                    b -> {
                        this.editingProvider = PuffAiConfig.nextProvider(this.editingProvider);
                        b.setMessage(Component.literal(PuffAiConfig.providerLabel(this.editingProvider)));
                        if (this.endpointInput != null) {
                            this.endpointInput.setValue(PuffAiConfig.defaultEndpointFor(this.editingProvider));
                        }
                        if (this.modelInput != null) {
                            this.modelInput.setValue(PuffAiConfig.defaultModelFor(this.editingProvider));
                        }
                    }, false, false, null);
            providerBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.free_chat.provider_tip")));
            this.addRenderableWidget(providerBtn);

            this.endpointInput = new EditBox(this.font, this.boxLeft + providerW + 3, providerY, endpointW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.endpoint_hint"));
            this.endpointInput.setMaxLength(256);
            String initialEndpoint = cfg.customEndpoint.isBlank()
                    ? PuffAiConfig.defaultEndpointFor(this.editingProvider)
                    : cfg.customEndpoint;
            this.endpointInput.setValue(initialEndpoint);
            this.endpointInput.setHint(Component.translatable("gui.puffcompanion.free_chat.endpoint_hint"));
            this.addRenderableWidget(this.endpointInput);

            int keyY = tabY - 62;
            this.apiKeyInput = new EditBox(this.font, this.boxLeft, keyY, this.boxW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.key_hint"));
            this.apiKeyInput.setMaxLength(256);
            this.apiKeyInput.setValue(cfg.apiKey);
            this.apiKeyInput.setHint(Component.translatable("gui.puffcompanion.free_chat.key_hint"));
            this.addRenderableWidget(this.apiKeyInput);
            this.setInitialFocus(this.apiKeyInput);

            int modelY = tabY - 42;
            int presetW = 64;
            int modelInputW = Math.max(80, this.boxW - presetW - 3);
            this.modelInput = new EditBox(this.font, this.boxLeft, modelY, modelInputW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.model_hint"));
            this.modelInput.setMaxLength(128);
            this.modelInput.setValue(cfg.model);
            this.modelInput.setHint(Component.translatable("gui.puffcompanion.free_chat.model_hint"));
            this.addRenderableWidget(this.modelInput);

            GalButton presetBtn = new GalButton(
                    this.boxLeft + modelInputW + 3, modelY, presetW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.model_cycle"),
                    b -> {
                        String cur = this.modelInput != null ? this.modelInput.getValue().trim() : cfg.model;
                        String next = PuffAiConfig.nextPresetModel(this.editingProvider, cur);
                        if (this.modelInput != null) {
                            this.modelInput.setValue(next);
                        }
                    }, false, false, null);
            presetBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.free_chat.model_cycle_tip")));
            this.addRenderableWidget(presetBtn);

            int saveW = 46;
            int backW = 40;
            int proxyW = Math.max(80, this.boxW - saveW - backW - 6);
            this.proxyInput = new EditBox(this.font, this.boxLeft, barY, proxyW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.proxy_hint"));
            this.proxyInput.setMaxLength(128);
            this.proxyInput.setValue(cfg.proxy);
            this.proxyInput.setHint(Component.translatable("gui.puffcompanion.free_chat.proxy_hint"));
            this.addRenderableWidget(this.proxyInput);

            this.addRenderableWidget(new GalButton(
                    this.boxLeft + proxyW + 3, barY, saveW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.save"),
                    b -> {
                        cfg.provider = PuffAiConfig.normalizeProvider(this.editingProvider);
                        cfg.customEndpoint = this.endpointInput != null ? this.endpointInput.getValue().trim() : cfg.customEndpoint;
                        cfg.apiKey = this.apiKeyInput != null ? this.apiKeyInput.getValue().trim() : cfg.apiKey;
                        String mVal = this.modelInput != null ? this.modelInput.getValue().trim() : cfg.model;
                        cfg.model = mVal.isEmpty() ? PuffAiConfig.defaultModelFor(cfg.provider) : mVal;
                        cfg.proxy = this.proxyInput != null ? this.proxyInput.getValue().trim() : cfg.proxy;
                        cfg.save();
                        this.aiSettingsOpen = false;
                        this.puff.showBubble(net.minecraft.client.resources.language.I18n.get("puff.ai.saved"), 100);
                        PuffVoicePlayer.playBubble(this.puff, "puff.ai.saved");
                        this.rebuildWidgets();
                    }, true, false, null));

            this.addRenderableWidget(new GalButton(
                    this.boxLeft + proxyW + saveW + 6, barY, backW, 16,
                    Component.translatable("gui.puffcompanion.free_chat.back"),
                    b -> { this.aiSettingsOpen = false; this.rebuildWidgets(); }, false, false, null));
        }
    }

    private void submitFreeChat() {
        if (this.chatInput == null || PuffAiChatService.isBusy()) return;
        String msg = this.chatInput.getValue().trim();
        if (msg.isEmpty()) return;
        this.chatInput.setValue("");
        PuffAiChatService.send(this.puff, msg);
    }

    private void buildActionChoices(int menuX, int anchorBottom) {
        Player player = this.minecraft != null ? this.minecraft.player : null;
        ItemStack hand = player != null ? player.getMainHandItem() : ItemStack.EMPTY;
        boolean onShoulder = this.puff.getVehicle() == player;
        boolean waiting = (this.puff.isInSittingPose() || this.puff.isResting()) && !onShoulder;

        // 2-column compact grid anchored cleanly above the tab strip (never overlaps tabs)
        boolean canPropose = this.puff.getGrowthStage() == 3 && this.puff.getAffection() >= 100;
        int gridW = MENU_COL_W * 2 + MENU_COL_GAP;
        int gridLeft = (this.boxLeft + this.boxW) - gridW;
        int rows = canPropose ? 5 : 4;
        int startY = Math.max(4, anchorBottom - rows * MENU_STEP + 2);

        java.util.List<GalButton> items = new java.util.ArrayList<>();
        GalButton gift = new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable("gui.puffcompanion.gift"),
                b -> this.send(Action.GIFT, ""), false, false, hand.isEmpty() ? null : hand.copyWithCount(1));
        gift.active = !hand.isEmpty();
        gift.setTooltip(Tooltip.create(hand.isEmpty()
                ? Component.translatable("gui.puffcompanion.gift.empty")
                : Component.translatable("gui.puffcompanion.gift.tooltip", hand.getHoverName())));
        items.add(gift);

        items.add(new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable(waiting ? "gui.puffcompanion.follow" : "gui.puffcompanion.stay"),
                b -> this.send(Action.TOGGLE_FOLLOW, ""), false, false, null));

        items.add(new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable(this.puff.isResting() ? "gui.puffcompanion.stand" : "gui.puffcompanion.rest"),
                b -> this.send(Action.TOGGLE_REST, ""), false, false, null));

        GalButton shoulderBtn = new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable(onShoulder ? "gui.puffcompanion.shoulder.off" : "gui.puffcompanion.shoulder"),
                b -> this.send(Action.SHOULDER, ""), onShoulder, false, null);
        shoulderBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.shoulder.tooltip")));
        items.add(shoulderBtn);

        GalButton petBtn = new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable("gui.puffcompanion.pet"),
                b -> this.send(Action.PET, ""), false, false, null);
        petBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.pet.tooltip")));
        items.add(petBtn);

        GalButton hoardBtn = new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable("gui.puffcompanion.hoard"),
                b -> this.send(Action.HOARD, ""), false, false, null);
        hoardBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.hoard.tooltip")));
        items.add(hoardBtn);

        var mode = this.puff.getWorkMode();
        GalButton modeBtn = new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable("gui.puffcompanion.mode_btn", Component.translatable(mode.langKey())),
                b -> this.send(Action.CYCLE_MODE, ""),
                mode != com.puffcompanion.entity.PuffWorkMode.FOLLOW_GUARD, true, null);
        modeBtn.setTooltip(Tooltip.create(Component.translatable(mode.descKey())));
        items.add(modeBtn);

        boolean homeOn = this.puff.isHomeMode();
        GalButton homeBtn = new GalButton(
                0, 0, MENU_COL_W, MENU_BTN_H,
                Component.translatable(homeOn ? "gui.puffcompanion.home.on" : "gui.puffcompanion.home.off"),
                b -> this.send(Action.TOGGLE_HOME, ""),
                homeOn, true, null);
        homeBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.home.tooltip")));
        items.add(homeBtn);

        if (canPropose) {
            GalButton proposeBtn = new GalButton(
                    0, 0, gridW, MENU_BTN_H,
                    Component.translatable("gui.puffcompanion.propose"),
                    b -> this.send(Action.PROPOSE, ""), true, true, null);
            proposeBtn.setTooltip(Tooltip.create(Component.translatable("gui.puffcompanion.propose.tooltip")));
            proposeBtn.setPosition(gridLeft, startY + 4 * MENU_STEP);
            this.addRenderableWidget(proposeBtn);
        }

        for (int i = 0; i < items.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            GalButton btn = items.get(i);
            btn.setPosition(gridLeft + col * (MENU_COL_W + MENU_COL_GAP), startY + row * MENU_STEP);
            this.addRenderableWidget(btn);
        }
    }

    private void buildTopicChoices(int menuX, int anchorBottom) {
        PuffTopic[] topics = PuffTopic.values();
        int startY = anchorBottom - topics.length * MENU_STEP + 2;
        for (int i = 0; i < topics.length; i++) {
            PuffTopic topic = topics[i];
            boolean unlocked = topic.isUnlocked(this.puff);
            Component label = unlocked
                    ? Component.translatable(topic.titleKey())
                    : Component.translatable("gui.puffcompanion.locked", Component.translatable(topic.titleKey()));
            GalButton b = new GalButton(
                    menuX, startY + i * MENU_STEP, MENU_W, MENU_BTN_H,
                    label, btn -> this.send(Action.TOPIC, topic.id()), false, false, null);
            b.active = unlocked;
            if (!unlocked) {
                b.setTooltip(Tooltip.create(Component.translatable(topic.lockedKey())));
            }
            this.addRenderableWidget(b);
        }
    }

    private void buildOutfitChoices(int menuX, int anchorBottom) {
        PuffOutfit[] outfits = PuffOutfit.values();
        int startY = anchorBottom - outfits.length * MENU_STEP + 2;
        for (int i = 0; i < outfits.length; i++) {
            PuffOutfit outfit = outfits[i];
            boolean unlocked = this.puff.isOutfitUnlocked(outfit);
            boolean current = outfit.id().equals(this.puff.getCurrentOutfit());
            Component label = current
                    ? Component.translatable("gui.puffcompanion.outfit.current_label", Component.translatable(outfit.nameKey()))
                    : Component.translatable(outfit.nameKey());
            Component tip = !unlocked
                    ? Component.translatable("gui.puffcompanion.outfit.locked", Component.translatable(outfit.nameKey()))
                    : Component.translatable(current ? "gui.puffcompanion.outfit.current" : "gui.puffcompanion.outfit.switch",
                    Component.translatable(outfit.nameKey()));
            GalButton b = new GalButton(
                    menuX, startY + i * MENU_STEP, MENU_W, MENU_BTN_H,
                    label, btn -> this.send(Action.OUTFIT, outfit.id()), current, true, null);
            b.active = unlocked && !current;
            b.setTooltip(Tooltip.create(tip));
            this.addRenderableWidget(b);
        }
    }

    private void toggleTab(MenuTab tab) {
        this.activeTab = (this.activeTab == tab) ? MenuTab.NONE : tab;
        this.rebuildWidgets();
    }

    private void send(Action action, String arg) {
        PuffNetwork.sendToServer(new PuffInteractionPacket(this.puff.getId(), action, arg));
    }

    private int stateHash() {
        Player player = this.minecraft != null ? this.minecraft.player : null;
        ItemStack hand = player != null ? player.getMainHandItem() : ItemStack.EMPTY;
        return Objects.hash(this.puff.isInSittingPose(), this.puff.isResting(), this.puff.getVehicle() == player,
                this.puff.getCurrentOutfit(), this.puff.getCallMode(), this.puff.getUnlockMask(),
                this.puff.getGrowthStage(), this.puff.getWorkMode(), this.puff.isHomeMode(), hand.isEmpty(), hand.getItem());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.chatInput != null) this.chatInput.tick();
        if (this.endpointInput != null) this.endpointInput.tick();
        if (this.apiKeyInput != null) this.apiKeyInput.tick();
        if (this.modelInput != null) this.modelInput.tick();
        if (this.proxyInput != null) this.proxyInput.tick();
        this.tickCounter++;
        Player player = this.minecraft != null ? this.minecraft.player : null;
        if (!this.puff.isAlive() || player == null
                || (this.puff.getVehicle() != player && this.puff.distanceToSqr(player) > 10.0 * 10.0)) {
            this.onClose();
            return;
        }
        this.puff.faceEntityClient(player);

        String latest = this.puff.getBubbleText();
        String safeLatest = latest != null ? latest : "";
        if (!safeLatest.equals(this.trackedLine)) {
            this.trackedLine = safeLatest;
            this.shownChars = 0;
        } else if (this.shownChars < this.trackedLine.length()) {
            this.shownChars = Math.min(this.trackedLine.length(), this.shownChars + 2);
        }

        int state = this.stateHash();
        if (state != this.lastState) {
            if (this.activeTab != MenuTab.FREE_CHAT) {
                this.rebuildWidgets();
            } else {
                this.lastState = state;
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.chatInput != null && this.chatInput.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            this.submitFreeChat();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if ((this.chatInput != null && this.chatInput.isFocused())
                || (this.endpointInput != null && this.endpointInput.isFocused())
                || (this.apiKeyInput != null && this.apiKeyInput.isFocused())
                || (this.modelInput != null && this.modelInput.isFocused())
                || (this.proxyInput != null && this.proxyInput.isFocused())) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // Clicking the dialogue box skips typewriter animation
        if (button == 0 && mouseX >= this.boxLeft && mouseX <= this.boxLeft + this.boxW
                && mouseY >= this.boxTop && mouseY <= this.boxTop + BOX_H) {
            if (this.shownChars < this.trackedLine.length()) {
                this.shownChars = this.trackedLine.length();
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int l = this.boxLeft, t = this.boxTop, r = l + this.boxW, b = t + BOX_H;

        // 1. Bottom Galgame Dialogue Box (no full-screen dim, world & Puff remain crystal clear!)
        drawRoundedCard(g, l + 2, t + 3, r + 2, b + 3, COL_SHADOW, 0);
        g.fillGradient(l + 1, t + 1, r - 1, b - 1, COL_BOX_TOP, COL_BOX_BOT);
        drawRoundedOutline(g, l, t, r, b, COL_BORDER);
        g.fill(l + 3, t + 6, l + 6, b - 6, COL_ACCENT);

        // 2. Top-left Nameplate + Stage Badge + Mini Affection/Hunger Pills
        Component name = this.puff.getDisplayName();
        int nameW = Math.max(44, this.font.width(name) + 18);
        int nt = t - 19, nb = t - 1;
        g.fillGradient(l + 1, nt + 1, l + nameW - 1, nb, COL_NAME_TOP, COL_NAME_BOT);
        drawRoundedOutline(g, l, nt, l + nameW, nb + 1, COL_BORDER);
        g.drawString(this.font, name, l + 9, nt + 5, COL_TITLE, false);

        int stage = Math.max(1, Math.min(4, this.puff.getGrowthStage()));
        Component stageComp = Component.translatable("puff.stage." + stage);
        int sx = l + nameW + 4;
        int sw = this.font.width(stageComp) + 10;
        g.fill(sx, nt + 2, sx + sw, nb - 1, COL_STAGE_BORDER);
        g.fill(sx + 1, nt + 3, sx + sw - 1, nb - 2, COL_STAGE_BG);
        g.drawString(this.font, stageComp, sx + 5, nt + 5, COL_STAGE_TEXT, false);

        // Compact affection & hunger indicators inside top-right of the dialogue box
        int statX = r - 134;
        drawMiniStat(g, statX, t + 7, "♥ " + this.puff.getAffection(), this.puff.getAffection(),
                COL_AFFECTION_L, COL_AFFECTION_R, 0xFFE86A92);
        drawMiniStat(g, statX + 66, t + 7, "◆ " + this.puff.getPuffHunger(), this.puff.getPuffHunger(),
                COL_HUNGER_L, COL_HUNGER_R, 0xFFD98218);

        // 3. Dialogue Text (typewriter)
        int textX = l + 13;
        int textY = t + 21;
        int textMaxW = this.boxW - 26;
        if (!this.trackedLine.isEmpty()) {
            int end = Math.min(this.shownChars, this.trackedLine.length());
            String visible = this.trackedLine.substring(0, end);
            List<FormattedCharSequence> lines = this.font.split(Component.literal(visible), textMaxW);
            for (int i = 0; i < Math.min(4, lines.size()); i++) {
                g.drawString(this.font, lines.get(i), textX, textY + i * 12, COL_TEXT, false);
            }
            if (end >= this.trackedLine.length() && (this.tickCounter / 12) % 2 == 0) {
                g.drawString(this.font, "▼", r - 15, b - 14, COL_ACCENT, false);
            }
        } else {
            g.drawString(this.font, Component.translatable("gui.puffcompanion.dialogue.hint"),
                    textX, textY + 6, COL_DIM, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawMiniStat(GuiGraphics g, int x, int y, String label, int value, int cL, int cR, int labelCol) {
        g.drawString(this.font, label, x, y, labelCol, false);
        int bx = x + this.font.width(label) + 4;
        int bw = 28;
        int by = y + 2;
        g.fill(bx, by, bx + bw, by + 4, COL_BAR_BG);
        int fw = Math.round(bw * Math.max(0, Math.min(100, value)) / 100.0F);
        if (fw > 0) {
            g.fill(bx, by, bx + fw, by + 4, cL);
            if (fw > 2) g.fill(bx + fw / 2, by, bx + fw, by + 4, cR);
        }
    }

    private static void drawRoundedCard(GuiGraphics g, int l, int t, int r, int b, int fill, int border) {
        g.fill(l + 1, t, r - 1, b, fill);
        g.fill(l, t + 1, l + 1, b - 1, fill);
        g.fill(r - 1, t + 1, r, b - 1, fill);
        if (border != 0) drawRoundedOutline(g, l, t, r, b, border);
    }

    private static void drawRoundedOutline(GuiGraphics g, int l, int t, int r, int b, int col) {
        g.fill(l + 1, t, r - 1, t + 1, col);
        g.fill(l + 1, b - 1, r - 1, b, col);
        g.fill(l, t + 1, l + 1, b - 1, col);
        g.fill(r - 1, t + 1, r, b - 1, col);
    }

    @Override
    public void removed() {
        PuffAiConfig.get().save();
        super.removed();
    }

    /** Sakura-pink Galgame voice volume slider (0% = muted, 100% = full voice volume). */
    private static final class GalVolumeSlider extends AbstractSliderButton {
        GalVolumeSlider(int x, int y, int w, int h, float currentVol) {
            super(x, y, w, h, Component.empty(), Math.max(0.0, Math.min(1.0, currentVol)));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            int pct = (int) Math.round(this.value * 100.0);
            if (pct <= 0) {
                this.setMessage(Component.translatable("gui.puffcompanion.voice.muted"));
            } else {
                this.setMessage(Component.translatable("gui.puffcompanion.voice.volume", pct));
            }
        }

        @Override
        protected void applyValue() {
            PuffAiConfig.get().voiceVolume = (float) Math.max(0.0, Math.min(1.0, this.value));
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            PuffAiConfig.get().save();
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            if (!this.active || !this.visible || !this.isHovered()) return false;
            double step = delta > 0 ? 0.05 : -0.05;
            this.value = Math.max(0.0, Math.min(1.0, this.value + step));
            this.applyValue();
            this.updateMessage();
            PuffAiConfig.get().save();
            return true;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int l = this.getX(), t = this.getY(), r = l + this.width, b = t + this.height;
            boolean hot = this.isHoveredOrFocused();

            // Cream rounded track + sakura border
            g.fill(l + 1, t + 1, r - 1, b - 1, hot ? 0xEEFFF0F5 : 0xDDFFF7FA);
            drawRoundedOutline(g, l, t, r, b, hot ? 0xCCEB7D9F : 0xCCF0BACB);

            // Sakura-pink filled bar
            int innerW = Math.max(0, this.width - 2);
            int fillW = (int) Math.round(innerW * Math.max(0.0, Math.min(1.0, this.value)));
            if (fillW > 0) {
                g.fillGradient(l + 1, t + 1, l + 1 + fillW, b - 1, 0xFFFDD5E3, 0xFFF6A5C1);
            }

            // Cute thumb handle
            int knobX = l + 1 + Math.min(innerW - 2, Math.max(0, fillW - 1));
            g.fill(knobX, t + 1, Math.min(r - 1, knobX + 2), b - 1, 0xFFE0638E);

            Font font = Minecraft.getInstance().font;
            int pct = (int) Math.round(this.value * 100.0);
            int textCol = pct <= 0 ? COL_DIM : COL_TITLE;
            int tw = font.width(this.getMessage());
            g.drawString(font, this.getMessage(), l + (this.width - tw) / 2, t + (this.height - 8) / 2, textCol, false);
        }
    }

    /** Galgame floating choice & tab button. */
    private static class GalButton extends AbstractButton {
        private final OnPress onPress;
        private final boolean highlighted;
        private final boolean amberHighlight;
        @Nullable
        private final ItemStack icon;

        GalButton(int x, int y, int w, int h, Component msg, OnPress onPress,
                  boolean highlighted, boolean amberHighlight, @Nullable ItemStack icon) {
            super(x, y, w, h, msg);
            this.onPress = onPress;
            this.highlighted = highlighted;
            this.amberHighlight = amberHighlight;
            this.icon = icon;
        }

        @Override
        public void onPress() {
            this.onPress.onPress(this);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int l = this.getX(), t = this.getY(), r = l + this.width, b = t + this.height;
            int bgTop, bgBot, border, textCol;

            if (this.highlighted) {
                if (this.amberHighlight) {
                    bgTop = 0xCCFFF1C2;
                    bgBot = 0xCCFFE18C;
                    border = 0xCCE5A93B;
                    textCol = 0xFF7A4E10;
                } else {
                    bgTop = 0xCCFFE3EC;
                    bgBot = 0xCCFCC8D8;
                    border = 0xCCE87298;
                    textCol = 0xFF6B2A3E;
                }
            } else if (!this.active) {
                bgTop = 0x88F3ECEF;
                bgBot = 0x88EAE0E4;
                border = 0xAAD9C7CE;
                textCol = 0xFFA29298;
            } else if (this.isHoveredOrFocused()) {
                bgTop = 0xD0FFECF2;
                bgBot = 0xD0FDD6E2;
                border = 0xCCEB7D9F;
                textCol = 0xFF5A2A37;
            } else {
                bgTop = 0xA8FFFFFF;
                bgBot = 0xA8FFF7FA;
                border = 0xCCF0BACB;
                textCol = 0xFF4A332D;
            }

            g.fillGradient(l + 1, t + 1, r - 1, b - 1, bgTop, bgBot);
            drawRoundedOutline(g, l, t, r, b, border);

            if (this.active && this.isHoveredOrFocused() && !this.highlighted) {
                g.fill(l + 2, t + 3, l + 4, b - 3, 0xFFF3A833);
            }

            Font font = Minecraft.getInstance().font;
            if (this.icon != null && !this.icon.isEmpty()) {
                g.pose().pushPose();
                g.pose().translate(l + 4, t + (this.height - 12) / 2.0F, 0);
                g.pose().scale(0.75F, 0.75F, 1.0F);
                g.renderItem(this.icon, 0, 0);
                g.pose().popPose();
                int textX = l + 18 + Math.max(0, (this.width - 22 - font.width(this.getMessage())) / 2);
                g.drawString(font, this.getMessage(), textX, t + (this.height - 8) / 2, textCol, false);
            } else {
                int tw = font.width(this.getMessage());
                int textX = l + Math.max(3, (this.width - tw) / 2);
                g.drawString(font, this.getMessage(), textX, t + (this.height - 8) / 2, textCol, false);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            this.defaultButtonNarrationText(narration);
        }

        @FunctionalInterface
        interface OnPress {
            void onPress(GalButton btn);
        }
    }
}
