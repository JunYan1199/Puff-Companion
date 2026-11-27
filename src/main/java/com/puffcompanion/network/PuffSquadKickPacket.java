package com.puffcompanion.network;

import com.puffcompanion.compat.rats.SquadManager;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server: remove a rat from Puff's squad via the maid panel. */
public record PuffSquadKickPacket(int entityId, UUID rat) {

    public static void encode(PuffSquadKickPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(msg.rat);
    }

    public static PuffSquadKickPacket decode(FriendlyByteBuf buf) {
        return new PuffSquadKickPacket(buf.readVarInt(), buf.readUUID());
    }

    public static void handle(PuffSquadKickPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender == null) return;
        if (!(sender.level().getEntity(msg.entityId) instanceof PuffEntity puff) || !puff.isAlive() || !puff.isOwnedBy(sender)) return;
        double max = PuffEntity.INTERACT_RANGE;
        if (puff.getVehicle() != sender && puff.distanceToSqr(sender) > max * max) return;
        SquadManager.kick(sender, puff, msg.rat);
        PuffNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), PuffSquadSyncPacket.of(puff));
    }
}
