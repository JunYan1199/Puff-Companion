package com.puffcompanion.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: show a speech bubble above a Puff.
 *
 * @param entityId      Puff's network entity id
 * @param langKey       translation key of the line (resolved + {player}/{call} substituted on the client)
 * @param durationTicks how long the bubble stays up
 * @param toChat        also print the line to chat (only on the owner's client)
 * @param animation     triggerable GeckoLib animation on the "action" controller, or "" for none
 * @param playerName    owner's name, used for {player} (and {call} in stage 1)
 */
public record PuffBubblePacket(int entityId, String langKey, int durationTicks, boolean toChat,
                               String animation, String playerName, String expression, String ear) {

    public PuffBubblePacket(int entityId, String langKey, int durationTicks, boolean toChat,
                            String animation, String playerName) {
        this(entityId, langKey, durationTicks, toChat, animation, playerName, "", "");
    }

    public static void encode(PuffBubblePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUtf(msg.langKey, 256);
        buf.writeVarInt(msg.durationTicks);
        buf.writeBoolean(msg.toChat);
        buf.writeUtf(msg.animation, 64);
        buf.writeUtf(msg.playerName, 64);
        buf.writeUtf(msg.expression == null ? "" : msg.expression, 32);
        buf.writeUtf(msg.ear == null ? "" : msg.ear, 32);
    }

    public static PuffBubblePacket decode(FriendlyByteBuf buf) {
        return new PuffBubblePacket(buf.readVarInt(), buf.readUtf(256), buf.readVarInt(),
                buf.readBoolean(), buf.readUtf(64), buf.readUtf(64),
                buf.readUtf(32), buf.readUtf(32));
    }

    public static void handle(PuffBubblePacket msg, Supplier<NetworkEvent.Context> ctx) {
        // consumerMainThread already runs this on the client main thread
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.puffcompanion.client.ClientPacketHandler.handleBubble(msg));
        ctx.get().setPacketHandled(true);
    }
}
