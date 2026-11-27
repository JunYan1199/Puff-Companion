package com.puffcompanion.compat.goety;

import com.Polarice3.Goety.api.items.magic.IWand;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffFakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Goety spellcasting bridge for Puff (only class-loaded when {@code goety} is installed).
 * Scans mainhand, offhand, and her 18-slot hoard inventory for {@link IWand} items with a slotted focus,
 * aims {@link PuffFakePlayer} straight at the target, and invokes {@link ISpell#startSpell} / {@link ISpell#SpellResult}.
 */
public final class GoetyImpl {
    private static final String CD_PREFIX = "goety:";

    private GoetyImpl() {}

    public static boolean isWand(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof IWand;
    }

    public static boolean hasReadySpell(PuffEntity puff) {
        return !findReadySpellItem(puff).isEmpty();
    }

    public static ItemStack findReadySpellItem(PuffEntity puff) {
        long now = puff.level().getGameTime();
        for (ItemStack stack : collectWands(puff)) {
            if (!(stack.getItem() instanceof IWand wand)) continue;
            ISpell spell = safeGetSpell(wand, stack);
            if (spell == null) continue;
            String key = CD_PREFIX + spell.getClass().getName();
            if (puff.isSpellOnCooldown(key, now)) continue;
            int cost = manaCostFor(spell);
            if (puff.getSpellMana() >= cost) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static boolean tryCast(PuffEntity puff, LivingEntity target) {
        if (!(puff.level() instanceof ServerLevel level)) return false;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return false;

        long now = level.getGameTime();
        for (ItemStack stack : collectWands(puff)) {
            if (!(stack.getItem() instanceof IWand wand)) continue;
            ISpell spell = safeGetSpell(wand, stack);
            if (spell == null) continue;

            String key = CD_PREFIX + spell.getClass().getName();
            if (puff.isSpellOnCooldown(key, now)) continue;
            int cost = manaCostFor(spell);
            if (puff.getSpellMana() < cost) continue;

            try {
                proxy.syncFrom(puff);
                aimAt(proxy, puff, target);
                ItemStack previousMain = proxy.getMainHandItem();
                proxy.setItemInHand(InteractionHand.MAIN_HAND, stack);

                SpellStat stat = spell.defaultStats();
                if (!spell.conditionsMet(level, proxy, stat)) {
                    proxy.setItemInHand(InteractionHand.MAIN_HAND, previousMain);
                    puff.setSpellCooldown(key, now + 60);
                    continue;
                }

                spell.startSpell(level, proxy, stack, stat);
                spell.SpellResult(level, proxy, stack, stat);
                proxy.setItemInHand(InteractionHand.MAIN_HAND, previousMain);
                proxy.flushTo(puff);

                int cooldownTicks = Math.max(25, spell.spellCooldown(proxy));
                puff.consumeSpellMana(cost);
                puff.setSpellCooldown(key, now + cooldownTicks);
                if (!isWand(puff.getMainHandItem())) {
                    puff.showCastDisplayStack(stack, 26);
                }
                return true;
            } catch (Throwable t) {
                puff.setSpellCooldown(key, now + 60);
                PuffCompanion.LOGGER.debug("[Puff Companion] Goety spell {} failed safely: {}",
                        spell.getClass().getSimpleName(), t.getMessage());
            }
        }
        return false;
    }

    public static List<String> describeSpells(PuffEntity puff) {
        List<String> list = new ArrayList<>();
        for (ItemStack stack : collectWands(puff)) {
            if (!(stack.getItem() instanceof IWand wand)) continue;
            ISpell spell = safeGetSpell(wand, stack);
            if (spell != null) {
                list.add("Goety:" + spell.getClass().getSimpleName());
            }
        }
        return list;
    }

    private static List<ItemStack> collectWands(PuffEntity puff) {
        List<ItemStack> out = new ArrayList<>();
        if (puff.getMainHandItem().getItem() instanceof IWand) out.add(puff.getMainHandItem());
        if (puff.getOffhandItem().getItem() instanceof IWand) out.add(puff.getOffhandItem());
        var hoard = puff.getHoard();
        for (int i = 0; i < hoard.getContainerSize(); i++) {
            ItemStack s = hoard.getItem(i);
            if (s.getItem() instanceof IWand) out.add(s);
        }
        return out;
    }

    private static ISpell safeGetSpell(IWand wand, ItemStack stack) {
        try {
            return wand.getSpell(stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int manaCostFor(ISpell spell) {
        int raw = Math.max(0, spell.defaultSoulCost());
        return Mth.clamp(Math.max(10, raw / 3), 10, 60);
    }

    private static void aimAt(PuffFakePlayer proxy, PuffEntity puff, LivingEntity target) {
        Vec3 from = puff.getEyePosition();
        Vec3 to = target.getEyePosition();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
        proxy.setPos(puff.getX(), puff.getY(), puff.getZ());
        proxy.setYRot(yaw);
        proxy.setYHeadRot(yaw);
        proxy.setXRot(pitch);
    }
}
