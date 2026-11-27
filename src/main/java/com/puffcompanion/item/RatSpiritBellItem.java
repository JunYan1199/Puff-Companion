package com.puffcompanion.item;

import com.puffcompanion.compat.RatsCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffEventTriggers;
import com.puffcompanion.entity.PuffOutfit;
import com.puffcompanion.entity.PuffRevival;
import com.puffcompanion.world.PuffGraveyard;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Spirit bell: right-click your Puff to store her (full snapshot inside the bell), right-click a block to
 * release her, right-click the air to recall a bound Puff from anywhere (same or other dimension), and
 * right-click any Rats cheese block while she is dead to revive her for one cheese.
 */
public class RatSpiritBellItem extends Item {
    private static final String TAG_BOUND = "BoundPuff";
    private static final String TAG_STORED = "StoredPuff";
    private static final String TAG_NAME = "PuffName";
    private static final String TAG_STAGE = "Stage";
    private static final String TAG_OUTFIT = "Outfit";
    private static final String TAG_HEALTH = "Health";
    private static final String TAG_MAX_HEALTH = "MaxHealth";
    private static final String TAG_AFFECTION = "Affection";
    private static final String TAG_DEAD = "Dead";
    private static final int COOLDOWN = 40;

    public RatSpiritBellItem(Properties props) {
        super(props.stacksTo(1).rarity(Rarity.RARE));
    }

