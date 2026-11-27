package com.puffcompanion.network;

import com.puffcompanion.story.PuffStoryManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: the player picked {@code choice} on {@code nodeId}, or reached the end of the
 * story ({@code choice == -1}). The server checks it against the active session before settling.
 */
public record PuffStoryChoicePacket(int entityId, String storyKey, String nodeId, int choice) {

    public static void encode(PuffStoryChoicePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUtf(msg.storyKey, 128);
        buf.writeUtf(msg.nodeId, 128);
        buf.writeVarInt(msg.choice + 1);   // -1 encoded as 0
    }

    public static PuffStoryChoicePacket decode(FriendlyByteBuf buf) {
        return new PuffStoryChoicePacket(buf.readVarInt(), buf.readUtf(128), buf.readUtf(128), buf.readVarInt() - 1);
    }

    public static void handle(PuffStoryChoicePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender != null) PuffStoryManager.handleChoice(sender, msg);
    }
}
