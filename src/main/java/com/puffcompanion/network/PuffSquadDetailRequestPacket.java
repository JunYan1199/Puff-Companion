package com.puffcompanion.network;

import com.puffcompanion.compat.rats.SquadManager;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server: on-demand request for one squad rat's live details and position. */
public record PuffSquadDetailRequestPacket(int entityId, UUID rat) {

    public static void encode(PuffSquadDetailRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(msg.rat);
    }

    public static PuffSquadDetailRequestPacket decode(FriendlyByteBuf buf) {
        return new PuffSquadDetailRequestPacket(buf.readVarInt(), buf.readUUID());
    }

    public static void handle(PuffSquadDetailRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender == null) return;
        if (!(sender.level().getEntity(msg.entityId) instanceof PuffEntity puff) || !puff.isAlive() || !puff.isOwnedBy(sender)) return;
        double max = PuffEntity.INTERACT_RANGE;
        if (puff.getVehicle() != sender && puff.distanceToSqr(sender) > max * max) return;
        SquadManager.RatDetail detail = SquadManager.detail(puff, msg.rat);
        if (detail != null) {
            PuffNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), new PuffSquadDetailPacket(msg.entityId, detail));
        }
    }
}