    // ---------------- Store ----------------

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof PuffEntity puff) || !puff.isOwnedBy(player)) return InteractionResult.PASS;
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        ItemStack held = player.getItemInHand(hand);   // the real stack (not the copy passed in creative)
        CompoundTag tag = held.getOrCreateTag();
        if (tag.contains(TAG_STORED)) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.bell.full"), true);
            return InteractionResult.CONSUME;
        }
        if (puff.isPassenger()) puff.stopRiding();
        bind(held, puff);
        tag.put(TAG_STORED, PuffRevival.snapshot(puff));
        ((ServerLevel) player.level()).sendParticles(PuffRevival.SAKURA_DUST, puff.getX(), puff.getY() + 1, puff.getZ(),
                25, 0.4, 0.6, 0.4, 0.02);
        puff.discard();
        player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
        player.displayClientMessage(Component.translatable("message.puffcompanion.bell.stored"), true);
        return InteractionResult.CONSUME;
    }

    // ---------------- Release / revive at a block ----------------

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        Level level = ctx.getLevel();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = ctx.getItemInHand();
        CompoundTag tag = stack.getTag();
        BlockPos clicked = ctx.getClickedPos();

        // Cheese-block soul calling: bound Puff is dead
        UUID bound = boundId(stack);
        if (bound != null && RatsCompat.isCheeseBlock(level.getBlockState(clicked).getBlock())) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            ServerLevel sl = (ServerLevel) level;
            if (!PuffGraveyard.get(sl.getServer()).isDead(bound)) {
                player.displayClientMessage(Component.translatable("message.puffcompanion.bell.not_dead"), true);
                return InteractionResult.CONSUME;
            }
            if (!player.getAbilities().instabuild && !consumeCheese(player)) {
                player.displayClientMessage(Component.translatable("message.puffcompanion.bell.need_cheese"), true);
                return InteractionResult.CONSUME;
            }
            PuffEntity puff = PuffRevival.reviveFromGraveyard(sl, bound, Vec3.atBottomCenterOf(clicked.above()));
            if (puff != null) {
                refreshInfo(stack, puff);
                tag.putBoolean(TAG_DEAD, false);
            }
            return InteractionResult.CONSUME;
        }

        // Release a stored Puff
        if (tag != null && tag.contains(TAG_STORED)) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            BlockPos spawn = clicked.relative(ctx.getClickedFace());
            PuffEntity puff = PuffRevival.spawnFromSnapshot((ServerLevel) level, Vec3.atBottomCenterOf(spawn),
                    tag.getCompound(TAG_STORED));
            if (puff != null) {
                // spawnFromSnapshot heals to full; keep the stored health instead (this is not a revival)
                if (tag.contains(TAG_HEALTH)) puff.setHealth(Math.max(1.0F, tag.getFloat(TAG_HEALTH)));
                tag.remove(TAG_STORED);
                bind(stack, puff);
                ((ServerLevel) level).sendParticles(PuffRevival.SAKURA_DUST, puff.getX(), puff.getY() + 1, puff.getZ(),
                        25, 0.4, 0.6, 0.4, 0.02);
                puff.say("puff.bell.release", 2, 100, false, PuffEntity.ANIM_SHY);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    // ---------------- Recall ----------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        UUID bound = boundId(stack);
        if (bound == null || stack.getOrCreateTag().contains(TAG_STORED)) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        ServerPlayer sp = (ServerPlayer) player;
        player.getCooldowns().addCooldown(this, COOLDOWN);
        if (PuffGraveyard.get(sp.server).isDead(bound)) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.bell.is_dead"), true);
            return InteractionResultHolder.consume(stack);
        }
        PuffEntity puff = find(sp, bound);
        if (puff == null) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.bell.not_found"), true);
            return InteractionResultHolder.consume(stack);
        }
        if (puff.isPassenger()) puff.stopRiding();
        if (puff.level() == player.level()) {
            puff.teleportTo(player.getX(), player.getY(), player.getZ());
            puff.getNavigation().stop();
        } else {
            puff.followOwnerToDimension(sp, sp.serverLevel());
        }
        level.playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.6F, 1.6F);
        sp.serverLevel().sendParticles(PuffRevival.GOLD_DUST, player.getX(), player.getY() + 1, player.getZ(), 20, 0.5, 0.6, 0.5, 0.02);
        player.displayClientMessage(Component.translatable("message.puffcompanion.bell.recalled"), true);
        return InteractionResultHolder.consume(stack);
    }

    @Nullable
    private static PuffEntity find(ServerPlayer player, UUID id) {
        for (ServerLevel level : player.server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof PuffEntity p && p.isAlive()) return p;
        }
        return null;
    }

    private static boolean consumeCheese(Player player) {
        for (ItemStack s : player.getInventory().items) {
            if (PuffEventTriggers.isCheese(s) && !(s.getItem() instanceof CheeseCoreItem)) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    // ---------------- NBT helpers ----------------

    @Nullable
    public static UUID boundId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(TAG_BOUND) ? tag.getUUID(TAG_BOUND) : null;
    }

    private static void bind(ItemStack stack, PuffEntity puff) {
        stack.getOrCreateTag().putUUID(TAG_BOUND, puff.getUUID());
        refreshInfo(stack, puff);
    }

    private static void refreshInfo(ItemStack stack, PuffEntity puff) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_NAME, Component.Serializer.toJson(puff.getDisplayName()));
        tag.putInt(TAG_STAGE, puff.getGrowthStage());
        tag.putString(TAG_OUTFIT, puff.getCurrentOutfit());
        tag.putFloat(TAG_HEALTH, puff.getHealth());
        tag.putFloat(TAG_MAX_HEALTH, puff.getMaxHealth());
        tag.putInt(TAG_AFFECTION, puff.getAffection());
    }

    /** Called when a bound Puff dies / is revived so the owner's bells show the right status. */
    public static void markDead(Player owner, UUID puff, boolean dead) {
        for (ItemStack s : owner.getInventory().items) {
            if (s.getItem() instanceof RatSpiritBellItem && puff.equals(boundId(s))) {
                s.getOrCreateTag().putBoolean(TAG_DEAD, dead);
            }
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.getTag() != null && stack.getTag().contains(TAG_STORED);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_BOUND)) {
            lines.add(Component.translatable("tooltip.puffcompanion.bell.unbound").withStyle(ChatFormatting.GRAY));
            return;
        }
        Component name = Component.Serializer.fromJson(tag.getString(TAG_NAME));
        String status = tag.contains(TAG_STORED) ? "stored" : tag.getBoolean(TAG_DEAD) ? "dead" : "out";
        lines.add(Component.translatable("tooltip.puffcompanion.bell.name", name != null ? name : Component.literal("?"),
                Component.translatable("tooltip.puffcompanion.bell.status." + status)).withStyle(ChatFormatting.LIGHT_PURPLE));
        PuffOutfit outfit = PuffOutfit.byId(tag.getString(TAG_OUTFIT));
        lines.add(Component.translatable("tooltip.puffcompanion.puff_summary", tag.getInt(TAG_STAGE),
                outfit != null ? Component.translatable(outfit.nameKey()) : Component.literal("?"),
                tag.getInt(TAG_AFFECTION)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.puffcompanion.bell.health",
                String.format(java.util.Locale.ROOT, "%.0f", tag.getFloat(TAG_HEALTH)),
                String.format(java.util.Locale.ROOT, "%.0f", tag.getFloat(TAG_MAX_HEALTH))).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.puffcompanion.bell.hint").withStyle(ChatFormatting.GOLD));
    }
}
