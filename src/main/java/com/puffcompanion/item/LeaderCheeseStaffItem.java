package com.puffcompanion.item;

import com.puffcompanion.compat.rats.SquadStorage;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffSquad;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Leader cheese staff ("big sister's scepter"): right-click Puff to bind it to her, right-click the air to pick
 * one of three modes. Area mode: right-click two corner blocks to set the squad's work area (max 32 x 16 x 32),
 * sneak + right-click a container to make it the squad's supply / storage. Join / leave modes: right-click your
 * own tamed rat to add it to / remove it from Puff's squad (handled in {@code SquadManager}).
 */
public class LeaderCheeseStaffItem extends Item {
    public static final int MODE_AREA = 0, MODE_JOIN = 1, MODE_LEAVE = 2;
    private static final String[] MODE_KEYS = {"area", "join", "leave"};

    public LeaderCheeseStaffItem(Properties properties) {
        super(properties);
    }

    public static int mode(ItemStack stack) {
        return stack.hasTag() ? Math.floorMod(stack.getTag().getInt("Mode"), 3) : MODE_AREA;
    }

    public static void setMode(ItemStack stack, int mode) {
        stack.getOrCreateTag().putInt("Mode", Math.floorMod(mode, 3));
    }

    public static Component modeName(int mode) {
        return Component.translatable("item.puffcompanion.leader_cheese_staff.mode." + MODE_KEYS[Math.floorMod(mode, 3)]);
    }

    // ---------------------------------------------------------------- binding

    public static void bind(ServerPlayer player, ItemStack stack, PuffEntity puff) {
        if (!puff.isOwnedBy(player)) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.squad.not_yours").withStyle(ChatFormatting.RED), true);
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID("Puff", puff.getUUID());
        tag.putString("PuffName", puff.getDisplayName().getString());
        writeArea(stack, puff.getSquad());
        player.displayClientMessage(Component.translatable("message.puffcompanion.staff.bound", puff.getDisplayName()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        player.level().playSound(null, puff.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.2F);
    }

    /** The bound Puff if she is loaded in the player's level and owned by them (otherwise tells the player why). */
    @Nullable
    public static PuffEntity boundPuff(ServerPlayer player, ItemStack stack) {
        if (!stack.hasTag() || !stack.getTag().hasUUID("Puff")) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.staff.unbound").withStyle(ChatFormatting.RED), true);
            return null;
        }
        UUID id = stack.getTag().getUUID("Puff");
        Entity e = ((ServerLevel) player.level()).getEntity(id);
        if (!(e instanceof PuffEntity puff) || !puff.isAlive() || !puff.isOwnedBy(player)) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.staff.puff_away").withStyle(ChatFormatting.RED), true);
            return null;
        }
        return puff;
    }

    /** Copies the squad's area / storage into the staff (tooltip). */
    public static void writeArea(ItemStack stack, PuffSquad squad) {
        CompoundTag tag = stack.getOrCreateTag();
        if (squad.hasArea()) {
            tag.putLong("AreaMin", squad.areaMin().asLong());
            tag.putLong("AreaMax", squad.areaMax().asLong());
        } else {
            tag.remove("AreaMin");
            tag.remove("AreaMax");
        }
        if (squad.storage() != null) tag.putLong("Storage", squad.storage().asLong());
        else tag.remove("Storage");
    }

    // ---------------------------------------------------------------- use

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.puffcompanion.client.ClientHooks.openStaffModeScreen(hand));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    /** Area mode works on any block, containers included (before the block's own right-click). */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext ctx) {
        if (mode(stack) != MODE_AREA || ctx.getPlayer() == null) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        if (level.isClientSide || !(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        PuffEntity puff = boundPuff(player, stack);
        if (puff == null) return InteractionResult.SUCCESS;
        BlockPos pos = ctx.getClickedPos().immutable();
        PuffSquad squad = puff.getSquad();
        CompoundTag tag = stack.getOrCreateTag();
        if (player.isShiftKeyDown()) {
            if (!SquadStorage.isContainer(level, pos)) {
                player.displayClientMessage(Component.translatable("message.puffcompanion.staff.not_container").withStyle(ChatFormatting.RED), true);
                return InteractionResult.SUCCESS;
            }
            squad.setStorage(pos);
            writeArea(stack, squad);
            player.displayClientMessage(Component.translatable("message.puffcompanion.staff.storage", pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.AQUA), true);
            level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.PLAYERS, 0.6F, 1.6F);
            return InteractionResult.SUCCESS;
        }
        if (!tag.contains("Pos1")) {
            tag.putLong("Pos1", pos.asLong());
            player.displayClientMessage(Component.translatable("message.puffcompanion.staff.pos1", pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.AQUA), true);
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.get(), SoundSource.PLAYERS, 0.6F, 1.0F);
            return InteractionResult.SUCCESS;
        }
        BlockPos a = BlockPos.of(tag.getLong("Pos1"));
        if (!PuffSquad.fits(a, pos)) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.staff.too_big",
                    Math.abs(a.getX() - pos.getX()) + 1, Math.abs(a.getY() - pos.getY()) + 1, Math.abs(a.getZ() - pos.getZ()) + 1,
                    PuffSquad.MAX_X, PuffSquad.MAX_Y, PuffSquad.MAX_Z).withStyle(ChatFormatting.RED), true);
            tag.remove("Pos1");
            return InteractionResult.SUCCESS;
        }
        squad.setArea(a, pos);
        tag.remove("Pos1");
        writeArea(stack, squad);
        BlockPos lo = squad.areaMin(), hi = squad.areaMax();
        player.displayClientMessage(Component.translatable("message.puffcompanion.staff.area",
                hi.getX() - lo.getX() + 1, hi.getY() - lo.getY() + 1, hi.getZ() - lo.getZ() + 1).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        level.playSound(null, pos, SoundEvents.NOTE_BLOCK_CHIME.get(), SoundSource.PLAYERS, 0.6F, 1.5F);
        return InteractionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- tooltip

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.mode", modeName(mode(stack))).withStyle(ChatFormatting.GOLD));
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.hasUUID("Puff")) {
            tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.bound", tag.getString("PuffName")).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.unbound").withStyle(ChatFormatting.GRAY));
        }
        if (tag != null && tag.contains("AreaMin")) {
            BlockPos lo = BlockPos.of(tag.getLong("AreaMin")), hi = BlockPos.of(tag.getLong("AreaMax"));
            tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.area", lo.toShortString(), hi.toShortString()).withStyle(ChatFormatting.AQUA));
        }
        if (tag != null && tag.contains("Pos1")) {
            tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.pos1", BlockPos.of(tag.getLong("Pos1")).toShortString()).withStyle(ChatFormatting.YELLOW));
        }
        if (tag != null && tag.contains("Storage")) {
            tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.storage", BlockPos.of(tag.getLong("Storage")).toShortString()).withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("item.puffcompanion.leader_cheese_staff.hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.hasTag() && stack.getTag().hasUUID("Puff");
    }
}
