package com.puffcompanion.entity;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * Secret-nest hoarding: when idle (not sitting / resting / riding) and the nest has room, walk to the
 * nearest shiny dropped item within 8 blocks (and not too far from the owner) and tuck it away.
 */
public class PuffHoardGoal extends Goal {
    private static final double SEARCH_RANGE = 8.0;
    private static final double MAX_OWNER_DIST = 12.0;
    private static final double PICKUP_DIST_SQR = 1.6 * 1.6;
    private static final int SCAN_INTERVAL = 20;
    private static final int GIVE_UP_TICKS = 160;

    private final PuffEntity puff;
    @Nullable private ItemEntity target;
    private int nextScan;
    private int runTicks;

    public PuffHoardGoal(PuffEntity puff) {
        this.puff = puff;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean idle() {
        return this.puff.isTame() && !this.puff.isOrderedToSit() && !this.puff.isResting()
                && !this.puff.isPassenger() && !this.puff.isPanicking() && this.puff.getTarget() == null;
    }

    @Override
    public boolean canUse() {
        if (!this.idle() || --this.nextScan > 0) return false;
        this.nextScan = SCAN_INTERVAL;
        this.target = this.findItem();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.idle() && this.target != null && this.target.isAlive() && this.runTicks < GIVE_UP_TICKS
                && hasRoomFor(this.puff.getHoard(), this.target.getItem());
    }

    @Override
    public void start() {
        this.runTicks = 0;
        if (this.target != null) this.puff.getNavigation().moveTo(this.target, 1.1D);
    }

    @Override
    public void stop() {
        this.target = null;
        this.puff.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        this.runTicks++;
        this.puff.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        if (this.puff.distanceToSqr(this.target) <= PICKUP_DIST_SQR) {
            this.pickUp(this.target);
            this.target = null;
        } else if (this.runTicks % 10 == 0) {
            this.puff.getNavigation().moveTo(this.target, 1.1D);
        }
    }

    private void pickUp(ItemEntity item) {
        ItemStack stack = item.getItem();
        int before = stack.getCount();
        ItemStack rest = this.puff.getHoard().addItem(stack.copy());
        int taken = before - rest.getCount();
        if (taken <= 0) return;
        this.puff.take(item, taken);   // vanilla pickup animation + sound
        if (rest.isEmpty()) {
            item.discard();
        } else {
            item.setItem(rest);
        }
        this.puff.onHoardPickup();
    }

    @Nullable
    private ItemEntity findItem() {
        LivingEntity owner = this.puff.getOwner();
        SimpleContainer hoard = this.puff.getHoard();
        return this.puff.level().getEntitiesOfClass(ItemEntity.class, this.puff.getBoundingBox().inflate(SEARCH_RANGE),
                        e -> e.isAlive() && !e.hasPickUpDelay() && PuffHoard.isHoardable(e.getItem())
                                && hasRoomFor(hoard, e.getItem())
                                && (owner == null || e.distanceToSqr(owner) <= MAX_OWNER_DIST * MAX_OWNER_DIST))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(this.puff)))
                .orElse(null);
    }

    static boolean hasRoomFor(SimpleContainer hoard, ItemStack stack) {
        return hoard.canAddItem(stack);
    }
}
