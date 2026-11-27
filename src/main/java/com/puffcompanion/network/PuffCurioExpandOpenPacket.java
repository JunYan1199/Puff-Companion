package com.puffcompanion.network;

import com.puffcompanion.compat.CuriosCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> owner client: open the slot picker of the star accessory expander (slot types + current counts). */
public record PuffCurioExpandOpenPacket(int entityId, List<CuriosCompat.SlotGroup> slots) {
    private static final int MAX = 128;

    public static void encode(PuffCurioExpandOpenPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        int n = Math.min(MAX, msg.slots.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(msg.slots.get(i).id(), 64);
            buf.writeVarInt(msg.slots.get(i).count());
        }
    }

    public static PuffCurioExpandOpenPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int n = Math.min(MAX, buf.readVarInt());
        List<CuriosCompat.SlotGroup> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) slots.add(new CuriosCompat.SlotGroup(buf.readUtf(64), buf.readVarInt()));
        return new PuffCurioExpandOpenPacket(id, slots);
    }

    public static void handle(PuffCurioExpandOpenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.puffcompanion.client.ClientPacketHandler.handleCurioExpandOpen(msg));
        ctx.get().setPacketHandled(true);
    }
}
