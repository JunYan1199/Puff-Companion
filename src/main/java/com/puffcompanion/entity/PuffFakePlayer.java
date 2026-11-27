package com.puffcompanion.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Lightweight player proxy for one Puff, so code that only works with a {@code Player} (player-only curios,
 * and later Lootr containers / spell casting in stages 6-7) can act "as Puff". The UUID is derived
 * deterministically from Puff's UUID, so it is the same every session. It is never added to the level.
 */
public class PuffFakePlayer extends FakePlayer {
    private static final EquipmentSlot[] SYNCED = EquipmentSlot.values();

    @org.jetbrains.annotations.Nullable private net.minecraft.network.chat.Component shownName;
    private java.lang.ref.WeakReference<PuffEntity> puff = new java.lang.ref.WeakReference<>(null);
    private boolean synced;
    private float baseHealth;
    private final java.util.Set<net.minecraft.world.effect.MobEffect> baseEffects = new java.util.HashSet<>();
    /** Attribute modifiers the proxy had at the last sync (to spot ones accessory / armor code adds or removes). */
    private final java.util.Map<net.minecraft.world.entity.ai.attributes.Attribute, java.util.Set<UUID>> baseModifiers = new java.util.HashMap<>();

    // ---- Curios bridge state (plain Minecraft types; managed by CuriosImpl) ----
    /** Persisted slot modifiers were restored onto this proxy's Curios inventory. */
    public boolean curiosPrepared;
    /** Slot type -> the stacks last written into the proxy's Curios slots (identity), to spot mod-made changes. */
    public final java.util.Map<String, net.minecraft.world.item.ItemStack[]> mirrored = new java.util.HashMap<>();
    /** Player-tick sessions this proxy has had (its very first ones are its "first join" for other mods). */
    public int ticksAlive;
    /** Slot type -> expander bonus currently applied as a slot modifier. */
    public final java.util.Map<String, Integer> appliedBonus = new java.util.HashMap<>();

    /** The Puff this proxy currently stands in for (null if unloaded). */
    @org.jetbrains.annotations.Nullable
    public PuffEntity getPuff() {
        return this.puff.get();
    }

