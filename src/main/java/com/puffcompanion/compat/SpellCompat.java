package com.puffcompanion.compat;

import com.puffcompanion.entity.PuffEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Soft-dependency entry point for Stage 7 spellcasting integrations:
 * Goety ({@code goety}) and Iron's Spells 'n Spellbooks ({@code irons_spellbooks}).
 * Safe to call when neither mod is installed.
 */
public final class SpellCompat {
    public static final String GOETY_ID = "goety";
    public static final String IRONS_ID = "irons_spellbooks";

    private SpellCompat() {}

    public static boolean isGoetyLoaded() {
        return ModList.get().isLoaded(GOETY_ID);
    }

    public static boolean isIronsLoaded() {
        return ModList.get().isLoaded(IRONS_ID);
    }

    public static boolean isAnySpellModLoaded() {
        return isGoetyLoaded() || isIronsLoaded();
    }

    /** True if Puff currently carries at least one ready combat spell from Goety or Iron's Spells. */
    public static boolean hasReadyCombatSpell(PuffEntity puff) {
        if (!puff.isSpellCastEnabled() || puff.getSpellMana() <= 0) return false;
        if (isIronsLoaded() && com.puffcompanion.compat.ironsspellbooks.IronsSpellsImpl.hasReadySpell(puff)) {
            return true;
        }
        return isGoetyLoaded() && com.puffcompanion.compat.goety.GoetyImpl.hasReadySpell(puff);
    }

    /** True when {@code stack} is a physical staff or wand (preferred over spellbooks in third-person hand). */
    public static boolean isCastingStaffOrWand(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (isGoetyLoaded() && com.puffcompanion.compat.goety.GoetyImpl.isWand(stack)) return true;
        return isIronsLoaded() && com.puffcompanion.compat.ironsspellbooks.IronsSpellsImpl.isStaff(stack);
    }

    /** Returns the first ready wand / staff / spellbook / scroll Puff can currently cast with (prefers staff/wand). */
    public static ItemStack peekReadySpellItem(PuffEntity puff) {
        if (!puff.isSpellCastEnabled() || puff.getSpellMana() <= 0) return ItemStack.EMPTY;
        if (isCastingStaffOrWand(puff.getMainHandItem())) return puff.getMainHandItem();
        if (isGoetyLoaded()) {
            ItemStack s = com.puffcompanion.compat.goety.GoetyImpl.findReadySpellItem(puff);
            if (!s.isEmpty()) return s;
        }
        if (isIronsLoaded()) {
            ItemStack s = com.puffcompanion.compat.ironsspellbooks.IronsSpellsImpl.findReadySpellItem(puff);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

    /**
     * Attempts to cast one ready combat spell at {@code target} using Puff's proxy player.
     * Returns true if a spell was successfully fired.
     */
    public static boolean tryCastCombatSpell(PuffEntity puff, LivingEntity target) {
        if (!puff.isSpellCastEnabled() || target == null || !target.isAlive()) return false;
        if (isIronsLoaded() && com.puffcompanion.compat.ironsspellbooks.IronsSpellsImpl.tryCast(puff, target)) {
            return true;
        }
        if (isGoetyLoaded() && com.puffcompanion.compat.goety.GoetyImpl.tryCast(puff, target)) {
            return true;
        }
        return false;
    }

    /** Human-readable list of detected spells on Puff (for debug / status display). */
    public static List<String> describeAvailableSpells(PuffEntity puff) {
        List<String> out = new ArrayList<>();
        if (isIronsLoaded()) {
            out.addAll(com.puffcompanion.compat.ironsspellbooks.IronsSpellsImpl.describeSpells(puff));
        }
        if (isGoetyLoaded()) {
            out.addAll(com.puffcompanion.compat.goety.GoetyImpl.describeSpells(puff));
        }
        return out;
    }
}
