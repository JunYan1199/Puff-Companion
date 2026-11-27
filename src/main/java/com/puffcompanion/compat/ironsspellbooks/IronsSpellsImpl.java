package com.puffcompanion.compat.ironsspellbooks;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffFakePlayer;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Iron's Spells 'n Spellbooks bridge for Puff (only class-loaded when {@code irons_spellbooks} is installed).
 * Discovers spell books, staves, and scrolls across mainhand, offhand, Curios slots, and her 18-slot hoard,
 * syncs Puff's mana into {@link MagicData}, aims {@link PuffFakePlayer} at the target, and casts via {@link AbstractSpell#onCast}.
 */
public final class IronsSpellsImpl {
    private static final String CD_PREFIX = "iss:";

    private IronsSpellsImpl() {}

    public static boolean isStaff(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof io.redspace.ironsspellbooks.item.weapons.StaffItem) return true;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && (id.getPath().contains("staff") || id.getPath().contains("wand"));
    }

    public static boolean hasReadySpell(PuffEntity puff) {
        return !findReadySpellItem(puff).isEmpty();
    }

    public static ItemStack findReadySpellItem(PuffEntity puff) {
        long now = puff.level().getGameTime();
        for (ItemStack stack : collectContainers(puff)) {
            ISpellContainer container = safeGetContainer(stack);
            if (container == null || container.isEmpty()) continue;
            boolean isScroll = isScrollItem(stack);
            for (SpellSlot slot : container.getActiveSpells()) {
                SpellData data = slot.spellData();
                if (data == null || data == SpellData.EMPTY) continue;
                AbstractSpell spell = data.getSpell();
                if (spell == null) continue;
                String key = CD_PREFIX + spell.getSpellId();
                if (puff.isSpellOnCooldown(key, now)) continue;
                int cost = isScroll ? 0 : manaCostFor(spell, Math.max(1, data.getLevel()));
                if (puff.getSpellMana() >= cost) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean tryCast(PuffEntity puff, LivingEntity target) {
        if (!(puff.level() instanceof ServerLevel level)) return false;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return false;

        long now = level.getGameTime();
        for (ItemStack stack : collectContainers(puff)) {
            ISpellContainer container = safeGetContainer(stack);
            if (container == null || container.isEmpty()) continue;
            boolean isScroll = isScrollItem(stack);

            for (SpellSlot slot : container.getActiveSpells()) {
                SpellData data = slot.spellData();
                if (data == null || data == SpellData.EMPTY) continue;
                AbstractSpell spell = data.getSpell();
                if (spell == null) continue;

                String key = CD_PREFIX + spell.getSpellId();
                if (puff.isSpellOnCooldown(key, now)) continue;
                int spellLevel = Math.max(1, data.getLevel());
                int cost = isScroll ? 0 : manaCostFor(spell, spellLevel);
                if (puff.getSpellMana() < cost) continue;

                try {
                    proxy.syncFrom(puff);
                    aimAt(proxy, puff, target);
                    ItemStack prevMain = proxy.getMainHandItem();
                    proxy.setItemInHand(InteractionHand.MAIN_HAND, stack);

                    MagicData magicData = MagicData.getPlayerMagicData(proxy);
                    magicData.setMana(Math.max(puff.getSpellMana(), spell.getManaCost(spellLevel) + 10));

                    if (!spell.checkPreCastConditions(level, spellLevel, proxy, magicData)) {
                        proxy.setItemInHand(InteractionHand.MAIN_HAND, prevMain);
                        puff.setSpellCooldown(key, now + 60);
                        continue;
                    }

                    CastSource source = isScroll ? CastSource.SCROLL : CastSource.SPELLBOOK;
                    spell.onServerPreCast(level, spellLevel, proxy, magicData);
                    spell.onCast(level, spellLevel, proxy, source, magicData);
                    if (spell.getCastType() == io.redspace.ironsspellbooks.api.spells.CastType.CONTINUOUS) {
                        spell.onServerCastTick(level, spellLevel, proxy, magicData);
                    }
                    spell.onServerCastComplete(level, spellLevel, proxy, magicData, false);
                    magicData.resetCastingState();

                    proxy.setItemInHand(InteractionHand.MAIN_HAND, prevMain);
                    proxy.flushTo(puff);

                    if (isScroll) {
                        stack.shrink(1);
                    } else if (cost > 0) {
                        puff.consumeSpellMana(cost);
                    }
                    int cooldownTicks = Math.max(30, spell.getSpellCooldown());
                    puff.setSpellCooldown(key, now + cooldownTicks);
                    if (!com.puffcompanion.compat.SpellCompat.isCastingStaffOrWand(puff.getMainHandItem())) {
                        puff.showCastDisplayStack(stack, 26);
                    }
                    return true;
                } catch (Throwable t) {
                    puff.setSpellCooldown(key, now + 60);
                    PuffCompanion.LOGGER.debug("[Puff Companion] Iron's spell {} failed safely: {}",
                            spell.getSpellId(), t.getMessage());
                }
            }
        }
        return false;
    }

    public static List<String> describeSpells(PuffEntity puff) {
        List<String> list = new ArrayList<>();
        for (ItemStack stack : collectContainers(puff)) {
            ISpellContainer container = safeGetContainer(stack);
            if (container == null || container.isEmpty()) continue;
            for (SpellSlot slot : container.getActiveSpells()) {
                SpellData data = slot.spellData();
                if (data != null && data != SpellData.EMPTY && data.getSpell() != null) {
                    list.add("ISS:" + data.getSpell().getSpellName() + " Lv." + data.getLevel());
                }
            }
        }
        return list;
    }

    private static List<ItemStack> collectContainers(PuffEntity puff) {
        List<ItemStack> out = new ArrayList<>();
        if (safeIsContainer(puff.getMainHandItem())) out.add(puff.getMainHandItem());
        if (safeIsContainer(puff.getOffhandItem())) out.add(puff.getOffhandItem());
        puff.getCurioStore().all().values().forEach(list -> {
            for (ItemStack s : list) {
                if (safeIsContainer(s)) out.add(s);
            }
        });
        var hoard = puff.getHoard();
        for (int i = 0; i < hoard.getContainerSize(); i++) {
            ItemStack s = hoard.getItem(i);
            if (safeIsContainer(s)) out.add(s);
        }
        return out;
    }

    private static boolean safeIsContainer(ItemStack stack) {
        if (stack.isEmpty()) return false;
        try {
            return ISpellContainer.isSpellContainer(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ISpellContainer safeGetContainer(ItemStack stack) {
        try {
            return ISpellContainer.get(stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isScrollItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.getPath().contains("scroll");
    }

    private static int manaCostFor(AbstractSpell spell, int level) {
        int raw = Math.max(0, spell.getManaCost(level));
        return Mth.clamp(Math.max(10, raw / 2), 10, 75);
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
