package com.puffcompanion.network;

import com.puffcompanion.compat.CreateCompat;
import com.puffcompanion.compat.rats.SquadJob;
import com.puffcompanion.compat.rats.SquadManager;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server: assign a job to one squad rat (owner, in range; builder only with Create). */
public record PuffSquadJobPacket(int entityId, UUID rat, int job) {

    public static void encode(PuffSquadJobPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(msg.rat);
        buf.writeVarInt(msg.job);
    }

    public static PuffSquadJobPacket decode(FriendlyByteBuf buf) {
        return new PuffSquadJobPacket(buf.readVarInt(), buf.readUUID(), buf.readVarInt());
    }

    public static void handle(PuffSquadJobPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender == null) return;
        if (!(sender.level().getEntity(msg.entityId) instanceof PuffEntity puff) || !puff.isAlive() || !puff.isOwnedBy(sender)) return;
        double max = PuffEntity.INTERACT_RANGE;
        if (puff.getVehicle() != sender && puff.distanceToSqr(sender) > max * max) return;
        SquadJob job = SquadJob.byId(msg.job);
        if (job == SquadJob.BUILDER && !CreateCompat.isLoaded()) return;
        SquadManager.setJob(puff, msg.rat, job);
        // confirm at once instead of waiting for the panel's periodic sync
        PuffNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sender), PuffSquadSyncPacket.of(puff));
    }
}
