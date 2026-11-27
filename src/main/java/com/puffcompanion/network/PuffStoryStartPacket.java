package com.puffcompanion.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> owner client: open the dialogue screen for a story. Carries the script JSON from the
 * server's data pack so the client renders exactly what the server validates against.
 */
public record PuffStoryStartPacket(int entityId, String storyKey, String json) {
    private static final int MAX_JSON = 1 << 18;

    public static void encode(PuffStoryStartPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUtf(msg.storyKey, 128);
        buf.writeUtf(msg.json, MAX_JSON);
    }

    public static PuffStoryStartPacket decode(FriendlyByteBuf buf) {
        return new PuffStoryStartPacket(buf.readVarInt(), buf.readUtf(128), buf.readUtf(MAX_JSON));
    }

    public static void handle(PuffStoryStartPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.puffcompanion.client.ClientPacketHandler.handleStoryStart(msg));
        ctx.get().setPacketHandled(true);
    }
}
