package com.puffcompanion.entity;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.EnumSet;

/**
 * Vanilla ranged weapon combat goal for Puff:
 * supports {@link BowItem}, {@link CrossbowItem}, and {@link TridentItem} (plus modded subclasses).
 * Draws arrows from offhand or Puff's 18-slot secret nest (respecting Infinity), applies Power / Punch / Flame /
 * Quick Charge / Multishot / Piercing / Impaling / Channeling enchantments, and throws a safe non-pickup copy of
 * the trident so enchanted tridents are never lost on the ground. Yields to melee if no weapon/ammo is available.
 */
public class PuffRangedAttackGoal extends Goal {
    private static final double MAX_RANGE = 18.0;
    private static final double MAX_RANGE_SQR = MAX_RANGE * MAX_RANGE;
    private static final double PREFERRED_MIN_SQR = 4.5 * 4.5;

    private final PuffEntity puff;
    private int cooldownTicks;
    private int seeTime;

    public PuffRangedAttackGoal(PuffEntity puff) {
        this.puff = puff;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.puff.canFight()) return false;
        LivingEntity target = this.puff.getTarget();
        if (target == null || !target.isAlive()) return false;
        return !findRangedWeapon().isEmpty() && canFireWeapon(findRangedWeapon());
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.puff.canFight()) return false;
        LivingEntity target = this.puff.getTarget();
        if (target == null || !target.isAlive()) return false;
        ItemStack w = findRangedWeapon();
        return !w.isEmpty() && canFireWeapon(w);
    }

    @Override
    public void stop() {
        this.seeTime = 0;
        this.cooldownTicks = Math.max(this.cooldownTicks, 8);
    }

    @Override
    public void tick() {
        LivingEntity target = this.puff.getTarget();
        if (target == null) return;

        double distSqr = this.puff.distanceToSqr(target);
        boolean canSee = this.puff.getSensing().hasLineOfSight(target);
        if (canSee) this.seeTime++;
        else this.seeTime = 0;

        this.puff.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (distSqr > MAX_RANGE_SQR || this.seeTime < 5) {
            this.puff.getNavigation().moveTo(target, 1.25D);
        } else {
            this.puff.getNavigation().stop();
            if (distSqr < PREFERRED_MIN_SQR && this.puff.getWorkMode() == PuffWorkMode.RANGED_COMBAT) {
                double dx = this.puff.getX() - target.getX();
                double dz = this.puff.getZ() - target.getZ();
                double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                this.puff.getNavigation().moveTo(this.puff.getX() + dx / len * 3.0, this.puff.getY(),
                        this.puff.getZ() + dz / len * 3.0, 1.1D);
            }
        }

        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return;
        }
        if (!canSee || distSqr > MAX_RANGE_SQR) return;

        ItemStack weapon = findRangedWeapon();
        if (weapon.isEmpty()) return;

        if (weapon.getItem() instanceof TridentItem) {
            fireTrident(weapon, target);
            this.cooldownTicks = 32;
        } else if (weapon.getItem() instanceof CrossbowItem) {
            if (fireCrossbow(weapon, target)) {
                int quick = weapon.getEnchantmentLevel(Enchantments.QUICK_CHARGE);
                this.cooldownTicks = Math.max(14, 34 - quick * 5);
            }
        } else if (weapon.getItem() instanceof BowItem) {
            if (fireBow(weapon, target)) {
                this.cooldownTicks = 24;
            }
        }
    }

    private ItemStack findRangedWeapon() {
        ItemStack main = this.puff.getMainHandItem();
        if (isRanged(main)) return main;
        ItemStack off = this.puff.getOffhandItem();
        if (isRanged(off)) return off;
        return ItemStack.EMPTY;
    }

    private static boolean isRanged(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof TridentItem);
    }

    private boolean canFireWeapon(ItemStack weapon) {
        if (weapon.getItem() instanceof TridentItem) return true;
        if (weapon.getEnchantmentLevel(Enchantments.INFINITY_ARROWS) > 0) return true;
        return !findAmmo(false).isEmpty();
    }

    private ItemStack findAmmo(boolean consume) {
        ItemStack off = this.puff.getOffhandItem();
        if (off.getItem() instanceof ArrowItem) {
            ItemStack used = off.copyWithCount(1);
            if (consume) off.shrink(1);
            return used;
        }
        SimpleContainer hoard = this.puff.getHoard();
        for (int i = 0; i < hoard.getContainerSize(); i++) {
            ItemStack s = hoard.getItem(i);
            if (s.getItem() instanceof ArrowItem) {
                ItemStack used = s.copyWithCount(1);
                if (consume) {
                    s.shrink(1);
                    hoard.setChanged();
                }
                return used;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean fireBow(ItemStack bow, LivingEntity target) {
        boolean infinity = bow.getEnchantmentLevel(Enchantments.INFINITY_ARROWS) > 0;
        ItemStack ammo = findAmmo(!infinity);
        if (ammo.isEmpty()) {
            if (!infinity) return false;
            ammo = new ItemStack(Items.ARROW);
        }

        AbstractArrow arrow = ProjectileUtil.getMobArrow(this.puff, ammo, 1.0F);
        if (bow.getItem() instanceof BowItem bowItem) {
            arrow = bowItem.customArrow(arrow);
        }
        int power = bow.getEnchantmentLevel(Enchantments.POWER_ARROWS);
        if (power > 0) arrow.setBaseDamage(arrow.getBaseDamage() + power * 0.5D + 0.5D);
        int punch = bow.getEnchantmentLevel(Enchantments.PUNCH_ARROWS);
        if (punch > 0) arrow.setKnockback(punch);
        if (bow.getEnchantmentLevel(Enchantments.FLAMING_ARROWS) > 0) arrow.setSecondsOnFire(100);
        if (infinity) arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

        shootProjectile(arrow, target, 1.75F, 2.5F);
        this.puff.level().playSound(null, this.puff.getX(), this.puff.getY(), this.puff.getZ(),
                SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F,
                1.0F / (this.puff.getRandom().nextFloat() * 0.4F + 0.8F));
        this.puff.playSwingAnim();
        return true;
    }

    private boolean fireCrossbow(ItemStack crossbow, LivingEntity target) {
        ItemStack ammo = findAmmo(true);
        if (ammo.isEmpty()) return false;

        int multishot = crossbow.getEnchantmentLevel(Enchantments.MULTISHOT) > 0 ? 3 : 1;
        int piercing = crossbow.getEnchantmentLevel(Enchantments.PIERCING);
        float[] angles = multishot == 3 ? new float[]{-10.0F, 0.0F, 10.0F} : new float[]{0.0F};

        for (float angle : angles) {
            AbstractArrow arrow = ProjectileUtil.getMobArrow(this.puff, ammo, 1.0F);
            arrow.setShotFromCrossbow(true);
            arrow.setCritArrow(true);
            if (piercing > 0) arrow.setPierceLevel((byte) piercing);
            if (angle != 0.0F) arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

            double dx = target.getX() - this.puff.getX();
            double dy = target.getY(0.35D) - arrow.getY();
            double dz = target.getZ() - this.puff.getZ();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double rad = Math.toRadians(angle);
            double rx = dx * Math.cos(rad) - dz * Math.sin(rad);
            double rz = dx * Math.sin(rad) + dz * Math.cos(rad);
            arrow.shoot(rx, dy + horiz * 0.16D, rz, 2.2F, 1.5F);
            this.puff.level().addFreshEntity(arrow);
        }

        this.puff.level().playSound(null, this.puff.getX(), this.puff.getY(), this.puff.getZ(),
                SoundEvents.CROSSBOW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        this.puff.playSwingAnim();
        return true;
    }

    private void fireTrident(ItemStack trident, LivingEntity target) {
        ThrownTrident proj = new ThrownTrident(this.puff.level(), this.puff, trident.copy());
        proj.pickup = AbstractArrow.Pickup.DISALLOWED; // Keep Puff's real trident in her hand safely
        shootProjectile(proj, target, 1.7F, 2.0F);
        this.puff.level().playSound(null, this.puff.getX(), this.puff.getY(), this.puff.getZ(),
                SoundEvents.TRIDENT_THROW, SoundSource.NEUTRAL, 1.0F, 1.0F);
        this.puff.playSwingAnim();
    }

    private void shootProjectile(AbstractArrow arrow, LivingEntity target, float velocity, float inaccuracy) {
        double dx = target.getX() - this.puff.getX();
        double dy = target.getY(0.35D) - arrow.getY();
        double dz = target.getZ() - this.puff.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.18D, dz, velocity, inaccuracy);
        this.puff.level().addFreshEntity(arrow);
    }
}
