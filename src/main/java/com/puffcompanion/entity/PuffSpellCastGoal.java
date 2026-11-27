package com.puffcompanion.entity;

import com.puffcompanion.compat.SpellCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Stage 7 combat spellcasting AI for Puff: when Goety or Iron's Spells is installed and Puff has a ready
 * spell, she pauses at mid-range (3..18 blocks), turns toward her target, triggers {@code action_spellcast},
 * and fires the spell through {@link SpellCompat}. When all spells are on cooldown or none are carried,
 * this goal yields cleanly to {@code PuffMeleeGoal}.
 */
public class PuffSpellCastGoal extends Goal {
    private static final double MIN_RANGE_SQR = 2.5 * 2.5;
    private static final double MAX_RANGE_SQR = 18.0 * 18.0;
    private static final int AIM_TICKS = 5;
    private static final int GLOBAL_GAP_TICKS = 22;

    private final PuffEntity puff;
    private int windup;
    private long nextCastTime;

    public PuffSpellCastGoal(PuffEntity puff) {
        this.puff = puff;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!SpellCompat.isAnySpellModLoaded()) return false;
        if (this.puff.getWorkMode() != PuffWorkMode.SPELL_COMBAT) return false;
        if (!this.puff.canFight() || !this.puff.isSpellCastEnabled()) return false;
        if (this.puff.level().getGameTime() < this.nextCastTime) return false;
        LivingEntity target = this.puff.getTarget();
        if (target == null || !target.isAlive()) return false;
        double d2 = this.puff.distanceToSqr(target);
        if (d2 < MIN_RANGE_SQR || d2 > MAX_RANGE_SQR) return false;
        if (!this.puff.getSensing().hasLineOfSight(target)) return false;
        return SpellCompat.hasReadyCombatSpell(this.puff);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.windup <= 0) return false;
        if (this.puff.getWorkMode() != PuffWorkMode.SPELL_COMBAT) return false;
        LivingEntity target = this.puff.getTarget();
        return this.puff.canFight() && this.puff.isSpellCastEnabled()
                && target != null && target.isAlive()
                && this.puff.distanceToSqr(target) <= MAX_RANGE_SQR * 1.25;
    }

    @Override
    public void start() {
        this.windup = AIM_TICKS;
        this.puff.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.puff.getTarget();
        if (target == null) {
            this.windup = 0;
            return;
        }
        this.puff.getLookControl().setLookAt(target, 30.0F, 30.0F);
        this.puff.getNavigation().stop();
        if (--this.windup == 0) {
            long now = this.puff.level().getGameTime();
            boolean cast = SpellCompat.tryCastCombatSpell(this.puff, target);
            if (cast) {
                this.puff.triggerAnim(PuffEntity.ACTION_CONTROLLER, PuffEntity.ANIM_SPELLCAST);
                this.nextCastTime = now + GLOBAL_GAP_TICKS;
            } else {
                this.nextCastTime = now + 20;
            }
        }
    }
}
