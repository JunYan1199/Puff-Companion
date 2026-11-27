package com.puffcompanion.network;

import com.puffcompanion.item.LeaderCheeseStaffItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: the leader staff's mode picked in the mode screen (applied to the staff in that hand). */
public record PuffStaffModePacket(InteractionHand hand, int mode) {

    public static void encode(PuffStaffModePacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.hand);
        buf.writeVarInt(msg.mode);
    }

    public static PuffStaffModePacket decode(FriendlyByteBuf buf) {
        return new PuffStaffModePacket(buf.readEnum(InteractionHand.class), buf.readVarInt());
    }

    public static void handle(PuffStaffModePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().setPacketHandled(true);
        if (sender == null) return;
        ItemStack stack = sender.getItemInHand(msg.hand);
        if (!(stack.getItem() instanceof LeaderCheeseStaffItem)) return;
        LeaderCheeseStaffItem.setMode(stack, msg.mode);
        if (stack.getTag() != null) stack.getTag().remove("Pos1");
        sender.displayClientMessage(Component.translatable("message.puffcompanion.staff.mode",
                LeaderCheeseStaffItem.modeName(msg.mode)).withStyle(ChatFormatting.GOLD), true);
    }
}
