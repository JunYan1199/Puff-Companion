package com.puffcompanion.client;

import com.puffcompanion.entity.PuffCalls;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffExpression;
import com.puffcompanion.network.PuffBubblePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** Client-only packet handling (kept out of the packet class so dedicated servers never load it). */
public final class ClientPacketHandler {
    private ClientPacketHandler() {}

    public static void handleStoryStart(com.puffcompanion.network.PuffStoryStartPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!(mc.level.getEntity(msg.entityId()) instanceof PuffEntity puff)) return;
        com.puffcompanion.story.PuffStory story;
        try {
            story = com.puffcompanion.story.PuffStory.parse(
                    com.google.gson.JsonParser.parseString(msg.json()).getAsJsonObject());
        } catch (Exception e) {
            com.puffcompanion.PuffCompanion.LOGGER.error("[Puff Companion] Could not parse story {}", msg.storyKey(), e);
            return;
        }
        mc.setScreen(new com.puffcompanion.client.gui.PuffDialogueScreen(puff, msg.storyKey(), story));
    }

    public static void handleCurioExpandOpen(com.puffcompanion.network.PuffCurioExpandOpenPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(msg.entityId()) instanceof PuffEntity)) return;
        mc.setScreen(new com.puffcompanion.client.gui.PuffCurioExpandScreen(msg.entityId(), msg.slots()));
    }

    public static void handleBubble(PuffBubblePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.screen instanceof com.puffcompanion.client.gui.PuffDialogueScreen) return;
        Entity entity = mc.level.getEntity(msg.entityId());
        if (!(entity instanceof PuffEntity puff)) return;

        String callKey = PuffCalls.callKey(puff.getGrowthStage(), puff.getCallMode());
        String call = callKey == null ? msg.playerName() : I18n.get(callKey);
        String text = PuffCalls.format(I18n.get(msg.langKey()), msg.playerName(), call);

        puff.showBubble(text, msg.durationTicks());
        PuffVoicePlayer.playBubble(puff, msg.langKey());
        if (msg.expression() != null && !msg.expression().isEmpty()) {
            String expr = PuffExpression.normalizeExpression(msg.expression());
            String ear = (msg.ear() != null && !msg.ear().isEmpty())
                    ? PuffExpression.normalizeEar(msg.ear())
                    : PuffExpression.defaultEarFor(expr);
            puff.setTempExpression(expr, ear, msg.durationTicks());
        } else if (msg.ear() != null && !msg.ear().isEmpty()) {
            puff.setTempExpression(PuffExpression.NEUTRAL, PuffExpression.normalizeEar(msg.ear()), msg.durationTicks());
        }

        if (!msg.animation().isEmpty()) {
            puff.triggerAnim(PuffEntity.ACTION_CONTROLLER, msg.animation());
        }
        if (msg.toChat() && mc.player != null && puff.isOwnedBy(mc.player)) {
            mc.player.displayClientMessage(
                    Component.translatable("puff.chat.format", puff.getDisplayName(), Component.literal(text)), false);
        }
    }
}
