package com.puffcompanion.entity;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.CuriosCompat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic (Curios-independent) part of Puff's proxy-player bridge: posting the player tick events for her
 * proxy and ticking her armor "as a player" ({@code onArmorTick} / {@code inventoryTick}), so modded armor
 * set bonuses and per-tick armor effects reach her. Effects, healing etc. the armor gives the proxy are
 * synced back to Puff by {@link PuffFakePlayer#syncBackTo}.
 */
public final class PuffProxyBridge {
    public static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int MAX_FAILURES = 3;

    /** True while an event replay / proxy tick runs (stops re-entrant bridging). */
    public static final ThreadLocal<Boolean> BRIDGING = ThreadLocal.withInitial(() -> false);
    /** Armor items whose tick keeps throwing are skipped (third-party safety). */
    private static final Map<Item, Integer> FAILURES = new HashMap<>();

    private PuffProxyBridge() {}

    public static boolean hasArmor(PuffEntity puff) {
        for (EquipmentSlot slot : ARMOR) if (!puff.getItemBySlot(slot).isEmpty()) return true;
        return false;
    }

    /**
     * The proxy is never in the level's entity list, so nothing ticks it. Many mods run passive effects from
     * the wearer's tick events (Curios itself ticks the proxy's accessories from LivingTickEvent: curioTick,
     * onEquip / onUnequip, slot modifiers), so post them for the proxy.
     */
    public static void postPlayerTick(PuffFakePlayer proxy) {
        proxy.ticksAlive++;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(proxy.getPuff());
        try {
            MinecraftForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, proxy));
            MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingTickEvent(proxy));
            MinecraftForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, proxy));
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] proxy tick bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** Ticks Puff's 4 armor pieces as the proxy player's armor (same ItemStack instances as Puff's). */
    public static void tickArmor(PuffFakePlayer proxy) {
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack stack = proxy.getItemBySlot(ARMOR[i]);
            if (stack.isEmpty() || FAILURES.getOrDefault(stack.getItem(), 0) >= MAX_FAILURES) continue;
            try {
                stack.inventoryTick(proxy.level(), proxy, ARMOR[i].getIndex(), false);
                stack.onArmorTick(proxy.level(), proxy);
            } catch (Throwable t) {
                int n = FAILURES.merge(stack.getItem(), 1, Integer::sum);
                PuffCompanion.LOGGER.warn("[Puff Companion] Armor {} failed on Puff ({}/{}): {}",
                        ForgeRegistries.ITEMS.getKey(stack.getItem()), n, MAX_FAILURES, t.toString());
            }
        }
    }

    /** Server tick without Curios installed: a proxy session only for armor effects. */
    public static void tickArmorOnly(PuffEntity puff) {
        if (hasArmor(puff)) puff.proxyLinger = 40;
        else if (puff.proxyLinger-- <= 0) return;   // a few more ticks after the last piece came off
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        PuffEntity prev = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.flushTo(puff);
            proxy.syncFrom(puff);
            float healthBefore = proxy.getHealth();
            postPlayerTick(proxy);
            CuriosCompat.ACTING.set(puff);
            tickArmor(proxy);
            proxy.reclaimInventory(puff, java.util.List.of());
            proxy.syncBackTo(puff, healthBefore);
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] armor bridge failed: {}", t.toString());
        } finally {
            CuriosCompat.ACTING.set(prev);
        }
    }
}
