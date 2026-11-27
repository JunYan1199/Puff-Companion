package com.puffcompanion.compat.rats;

import com.github.alexthe666.rats.server.entity.rat.RatCommand;
import com.github.alexthe666.rats.server.entity.rat.TamedRat;
import com.github.alexthe666.rats.server.misc.RatUpgradeUtils;
import com.github.alexthe666.rats.registry.RatsItemRegistry;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffSquad;
import com.puffcompanion.item.LeaderCheeseStaffItem;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Puff's rat squad on the Rats side. Rats: Reboot API used (javap-verified, Rats 8.1.5):
 * {@code TamedRat} ({@code InventoryRat#setCommand(RatCommand)}, {@code isInCage()}),
 * {@code RatCommand.WANDER}, {@code RatUpgradeUtils.forEachUpgrade / hasUpgrade}. A squad rat stores which Puff
 * it belongs to in its persistent data; the job itself lives in Puff's {@link PuffSquad}.
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID)
public final class SquadManager {
    public static final String DATA = "PuffSquad";
    private static final Set<TamedRat> GOAL_ADDED = Collections.newSetFromMap(new WeakHashMap<>());

    private SquadManager() {}

    /** The Puff a squad rat belongs to, if she is loaded in the rat's level and still lists it. */
    @Nullable
    public static PuffEntity puffOf(TamedRat rat) {
        CompoundTag data = rat.getPersistentData().getCompound(DATA);
        if (!data.hasUUID("Puff") || !(rat.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(data.getUUID("Puff"));
        return e instanceof PuffEntity puff && puff.isAlive() && puff.getSquad().isMember(rat.getUUID()) ? puff : null;
    }

    private static void ensureGoal(TamedRat rat) {
        if (GOAL_ADDED.add(rat)) rat.goalSelector.addGoal(0, new PuffRatJobGoal(rat));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof TamedRat rat
                && rat.getPersistentData().contains(DATA)) {
            ensureGoal(rat);
        }
    }

    // ---------------------------------------------------------------- staff on entities

    /** Leader staff used on Puff (bind) or on a rat (join / leave): handled before the entity's own click. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        ItemStack stack = event.getEntity().getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof LeaderCheeseStaffItem)) return;
        if (!(event.getTarget() instanceof PuffEntity) && !(event.getTarget() instanceof TamedRat)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getTarget() instanceof PuffEntity puff) {
            LeaderCheeseStaffItem.bind(player, stack, puff);
        } else if (event.getTarget() instanceof TamedRat rat) {
            PuffEntity puff = LeaderCheeseStaffItem.boundPuff(player, stack);
            if (puff == null) return;
            if (LeaderCheeseStaffItem.mode(stack) == LeaderCheeseStaffItem.MODE_JOIN) join(player, puff, rat);
            else if (LeaderCheeseStaffItem.mode(stack) == LeaderCheeseStaffItem.MODE_LEAVE) leave(player, puff, rat);
            else player.displayClientMessage(Component.translatable("message.puffcompanion.staff.wrong_mode").withStyle(ChatFormatting.GRAY), true);
        }
    }

    public static void join(ServerPlayer player, PuffEntity puff, TamedRat rat) {
        if (!rat.isTame() || !player.getUUID().equals(rat.getOwnerUUID()) || !puff.isOwnedBy(player)) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.squad.not_yours").withStyle(ChatFormatting.RED), true);
            return;
        }
        PuffSquad squad = puff.getSquad();
        if (squad.isMember(rat.getUUID())) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.squad.already", rat.getName()), true);
            return;
        }
        // leaving another Puff's squad first
        PuffEntity other = puffOf(rat);
        if (other != null && other != puff) other.getSquad().remove(rat.getUUID());
        if (!squad.add(rat.getUUID(), rat.getName().getString())) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.squad.full", PuffSquad.MAX_MEMBERS).withStyle(ChatFormatting.RED), true);
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putUUID("Puff", puff.getUUID());
        rat.getPersistentData().put(DATA, data);
        ensureGoal(rat);
        player.displayClientMessage(Component.translatable("message.puffcompanion.squad.joined", rat.getName(), squad.size()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        puff.say("puff.squad.join", 2, 80, false, "");
    }

    public static void leave(ServerPlayer player, PuffEntity puff, TamedRat rat) {
        if (!puff.getSquad().remove(rat.getUUID())) {
            player.displayClientMessage(Component.translatable("message.puffcompanion.squad.not_member", rat.getName()), true);
            return;
        }
        rat.getPersistentData().remove(DATA);
        rat.getNavigation().stop();
        PuffRatJobGoal.STATUS.remove(rat.getUUID());
        player.displayClientMessage(Component.translatable("message.puffcompanion.squad.left", rat.getName()).withStyle(ChatFormatting.GRAY), true);
    }

    /** Panel: kick a rat from Puff's squad by UUID (works even if the rat is currently unloaded). */
    public static void kick(ServerPlayer player, PuffEntity puff, UUID ratId) {
        PuffSquad.Member m = puff.getSquad().member(ratId);
        if (m == null) return;
        String ratName = m.name;
        puff.getSquad().remove(ratId);
        PuffRatJobGoal.STATUS.remove(ratId);
        TamedRat rat = findLoadedRat(puff, ratId);
        if (rat != null) {
            ratName = rat.getName().getString();
            rat.getPersistentData().remove(DATA);
            rat.getNavigation().stop();
        }
        player.displayClientMessage(Component.translatable("message.puffcompanion.squad.left", ratName).withStyle(ChatFormatting.GRAY), true);
    }

    @Nullable
    private static TamedRat findLoadedRat(PuffEntity puff, UUID ratId) {
        if (!(puff.level() instanceof ServerLevel sl)) return null;
        if (sl.getEntity(ratId) instanceof TamedRat rat) return rat;
        var server = sl.getServer();
        for (ServerLevel other : server.getAllLevels()) {
            if (other == sl) continue;
            if (other.getEntity(ratId) instanceof TamedRat rat) return rat;
        }
        return null;
    }

    /** Panel: change one rat's job. Rats with a job are switched to "wander" so their own commands do not pull them away. */
    public static void setJob(PuffEntity puff, UUID ratId, SquadJob job) {
        PuffSquad.Member m = puff.getSquad().member(ratId);
        if (m == null) return;
        m.job = job.ordinal();
        PuffRatJobGoal.STATUS.remove(ratId);
        if (puff.level() instanceof ServerLevel sl && sl.getEntity(ratId) instanceof TamedRat rat) {
            m.name = rat.getName().getString();
            if (job != SquadJob.NONE) {
                rat.setOrderedToSit(false);
                rat.setCommand(RatCommand.WANDER);
                ensureGoal(rat);
            }
        }
    }

    // ---------------------------------------------------------------- panel data

    /** One squad row for the panel. */
    public record Row(UUID id, String name, int job, boolean online, List<ItemStack> upgrades, String statusKey, String statusArg) {}

    /** Lazy-loaded detail snapshot for a single squad rat (queried only when the player opens its detail view). */
    public record RatDetail(
            UUID id, String name, int job, boolean online,
            float hp, float maxHp,
            int x, int y, int z, String dimension, int distance,
            String command, boolean sitting, boolean inCage,
            ItemStack heldItem, List<ItemStack> upgrades,
            String statusKey, String statusArg
    ) {}

    @Nullable
    public static RatDetail detail(PuffEntity puff, UUID ratId) {
        PuffSquad.Member m = puff.getSquad().member(ratId);
        if (m == null) return null;
        String[] status = PuffRatJobGoal.STATUS.getOrDefault(ratId, new String[]{"", ""});
        TamedRat rat = findLoadedRat(puff, ratId);
        if (rat == null) {
            return new RatDetail(m.id, m.name, m.job, false,
                    0F, 0F, 0, 0, 0, "", -1,
                    "", false, false,
                    ItemStack.EMPTY, List.of(), status[0], status[1]);
        }
        m.name = rat.getName().getString();
        List<ItemStack> upgrades = new ArrayList<>();
        try {
            RatUpgradeUtils.forEachUpgrade(rat, item -> item != RatsItemRegistry.RAT_UPGRADE_BASIC.get(),
                    (stack, slot) -> upgrades.add(stack.copyWithCount(1)));
        } catch (Throwable ignored) {
        }
        int dist = rat.level() == puff.level() ? (int) Math.round(rat.distanceTo(puff)) : -1;
        String cmd = "";
        try {
            RatCommand rc = rat.getCommand();
            if (rc != null) cmd = rc.name();
        } catch (Throwable ignored) {
        }
        boolean caged = false;
        try {
            caged = rat.isInCage();
        } catch (Throwable ignored) {
        }
        return new RatDetail(
                m.id, m.name, m.job, true,
                rat.getHealth(), rat.getMaxHealth(),
                rat.getBlockX(), rat.getBlockY(), rat.getBlockZ(),
                rat.level().dimension().location().toString(), dist,
                cmd, rat.isInSittingPose(), caged,
                rat.getMainHandItem().copy(), upgrades,
                status[0], status[1]
        );
    }

    public static List<Row> rows(PuffEntity puff) {
        List<Row> out = new ArrayList<>();
        ServerLevel sl = puff.level() instanceof ServerLevel l ? l : null;
        for (PuffSquad.Member m : puff.getSquad().members()) {
            Entity e = sl == null ? null : sl.getEntity(m.id);
            List<ItemStack> upgrades = new ArrayList<>();
            boolean online = e instanceof TamedRat;
            if (e instanceof TamedRat rat) {
                m.name = rat.getName().getString();
                try {
                    RatUpgradeUtils.forEachUpgrade(rat, item -> item != RatsItemRegistry.RAT_UPGRADE_BASIC.get(),
                            (stack, slot) -> upgrades.add(stack.copyWithCount(1)));
                } catch (Throwable ignored) {
                }
            }
            String[] status = PuffRatJobGoal.STATUS.getOrDefault(m.id, new String[]{"", ""});
            out.add(new Row(m.id, m.name, m.job, online, upgrades, status[0], status[1]));
        }
        return out;
    }
}
