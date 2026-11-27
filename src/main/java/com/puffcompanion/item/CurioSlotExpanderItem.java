package com.puffcompanion.item;

import com.puffcompanion.compat.CuriosCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.network.PuffCurioExpandOpenPacket;
import com.puffcompanion.network.PuffNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Star accessory expander: right-click your own Puff (Curios installed) to pick one Curios slot type in
 * {@code PuffCurioExpandScreen}; the server then consumes one expander and gives her one more slot of that type
 * for good (saved on Puff, kept through death and revival).
 */
public class CurioSlotExpanderItem extends Item {
    public CurioSlotExpanderItem(Properties properties) {
        super(properties);
    }

    /** Server: owner right-clicked Puff with the expander. */
    public static void openFor(ServerPlayer player, PuffEntity puff) {
        if (!CuriosCompat.isLoaded()) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.curio_expand.no_curios"), true);
            return;
        }
        List<CuriosCompat.SlotGroup> options = new ArrayList<>();
        for (CuriosCompat.SlotGroup g : CuriosCompat.slotSizes(puff)) {
            if (CuriosCompat.isPlayerSlotType(puff, g.id())) options.add(g);
        }
        if (options.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.curio_expand.no_slots"), true);
            return;
        }
        PuffNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PuffCurioExpandOpenPacket(puff.getId(), options));
    }

    /** True if {@code player} holds an expander in either hand. */
    public static boolean isHeld(net.minecraft.world.entity.player.Player player) {
        return player.getMainHandItem().getItem() instanceof CurioSlotExpanderItem
                || player.getOffhandItem().getItem() instanceof CurioSlotExpanderItem;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.puffcompanion.curio_slot_expander.tooltip").withStyle(ChatFormatting.LIGHT_PURPLE));
        if (!CuriosCompat.isLoaded()) {
            tooltip.add(Component.translatable("item.puffcompanion.curio_slot_expander.no_curios").withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
