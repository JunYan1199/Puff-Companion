package com.puffcompanion.compat;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Gate for the optional Curios soft dependency. Common code only ever calls this class; everything that
 * touches Curios API types lives in {@link com.puffcompanion.compat.curios.CuriosImpl} and runs only when
 * {@code curios} is loaded, so a pack without Curios never loads those classes.
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CuriosCompat {
    public static final String MODID = "curios";

    /** One Curios slot type and how many of it Puff gets. */
    public record SlotGroup(String id, int count) {}

    private CuriosCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * Puff's slot layout, exactly like a player's: Curios' player slot types and base sizes, plus slot
     * modifiers of what she wears, plus her star-expander bonus. Only types with at least one slot.
     */
    public static List<SlotGroup> layoutFor(PuffEntity puff) {
        return isLoaded() ? com.puffcompanion.compat.curios.CuriosImpl.layoutFor(puff) : List.of();
    }

    /** Every player slot type with Puff's current count (0 included), in Curios order. */
    public static List<SlotGroup> slotSizes(PuffEntity puff) {
        return isLoaded() ? com.puffcompanion.compat.curios.CuriosImpl.slotSizes(puff) : List.of();
    }

    public static boolean isPlayerSlotType(PuffEntity puff, String type) {
        return isLoaded() && com.puffcompanion.compat.curios.CuriosImpl.isPlayerSlotType(puff, type);
    }

    /** Adds {@code delta} expander slots to one type (bonus never below 0). Returns the new slot count. */
    public static int addSlotBonus(PuffEntity puff, String type, int delta) {
        if (!isLoaded()) return 0;
        puff.addCurioSlotBonus(type, delta);
        com.puffcompanion.compat.curios.CuriosImpl.refreshBonus(puff);
        for (SlotGroup g : slotSizes(puff)) if (g.id().equals(type)) return g.count();
        return 0;
    }

    /** May {@code stack} go into a {@code slotType} slot? (false without Curios) */
    public static boolean isValid(String slotType, ItemStack stack, boolean clientSide) {
        return isLoaded() && com.puffcompanion.compat.curios.CuriosImpl.isValid(slotType, stack, clientSide);
    }

    /**
     * Same check the player's own Curios slot uses: the slot type's validators run against a SlotContext for
     * {@code wearer} at {@code index}. Falls back to the item's slot tags when no wearer is known.
     */
    public static boolean isValidFor(String slotType, int index, ItemStack stack, @Nullable LivingEntity wearer, boolean clientSide) {
        return isLoaded() && com.puffcompanion.compat.curios.CuriosImpl.isValidFor(slotType, index, stack, wearer, clientSide);
    }

    /** Server: may this accessory be taken off Puff (curse / item rule, like the player's own slot)? */
    public static boolean canUnequip(PuffEntity puff, String type, int index, ItemStack stack) {
        return !isLoaded() || com.puffcompanion.compat.curios.CuriosImpl.canUnequip(puff, type, index, stack);
    }

    /** Sprite (block atlas) of the slot type's empty-slot icon, e.g. {@code curios:slot/empty_ring_slot}. */
    @Nullable
    public static ResourceLocation slotIcon(String slotType) {
        return isLoaded() ? com.puffcompanion.compat.curios.CuriosImpl.slotIcon(slotType) : null;
    }

    /** Server tick: curio ticks through Puff's FakePlayer proxy + attribute modifiers. */
    public static void tick(PuffEntity puff) {
        if (isLoaded()) com.puffcompanion.compat.curios.CuriosImpl.tick(puff);
    }

    /** Damage bridge through the proxy player (accessory damage effects); -1 = cancelled. */
    public static float bridgeIncomingDamage(PuffEntity puff, net.minecraft.world.damagesource.DamageSource source, float amount) {
        return isLoaded() ? com.puffcompanion.compat.curios.CuriosImpl.bridgeIncomingDamage(puff, source, amount) : amount;
    }

    /** Damage source for Puff's melee hits while she wears accessories (null = use the normal mob source). */
    @Nullable
    public static net.minecraft.world.damagesource.DamageSource attackSource(PuffEntity puff) {
        return isLoaded() ? com.puffcompanion.compat.curios.CuriosImpl.attackSource(puff) : null;
    }

    /** Accessory bridge: Puff's post-armor damage replayed on her proxy player (runs late, after other mods). */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void onLivingDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (isLoaded() && event.getEntity() instanceof PuffEntity puff && !puff.level().isClientSide && puff.isTame()) {
            com.puffcompanion.compat.curios.CuriosImpl.bridgeDamage(puff, event);
        }
    }

    /** Accessory bridge: healing (regeneration boosts). */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void onLivingHeal(net.minecraftforge.event.entity.living.LivingHealEvent event) {
        if (isLoaded() && event.getEntity() instanceof PuffEntity puff && !puff.level().isClientSide && puff.isTame()) {
            com.puffcompanion.compat.curios.CuriosImpl.bridgeHeal(puff, event);
        }
    }

    /** Accessory bridge: fall damage. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void onLivingFall(net.minecraftforge.event.entity.living.LivingFallEvent event) {
        if (isLoaded() && event.getEntity() instanceof PuffEntity puff && !puff.level().isClientSide && puff.isTame()) {
            com.puffcompanion.compat.curios.CuriosImpl.bridgeFall(puff, event);
        }
    }

    /** Set while a wearer-death replay runs: item / XP spawns are swallowed. */
    public static final ThreadLocal<Boolean> BLOCK_DROPS = ThreadLocal.withInitial(() -> false);

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (BLOCK_DROPS.get() && (event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity
                || event.getEntity() instanceof net.minecraft.world.entity.ExperienceOrb)) {
            event.setCanceled(true);
        }
    }

    /** Flushes proxy health/effects/modifiers back to Puff immediately (e.g. after a melee hit). */
    public static void flushProxy(PuffEntity puff) {
        if (isLoaded()) com.puffcompanion.compat.curios.CuriosImpl.flushProxy(puff);
    }

    /** Client/server safe check whether {@code entity} has a specific Curios item equipped. */
    public static boolean hasCurioItem(@Nullable LivingEntity entity, String modid, String path) {
        return entity != null && isLoaded() && com.puffcompanion.compat.curios.CuriosImpl.hasItemEquipped(entity, modid, path);
    }

    /** Wearer death (called from PuffEntity#die before anything is recorded). True = an accessory saved her. */
    public static boolean bridgeDeath(PuffEntity puff, net.minecraft.world.damagesource.DamageSource source) {
        return isLoaded() && com.puffcompanion.compat.curios.CuriosImpl.bridgeDeath(puff, source);
    }

    /** Before Puff's melee hit: damage multiplier from crit accessories, or -1 = attack cancelled. */
    public static float bridgeMeleePre(PuffEntity puff, net.minecraft.world.entity.Entity target) {
        return isLoaded() ? com.puffcompanion.compat.curios.CuriosImpl.bridgeMeleePre(puff, target) : 1F;
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void onChangeTarget(net.minecraftforge.event.entity.living.LivingChangeTargetEvent event) {
        if (isLoaded() && event.getNewTarget() instanceof PuffEntity puff && !puff.level().isClientSide && puff.isTame()) {
            com.puffcompanion.compat.curios.CuriosImpl.bridgeChangeTarget(puff, event);
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void onPuffEffectApplicable(net.minecraftforge.event.entity.living.MobEffectEvent.Applicable event) {
        if (isLoaded() && event.getEntity() instanceof PuffEntity puff && !puff.level().isClientSide && puff.isTame()
                && event.getResult() != net.minecraftforge.eventbus.api.Event.Result.DENY) {
            com.puffcompanion.compat.curios.CuriosImpl.bridgeEffectApplicable(puff, event);
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void onKnockback(net.minecraftforge.event.entity.living.LivingKnockBackEvent event) {
        if (isLoaded() && event.getEntity() instanceof PuffEntity puff && !puff.level().isClientSide && puff.isTame()) {
            com.puffcompanion.compat.curios.CuriosImpl.bridgeKnockback(puff, event);
        }
    }

    /** The Puff whose accessory code is running on this thread right now (curio ticks / event replays). */
    public static final ThreadLocal<PuffEntity> ACTING = new ThreadLocal<>();

    /**
     * Whitelist: targets Puff's accessories must never hurt or debuff - every real player, Puff herself and
     * other Puffs, and pets owned by Puff's owner. (Her proxy player is the wearer, not a target.)
     */
    public static boolean isProtected(PuffEntity puff, net.minecraft.world.entity.Entity target) {
        if (target == puff || target instanceof com.puffcompanion.entity.PuffFakePlayer) return false;
        if (target instanceof net.minecraft.world.entity.player.Player p) return !(p instanceof net.minecraftforge.common.util.FakePlayer);
        if (target instanceof PuffEntity) return true;
        if (target instanceof net.minecraft.world.entity.OwnableEntity pet && pet.getOwnerUUID() != null) {
            return pet.getOwnerUUID().equals(puff.getOwnerUUID());
        }
        return false;
    }

    /** Effects applied while Puff's accessory code runs (gaze / aura curios) skip whitelisted targets. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void onEffectApplicable(net.minecraftforge.event.entity.living.MobEffectEvent.Applicable event) {
        PuffEntity puff = ACTING.get();
        if (puff != null && isProtected(puff, event.getEntity())) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }

    /** Damage dealt by Puff's proxy player (or during her accessory code) never hits whitelisted targets. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        PuffEntity puff = ACTING.get();
        if (puff == null && event.getSource().getEntity() instanceof com.puffcompanion.entity.PuffFakePlayer proxy) {
            puff = proxy.getPuff();
        }
        if (puff != null && isProtected(puff, event.getEntity())) event.setCanceled(true);
    }

    /** True while {@code /puff debug curiotest} runs: Puff must never die from the test hits. */
    public static volatile boolean selfTestRunning;

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (selfTestRunning && event.getEntity() instanceof PuffEntity puff) {
            event.setCanceled(true);
            puff.setHealth(1.0F);
        }
    }

}
