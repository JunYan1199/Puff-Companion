package com.puffcompanion.compat.curios;

import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffFakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer self-test for the accessory bridge ({@code /puff debug curiotest [item]}): temporarily puts one
 * accessory on Puff and checks, on the server, that its typical effects reach her. Default item is Enigmatic
 * Legacy's Blazing Core (fire/lava immunity, extinguish, attackers ignited + hurt, more damage from water mobs).
 * Puff's accessories, health and fire are restored afterwards; test mobs are never added to the world.
 */
public final class CurioSelfTest {
    private CurioSelfTest() {}

    public static void run(PuffEntity puff, String itemId, Consumer<Component> out) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            out.accept(Component.literal("Unknown item " + itemId).withStyle(ChatFormatting.RED));
            return;
        }
        ItemStack stack = new ItemStack(item);
        List<String> types = new ArrayList<>(CuriosApi.getItemStackSlots(stack, false).keySet());
        if (types.isEmpty()) {
            out.accept(Component.literal(itemId + " is not a curio").withStyle(ChatFormatting.RED));
            return;
        }
        String type = types.get(0);
        // Use a free slot of that type - never overwrite her own accessories
        int size = 0;
        for (com.puffcompanion.compat.CuriosCompat.SlotGroup g : com.puffcompanion.compat.CuriosCompat.slotSizes(puff)) {
            if (g.id().equals(type)) size = g.count();
        }
        int index = 0;
        while (index < size && !puff.getCurioStore().get(type, index).isEmpty()) index++;
        if (index >= size) {
            out.accept(Component.literal("No free '" + type + "' slot on Puff (" + size + " slots) - try /puff debug curio_slot "
                    + type + " 1").withStyle(ChatFormatting.RED));
            return;
        }
        final int slot = index;
        float hp = puff.getHealth();
        com.puffcompanion.compat.CuriosCompat.selfTestRunning = true;
        ServerLevel level = (ServerLevel) puff.level();
        out.accept(Component.literal("== Curio bridge self-test: " + itemId + " in '" + type + "' #" + slot + " ==").withStyle(ChatFormatting.GOLD));
        StringBuilder worn = new StringBuilder();
        puff.getCurioStore().all().forEach((t, l) -> l.forEach(s -> {
            if (!s.isEmpty()) worn.append(t).append('=').append(ForgeRegistries.ITEMS.getKey(s.getItem())).append(' ');
        }));
        out.accept(Component.literal("already worn: " + (worn.length() == 0 ? "nothing" : worn.toString().trim())).withStyle(ChatFormatting.GRAY));
        try {
            // Same probes without and with the accessory -> numeric before/after table
            float[] before = probe(puff, level);
            puff.getCurioStore().set(type, slot, stack);
            CuriosImpl.tick(puff);   // mirror into proxy + tick events
            PuffFakePlayer proxy = puff.getFakePlayer();
            boolean mirrored = proxy != null && CuriosApi.getCuriosInventory(proxy).map(inv -> inv.isEquipped(item)).orElse(false);
            report(out, "proxy player wears it (Curios inventory mirror)", mirrored);
            float[] after = probe(puff, level);
            String[] names = {"fire taken", "lava taken", "zombie hit taken", "drowned hit taken", "Puff's melee dealt", "heal(1) received"};
            for (int i = 0; i < names.length; i++) {
                boolean changed = Math.abs(after[i] - before[i]) > 0.005F;
                out.accept(Component.literal(String.format("%s %-20s %.2f -> %.2f", changed ? "[~]" : "   ", names[i], before[i], after[i]))
                        .withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
            }

            puff.setSecondsOnFire(10);
            CuriosImpl.tick(puff);
            out.accept(Component.literal("after 1 tick on fire: fire ticks " + puff.getRemainingFireTicks()).withStyle(ChatFormatting.GRAY));
            Mob drowned = EntityType.DROWNED.create(level);
            float drownedHp = drowned == null ? 0 : drowned.getHealth();
            hitBy(puff, drowned, 2F);
            if (drowned != null && (drowned.getRemainingFireTicks() > 0 || drowned.getHealth() < drownedHp)) {
                out.accept(Component.literal(String.format("[~] attacker punished: fire %d, hp %.1f -> %.1f",
                        drowned.getRemainingFireTicks(), drownedHp, drowned.getHealth())).withStyle(ChatFormatting.YELLOW));
            }

            net.minecraft.world.damagesource.DamageSource src = CuriosImpl.attackSource(puff);
            report(out, "Puff's own hits are player melee hits by her proxy (on-hit effects)",
                    src != null && src.getEntity() instanceof Player && src.getDirectEntity() == src.getEntity());
            // Whitelist: effects from accessory code must skip the owner but still hit hostile mobs
            net.minecraft.world.entity.LivingEntity owner = puff.getOwner();
            if (owner != null) {
                com.puffcompanion.compat.CuriosCompat.ACTING.set(puff);
                boolean ownerHit, mobHit;
                Mob victim = EntityType.ZOMBIE.create(level);
                try {
                    ownerHit = owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 1));
                    mobHit = victim != null && victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 1));
                } finally {
                    com.puffcompanion.compat.CuriosCompat.ACTING.remove();
                }
                report(out, "whitelist: accessory debuff blocked on owner, still lands on mobs", !ownerHit && mobHit);
                boolean ownerDamaged = owner.hurt(owner.damageSources().playerAttack(CuriosImpl.proxyOf(puff)), 0.01F);
                report(out, "whitelist: proxy damage to owner cancelled", !ownerDamaged);
            }
            StringBuilder blocked = new StringBuilder();
            for (net.minecraft.world.effect.MobEffect e : new net.minecraft.world.effect.MobEffect[]{
                    net.minecraft.world.effect.MobEffects.POISON, net.minecraft.world.effect.MobEffects.WITHER,
                    net.minecraft.world.effect.MobEffects.WEAKNESS, net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                    net.minecraft.world.effect.MobEffects.BLINDNESS, net.minecraft.world.effect.MobEffects.CONFUSION,
                    net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN, net.minecraft.world.effect.MobEffects.DARKNESS}) {
                boolean had = puff.hasEffect(e);
                if (!had && !puff.addEffect(new net.minecraft.world.effect.MobEffectInstance(e, 40))) {
                    blocked.append(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getKey(e).getPath()).append(' ');
                }
                if (!had) puff.removeEffect(e);
            }
            out.accept(Component.literal("negative effects blocked: " + (blocked.length() == 0 ? "none" : blocked.toString().trim()))
                    .withStyle(ChatFormatting.AQUA));
        } catch (Throwable t) {
            out.accept(Component.literal("Self-test crashed: " + t).withStyle(ChatFormatting.RED));
        } finally {
            puff.getCurioStore().set(type, slot, ItemStack.EMPTY);
            com.puffcompanion.compat.CuriosCompat.selfTestRunning = false;
            CuriosImpl.tick(puff);
            reset(puff);
            puff.clearFire();
            puff.setHealth(hp);
        }
    }

    /** fire, lava, zombie hit, drowned hit (damage Puff takes), Puff's melee (damage dealt), heal received. */
    private static float[] probe(PuffEntity puff, ServerLevel level) {
        float fire = taken(puff, puff.damageSources().inFire(), 2F);
        float lava = taken(puff, puff.damageSources().lava(), 2F);
        float zombie = hitBy(puff, EntityType.ZOMBIE.create(level), 2F);
        float drowned = hitBy(puff, EntityType.DROWNED.create(level), 2F);
        net.minecraft.world.damagesource.DamageSource src = CuriosImpl.attackSource(puff);
        float dealt = dealTo(puff, level, src != null ? src : puff.damageSources().mobAttack(puff));
        float heal = healAmount(puff, 1F);
        reset(puff);
        puff.clearFire();
        return new float[]{fire, lava, zombie, drowned, dealt, heal};
    }

    private static float taken(PuffEntity puff, net.minecraft.world.damagesource.DamageSource source, float amount) {
        reset(puff);
        puff.clearFire();
        float before = puff.getHealth();
        puff.hurt(source, amount);
        return before - puff.getHealth();
    }

    /** Health a fresh zombie loses from a 5-damage hit with {@code source} (not added to the world). */
    private static float dealTo(PuffEntity puff, ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
        Mob z = EntityType.ZOMBIE.create(level);
        if (z == null || source == null) return -1;
        z.moveTo(puff.getX() + 1.5, puff.getY(), puff.getZ(), 0, 0);
        float before = z.getHealth();
        z.hurt(source, 5F);
        return before - z.getHealth();
    }

    private static float healAmount(PuffEntity puff, float amount) {
        puff.setHealth(1F);
        puff.heal(amount);
        return puff.getHealth() - 1F;
    }

    /** Health Puff loses from one {@code amount} melee hit by {@code attacker} (health refilled first). */
    private static float hitBy(PuffEntity puff, Mob attacker, float amount) {
        if (attacker == null) return -1;
        attacker.moveTo(puff.getX() + 1.5, puff.getY(), puff.getZ(), 0, 0);
        reset(puff);
        float before = puff.getHealth();
        puff.hurt(puff.damageSources().mobAttack(attacker), amount);
        return before - puff.getHealth();
    }

    private static void reset(PuffEntity puff) {
        puff.invulnerableTime = 0;
        puff.hurtTime = 0;
        puff.setHealth(puff.getMaxHealth());
    }

    private static void report(Consumer<Component> out, String what, boolean ok) {
        out.accept(Component.literal((ok ? "[OK] " : "[--] ") + what).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
}