    public PuffFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    /** Death messages, kill credit text etc. show Puff's name instead of the proxy profile name. */
    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return this.shownName != null ? this.shownName : super.getDisplayName();
    }

    /** Stable proxy UUID for a Puff. */
    public static UUID deriveUuid(UUID puffId) {
        return UUID.nameUUIDFromBytes(("puffcompanion:proxy:" + puffId).getBytes(StandardCharsets.UTF_8));
    }

    public static PuffFakePlayer create(PuffEntity puff) {
        UUID id = deriveUuid(puff.getUUID());
        // Player names are limited to 16 chars
        String name = ("Puff_" + id.toString().replace("-", "")).substring(0, 16);
        return new PuffFakePlayer((ServerLevel) puff.level(), new GameProfile(id, name));
    }

    /**
     * Mirrors Puff's state onto the proxy: position, rotation, hands/armor, health, fire, air, absorption and
     * active effects, so wearer-checks in accessory code ("is the wearer burning / poisoned / in water?") see
     * Puff's real situation.
     */
    public void syncFrom(PuffEntity puff) {
        this.puff = new java.lang.ref.WeakReference<>(puff);
        this.shownName = puff.getDisplayName();
        this.moveTo(puff.getX(), puff.getY(), puff.getZ(), puff.getYRot(), puff.getXRot());
        this.setYHeadRot(puff.getYHeadRot());
        this.setOnGround(puff.onGround());
        this.setDeltaMovement(puff.getDeltaMovement());
        for (EquipmentSlot slot : SYNCED) {
            // Armor: the very same stacks (charge / durability / NBT written by armor ticks lands on Puff's
            // pieces); hands: copies, so nothing can "use up" Puff's weapon through the proxy.
            ItemStack hers = puff.getItemBySlot(slot);
            ItemStack want = slot.getType() == EquipmentSlot.Type.ARMOR ? hers : hers.copy();
            if (this.getItemBySlot(slot) != want) this.setItemSlot(slot, want);
        }
        this.setHealth(Math.max(1.0F, Math.min(this.getMaxHealth(), puff.getHealth())));
        this.setRemainingFireTicks(puff.getRemainingFireTicks());
        this.setAirSupply(puff.getAirSupply());
        this.setAbsorptionAmount(puff.getAbsorptionAmount());
        this.removeAllEffects();
        for (MobEffectInstance effect : puff.getActiveEffects()) {
            this.forceAddEffect(new MobEffectInstance(effect), null);
        }
        this.copyAttributes(puff);
        this.markBaseline();
    }

    /**
     * Proxy attributes = Puff's (base values and every modifier: growth, equipment, accessories...). Event
     * handlers of accessory mods read the wearer's attributes, and the wearer they see is this proxy.
     */
    private void copyAttributes(PuffEntity puff) {
        for (net.minecraft.world.entity.ai.attributes.Attribute attr : net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValues()) {
            net.minecraft.world.entity.ai.attributes.AttributeInstance mine = this.getAttribute(attr);
            net.minecraft.world.entity.ai.attributes.AttributeInstance hers = puff.getAttribute(attr);
            if (mine == null || hers == null) continue;
            if (mine.getBaseValue() != hers.getBaseValue()) mine.setBaseValue(hers.getBaseValue());
            java.util.Set<net.minecraft.world.entity.ai.attributes.AttributeModifier> want = hers.getModifiers();
            java.util.Set<net.minecraft.world.entity.ai.attributes.AttributeModifier> have = mine.getModifiers();
            if (want.size() == have.size() && have.containsAll(want)) continue;
            for (net.minecraft.world.entity.ai.attributes.AttributeModifier m : new ArrayList<>(have)) mine.removeModifier(m.getId());
            for (net.minecraft.world.entity.ai.attributes.AttributeModifier m : want) {
                if (mine.getModifier(m.getId()) == null) mine.addTransientModifier(m);
            }
        }
    }

    /** Remembers the proxy state we last agreed on with Puff, so later changes can be told apart. */
    private void markBaseline() {
        this.synced = true;
        this.baseHealth = this.getHealth();
        this.baseEffects.clear();
        for (MobEffectInstance e : this.getActiveEffects()) this.baseEffects.add(e.getEffect());
        this.baseModifiers.clear();
        for (net.minecraft.world.entity.ai.attributes.Attribute attr : net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValues()) {
            net.minecraft.world.entity.ai.attributes.AttributeInstance inst = this.getAttribute(attr);
            if (inst == null || inst.getModifiers().isEmpty()) continue;
            java.util.Set<UUID> ids = new java.util.HashSet<>();
            for (net.minecraft.world.entity.ai.attributes.AttributeModifier m : inst.getModifiers()) ids.add(m.getId());
            this.baseModifiers.put(attr, ids);
        }
    }

    /**
     * Many accessories / armor pieces add attribute modifiers straight onto the wearer in onEquip / curioTick /
     * onArmorTick instead of declaring them (e.g. "nightmare" style curses lowering damage and max health). The
     * wearer they see is this proxy, so pass new modifiers on to Puff and take back the ones the code removed
     * again. Only modifiers that came this way are ever removed from Puff.
     */
    private void syncModifiersBack(PuffEntity puff) {
        java.util.Map<net.minecraft.world.entity.ai.attributes.Attribute, java.util.Set<UUID>> mine = puff.getProxyAttributeModifiers();
        for (net.minecraft.world.entity.ai.attributes.Attribute attr : net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValues()) {
            net.minecraft.world.entity.ai.attributes.AttributeInstance proxyInst = this.getAttribute(attr);
            net.minecraft.world.entity.ai.attributes.AttributeInstance puffInst = puff.getAttribute(attr);
            if (proxyInst == null || puffInst == null) continue;
            java.util.Set<UUID> before = this.baseModifiers.getOrDefault(attr, java.util.Set.of());
            java.util.Set<UUID> now = new java.util.HashSet<>();
            for (net.minecraft.world.entity.ai.attributes.AttributeModifier m : proxyInst.getModifiers()) {
                now.add(m.getId());
                if (!before.contains(m.getId()) && puffInst.getModifier(m.getId()) == null) {
                    puffInst.addTransientModifier(m);
                    mine.computeIfAbsent(attr, a -> new java.util.HashSet<>()).add(m.getId());
                }
            }
            java.util.Set<UUID> tracked = mine.get(attr);
            if (tracked == null) continue;
            for (UUID id : before) {
                if (!now.contains(id) && tracked.remove(id)) puffInst.removeModifier(id);
            }
            if (tracked.isEmpty()) mine.remove(attr);
        }
        if (puff.getHealth() > puff.getMaxHealth()) puff.setHealth(puff.getMaxHealth());
    }

    /**
     * Hands Puff whatever happened to the proxy since the last sync - e.g. "heal the attacker on kill" curios
     * fire during the victim's death, between Puff's ticks, and would otherwise be overwritten next tick.
     */
    public void flushTo(PuffEntity puff) {
        if (this.synced) this.syncBackTo(puff, this.baseHealth);
    }

    /**
     * Passes back what accessory code did to the proxy: fire put out, air refilled, healing, absorption and
     * effects added or removed (e.g. a charm curing poison or granting fire resistance).
     */
    public void syncBackTo(PuffEntity puff, float healthBefore) {
        this.syncModifiersBack(puff);
        if (this.getRemainingFireTicks() < puff.getRemainingFireTicks()) puff.setRemainingFireTicks(this.getRemainingFireTicks());
        if (this.getAirSupply() > puff.getAirSupply()) puff.setAirSupply(this.getAirSupply());
        float delta = this.getHealth() - healthBefore;
        if (delta > 1.0E-4F) puff.heal(delta);
        else if (delta < -1.0E-4F) puff.setHealth(Math.max(1.0F, puff.getHealth() + delta));
        if (this.getAbsorptionAmount() > puff.getAbsorptionAmount()) puff.setAbsorptionAmount(this.getAbsorptionAmount());
        for (MobEffectInstance effect : new ArrayList<>(this.getActiveEffects())) {
            MobEffectInstance mine = puff.getEffect(effect.getEffect());
            if (mine == null || mine.getAmplifier() != effect.getAmplifier() || mine.getDuration() < effect.getDuration()) {
                puff.forceAddEffect(new MobEffectInstance(effect), null);
            }
        }
        // Only undo effects the proxy had at the last sync and lost since (cured by an accessory) -
        // never effects Puff gained on her own in the meantime.
        for (net.minecraft.world.effect.MobEffect effect : new ArrayList<>(this.baseEffects)) {
            if (!this.hasEffect(effect) && puff.hasEffect(effect)) puff.removeEffect(effect);
        }
        this.markBaseline();
    }

    /**
     * Items that ended up in the proxy's own inventory (a mod "gave the player" an item, Curios returned an
     * accessory from a removed slot...) go to Puff's secret nest, or drop at her feet - never lost, never
     * duplicated: stacks Puff still owns ({@code owned}, compared by identity / equality) are just discarded.
     */
    private static final int FIRST_JOIN_TICKS = 40;

    public void reclaimInventory(PuffEntity puff, java.util.List<ItemStack> owned) {
        net.minecraft.core.NonNullList<ItemStack> items = this.getInventory().items;
        int selected = this.getInventory().selected;
        java.util.List<ItemStack> pool = new ArrayList<>(owned);
        for (int i = 0; i < items.size(); i++) {
            if (i == selected) continue;   // main hand mirror
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            items.set(i, ItemStack.EMPTY);
            if (this.ticksAlive <= FIRST_JOIN_TICKS) {
                // "welcome" items other mods hand every new player (guide books, starter kits): not Puff's
                com.puffcompanion.PuffCompanion.LOGGER.debug("[Puff Companion] proxy starter item ignored: {}", s);
                continue;
            }
            boolean dup = false;
            for (java.util.Iterator<ItemStack> it = pool.iterator(); it.hasNext(); ) {
                ItemStack o = it.next();
                if (o == s || ItemStack.matches(o, s)) {
                    it.remove();
                    dup = true;
                    break;
                }
            }
            if (!dup) puff.stashOrDrop(s);
        }
    }

    /** Copies any effects the proxy picked up (e.g. from a curio tick) onto Puff, then clears the proxy. */
    public void transferEffectsTo(PuffEntity puff) {
        if (this.getActiveEffects().isEmpty()) return;
        for (MobEffectInstance effect : new ArrayList<>(this.getActiveEffects())) {
            puff.addEffect(new MobEffectInstance(effect));
        }
        this.removeAllEffects();
    }
}
