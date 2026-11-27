package com.puffcompanion.compat;

import com.github.alexthe666.rats.server.entity.misc.MouseApothecary;
import com.github.alexthe666.rats.server.entity.misc.PlagueDoctor;
import com.github.alexthe666.rats.server.entity.rat.AbstractRat;
import com.github.alexthe666.rats.server.entity.rat.RatCommand;
import com.github.alexthe666.rats.server.entity.rat.TamedRat;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every direct reference to Rats: Reboot 8.1.5 classes lives here. Signatures were checked with
 * {@code javap -cp libs/Rats-Reboot-Forge-1.20.1-8.1.5.jar}:
 * <ul>
 *   <li>{@code AbstractRat extends TamableAnimal} (wild {@code Rat} and tamed {@code TamedRat} share it)</li>
 *   <li>{@code TamedRat}: {@code public boolean isCurrentlyWorking}, {@code isHoldingItemInHands()},
 *       {@code getCommand()} (from {@code InventoryRat}) returning {@code RatCommand}</li>
 *   <li>{@code RatCommand}: {@code public final boolean workCommand}</li>
 *   <li>{@code misc.PlagueDoctor extends AbstractVillager}, {@code misc.MouseApothecary extends PathfinderMob}</li>
 * </ul>
 */
public final class RatsCompat {
    /** Rats cheese blocks (registry ids verified in the jar's blockstates). */
    private static final Set<ResourceLocation> CHEESE_BLOCKS = Set.of(
            new ResourceLocation("rats", "block_of_cheese"),
            new ResourceLocation("rats", "block_of_blue_cheese"),
            new ResourceLocation("rats", "block_of_nether_cheese"));

    private static final int AURA_TICKS = 60;   // Speed I for 3 s, refreshed periodically

    private RatsCompat() {}

    /** Tamed rats (any rat class) owned by {@code owner} within {@code range} of {@code center}. */
    public static List<AbstractRat> ownedRats(Entity center, UUID owner, double range) {
        double r2 = range * range;
        return center.level().getEntitiesOfClass(AbstractRat.class, center.getBoundingBox().inflate(range),
                rat -> rat.isAlive() && rat.isTame() && owner.equals(rat.getOwnerUUID()) && rat.distanceToSqr(center) <= r2);
    }

    /** "Working or hauling": the rat's own working flag, or a work command while carrying something. */
    public static boolean isWorking(AbstractRat rat) {
        if (!(rat instanceof TamedRat tamed)) return false;
        if (tamed.isCurrentlyWorking) return true;
        RatCommand command = tamed.getCommand();
        return command != null && command.workCommand && tamed.isHoldingItemInHands();
    }

    /** Big-sister aura: owned rats near Puff move faster. */
    public static void applyLeaderAura(PuffEntity puff, double range) {
        UUID owner = puff.getOwnerUUID();
        if (owner == null) return;
        for (AbstractRat rat : ownedRats(puff, owner, range)) {
            MobEffectInstance cur = rat.getEffect(MobEffects.MOVEMENT_SPEED);
            if (cur == null || cur.getAmplifier() == 0 && cur.getDuration() < AURA_TICKS / 2) {
                rat.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, AURA_TICKS, 0, true, false, true));
            }
        }
    }

    /** True if any owned rat within range is working / hauling. */
    public static boolean anyRatWorking(PuffEntity puff, double range) {
        UUID owner = puff.getOwnerUUID();
        if (owner == null) return false;
        for (AbstractRat rat : ownedRats(puff, owner, range)) {
            if (isWorking(rat)) return true;
        }
        return false;
    }

    /** Plague Doctor or Mouse Apothecary within range. */
    public static boolean ratsNpcNearby(Entity center, double range) {
        double r2 = range * range;
        return !center.level().getEntitiesOfClass(Entity.class, center.getBoundingBox().inflate(range),
                e -> (e instanceof PlagueDoctor || e instanceof MouseApothecary) && e.isAlive()
                        && e.distanceToSqr(center) <= r2).isEmpty();
    }

    public static boolean isCheeseBlock(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && CHEESE_BLOCKS.contains(id);
    }

    /** Any Rats cheese block within a cube of {@code radius} around {@code center}. */
    public static boolean cheeseBlockNearby(Level level, BlockPos center, int radius) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (level.isLoaded(p) && isCheeseBlock(level.getBlockState(p).getBlock())) return true;
        }
        return false;
    }
}
