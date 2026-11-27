package com.puffcompanion.network;

import com.puffcompanion.compat.CuriosCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffRevival;
import com.puffcompanion.item.CurioSlotExpanderItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: the owner picked a slot type in the expander screen. The server re-checks everything
 * (owner, distance, an expander actually in hand, a real player slot type) before consuming one expander and
 * adding one permanent slot of that type to Puff.
 */
public record PuffCurioExpandPacket(int entityId, String slotType) {

    public static void encode(PuffCurioExpandPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeUtf(msg.slotType, 64);
    }

    public static PuffCurioExpandPacket decode(FriendlyByteBuf buf) {
        return new PuffCurioExpandPacket(buf.readVarInt(), buf.readUtf(64));
    }

    public static void handle(PuffCurioExpandPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender == null || !CuriosCompat.isLoaded()) return;
        if (!(sender.level().getEntity(msg.entityId) instanceof PuffEntity puff)) return;
        if (!puff.isAlive() || !puff.isOwnedBy(sender)) return;
        double max = PuffEntity.INTERACT_RANGE;
        if (puff.getVehicle() != sender && puff.distanceToSqr(sender) > max * max) return;
        if (!CuriosCompat.isPlayerSlotType(puff, msg.slotType)) return;
        InteractionHand hand = sender.getMainHandItem().getItem() instanceof CurioSlotExpanderItem ? InteractionHand.MAIN_HAND
                : sender.getOffhandItem().getItem() instanceof CurioSlotExpanderItem ? InteractionHand.OFF_HAND : null;
        if (hand == null) {
            sender.displayClientMessage(Component.translatable("message.puffcompanion.curio_expand.need_item"), true);
            return;
        }
        ItemStack stack = sender.getItemInHand(hand);
        if (puff.getCurioSlotBonus(msg.slotType) >= 64) {
            sender.displayClientMessage(Component.translatable("message.puffcompanion.curio_expand.max"), true);
            return;
        }
        if (!sender.getAbilities().instabuild) stack.shrink(1);
        int now = CuriosCompat.addSlotBonus(puff, msg.slotType, 1);
        sender.displayClientMessage(Component.translatable("message.puffcompanion.curio_expand.done",
                Component.translatable("curios.identifier." + msg.slotType), now), false);
        if (puff.level() instanceof ServerLevel sl) {
            sl.sendParticles(PuffRevival.GOLD_DUST, puff.getX(), puff.getY() + 1.0, puff.getZ(), 20, 0.4, 0.5, 0.4, 0.02);
            sl.sendParticles(PuffRevival.SAKURA_DUST, puff.getX(), puff.getY() + 1.0, puff.getZ(), 14, 0.4, 0.5, 0.4, 0.02);
            sl.playSound(null, puff.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0F, 1.3F);
        }
    }
}
