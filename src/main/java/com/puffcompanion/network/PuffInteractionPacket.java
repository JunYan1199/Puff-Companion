package com.puffcompanion.network;

import com.puffcompanion.entity.PuffEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: a button in {@code PuffInteractionScreen} was pressed. The server is authoritative:
 * it re-checks ownership, distance and (for gifts) the actual main-hand stack before changing anything.
 */
public record PuffInteractionPacket(int entityId, Action action, String arg) {

    public enum Action {
        TOPIC, GIFT, TOGGLE_FOLLOW, TOGGLE_REST, SHOULDER, PET, OUTFIT, CALL_MODE, HOARD, PROPOSE, STORY_REPLAY, TOGGLE_GUARD, TOGGLE_LOOTR, TOGGLE_SPELL, CYCLE_MODE, TOGGLE_HOME, AI_COMMAND
    }

    public static void encode(PuffInteractionPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeEnum(msg.action);
        buf.writeUtf(msg.arg, 32);
    }

    public static PuffInteractionPacket decode(FriendlyByteBuf buf) {
        return new PuffInteractionPacket(buf.readVarInt(), buf.readEnum(Action.class), buf.readUtf(32));
    }

    public static void handle(PuffInteractionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender == null) return;
        if (!(sender.level().getEntity(msg.entityId) instanceof PuffEntity puff)) return;
        if (!puff.isAlive() || !puff.isOwnedBy(sender)) return;
        boolean onShoulder = puff.getVehicle() == sender;
        double max = PuffEntity.INTERACT_RANGE;
        if (!onShoulder && puff.distanceToSqr(sender) > max * max) return;
        puff.handleInteraction(sender, msg.action, msg.arg);
    }
}
