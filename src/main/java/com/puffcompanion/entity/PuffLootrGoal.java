package com.puffcompanion.entity;

import com.puffcompanion.compat.LootrCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Puff's own job (Lootr installed, toggle on in the squad tab): every couple of seconds she looks for a Lootr
 * loot container within 10 blocks she has not opened yet, walks over and takes her own share (her proxy
 * player's separate Lootr inventory) into the secret nest. Never runs while she sits, rests, rides or fights.
 */
public class PuffLootrGoal extends Goal {
    private static final int RADIUS = 10;
    private static final int TIMEOUT = 200;
    private final PuffEntity puff;
    private int scanTimer;
    private int age;

    public PuffLootrGoal(PuffEntity puff) {
        this.puff = puff;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean free() {
        return this.puff.isTame() && this.puff.getSquad().autoLootr && LootrCompat.isLoaded() && !this.puff.isOrderedToSit()
                && !this.puff.isResting() && !this.puff.isPassenger() && this.puff.getTarget() == null
                && this.puff.level() instanceof ServerLevel;
    }

    @Override
    public boolean canUse() {
        if (!this.free()) return false;
        if (this.puff.getSquad().lootrTarget != null) return true;
        if (--this.scanTimer > 0) return false;
        this.scanTimer = 40;
        this.puff.getSquad().lootrTarget = LootrCompat.findNear(this.puff, RADIUS);
        return this.puff.getSquad().lootrTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.free() && this.puff.getSquad().lootrTarget != null;
    }

    @Override
    public void start() {
        this.age = 0;
    }

    @Override
    public void stop() {
        this.puff.getNavigation().stop();
    }

    @Override
    public void tick() {
        PuffSquad squad = this.puff.getSquad();
        BlockPos pos = squad.lootrTarget;
        if (pos == null) return;
        Vec3 c = Vec3.atCenterOf(pos);
        this.puff.getLookControl().setLookAt(c.x, c.y, c.z);
        if (this.puff.position().distanceToSqr(c) > 2.5 * 2.5 && ++this.age < TIMEOUT) {
            if (this.puff.getNavigation().isDone() || this.age % 20 == 0) this.puff.getNavigation().moveTo(c.x, pos.getY(), c.z, 1.1D);
            return;
        }
        this.puff.getNavigation().stop();
        squad.lootrOpened.add(pos.asLong());
        if (squad.lootrOpened.size() > 2048) squad.lootrOpened.clear();   // keep the save small
        squad.lootrTarget = null;
        if (this.age >= TIMEOUT) return;   // unreachable: give up on it
        int count = LootrCompat.open(this.puff, pos);
        if (count > 0 && this.puff.level() instanceof ServerLevel sl) {
            this.puff.say("puff.lootr.found", 3, 100, false, PuffEntity.ANIM_SHY);
            sl.sendParticles(PuffRevival.GOLD_DUST, c.x, c.y + 0.6, c.z, 16, 0.4, 0.3, 0.4, 0.02);
            PuffEventTriggers.hearts(sl, this.puff, 3);
        }
    }
}
