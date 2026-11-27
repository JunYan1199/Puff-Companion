package com.puffcompanion.compat.curios;

import com.google.common.collect.Multimap;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.CuriosCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffFakePlayer;
import com.puffcompanion.entity.PuffCurioStore;
import com.puffcompanion.entity.PuffProxyBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import top.theillusivec4.curios.api.SlotAttribute;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.ISlotType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Only loaded when Curios is installed. API verified with javap against curios-forge 5.9.1+1.20.1:
 * {@code CuriosApi.getItemStackSlots(ItemStack, boolean)}, {@code getAttributeModifiers(SlotContext, UUID, ItemStack)},
 * {@code getSlotUuid(SlotContext)}, {@code getCurio(ItemStack)} and {@code ICurio#curioTick(SlotContext)};
 * {@code SlotContext(String, LivingEntity, int, boolean, boolean)}.
 */
public final class CuriosImpl {
    private static final int MODIFIER_REFRESH = 20;
    /** Ticks a slot type may stay smaller than her store before the extra accessories are handed back. */
    private static final int SHRINK_GRACE = 40;
    private static final String BONUS_NAME = "puffcompanion:curio_slot_expander";

    private CuriosImpl() {}

    // ---------------------------------------------------------------- slot layout

    /**
     * Puff's Curios slots = exactly the player's: every slot type and its size come from her proxy player's
     * real Curios inventory, i.e. the player base size (datapack / config), plus slot modifiers of what she
     * wears (accessories that add slots, onEquip slot unlocks...), plus her star-expander bonus. Types with 0
     * slots are included (for the expander list). Order = Curios slot order.
     */
    public static List<CuriosCompat.SlotGroup> slotSizes(PuffEntity puff) {
        Map<String, Integer> sizes = new HashMap<>();
        PuffFakePlayer proxy = puff.getFakePlayer();
        ICuriosItemHandler inv = proxy == null ? null : inventory(proxy);
        if (inv != null) {
            proxy.flushTo(puff);
            proxy.syncFrom(puff);
            mirror(puff, proxy, inv);
            inv.getCurios().forEach((type, handler) -> {
                if (handler.isVisible() || handler.getSlots() > 0) sizes.put(type, handler.getSlots());
            });
        }
        try {
            // Completeness / fallback without a proxy: the player's registered slot types (base size + bonus)
            CuriosApi.getPlayerSlots(puff.level()).forEach((type, slot) -> {
                if (!sizes.containsKey(type) && (slot.isVisible() || slot.getSize() > 0 || puff.getCurioSlotBonus(type) > 0)) {
                    sizes.put(type, slot.getSize() + puff.getCurioSlotBonus(type));
                }
            });
        } catch (Throwable ignored) {
        }
        // Never hide any slot type that already holds equipped items (e.g. spellstone / hidden-by-default slots)
        puff.getCurioStore().all().forEach((type, list) -> {
            int lastUsed = -1;
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).isEmpty()) lastUsed = i;
            }
            if (lastUsed >= 0) sizes.merge(type, lastUsed + 1, Math::max);
        });
        List<String> order = new ArrayList<>(sizes.keySet());
        order.sort(java.util.Comparator
                .comparing((String t) -> !hasEquippedInType(puff, t))
                .thenComparingInt(t -> CuriosApi.getSlot(t, false).map(ISlotType::getOrder).orElse(1000))
                .thenComparing(t -> t));
        List<CuriosCompat.SlotGroup> out = new ArrayList<>();
        for (String t : order) out.add(new CuriosCompat.SlotGroup(t, sizes.get(t)));
        return out;
    }

    private static boolean hasEquippedInType(PuffEntity puff, String type) {
        List<ItemStack> list = puff.getCurioStore().all().get(type);
        if (list == null) return false;
        for (ItemStack s : list) if (!s.isEmpty()) return true;
        return false;
    }

    /** Slot types Puff currently has at least one slot of (maid panel layout). */
    public static List<CuriosCompat.SlotGroup> layoutFor(PuffEntity puff) {
        List<CuriosCompat.SlotGroup> out = new ArrayList<>();
        for (CuriosCompat.SlotGroup g : slotSizes(puff)) if (g.count() > 0) out.add(g);
        return out;
    }

    /** Is {@code type} a Curios slot type players have (the expander may only target those)? */
    public static boolean isPlayerSlotType(PuffEntity puff, String type) {
        try {
            return CuriosApi.getPlayerSlots(puff.level()).containsKey(type);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Expander bonus changed: re-apply it to the proxy right away. */
    public static void refreshBonus(PuffEntity puff) {
        PuffFakePlayer proxy = puff.getFakePlayer();
        ICuriosItemHandler inv = proxy == null ? null : inventory(proxy);
        if (inv != null) prepare(puff, proxy, inv);
    }

    public static boolean isValid(String slotType, ItemStack stack, boolean clientSide) {
        if (stack.isEmpty()) return false;
        try {
            return CuriosApi.getItemStackSlots(stack, clientSide).containsKey(slotType);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isValidFor(String slotType, int index, ItemStack stack, @Nullable LivingEntity wearer, boolean clientSide) {
        if (stack.isEmpty()) return false;
        // NOTE: a SlotContext *with* the wearer makes Curios filter by the slots registered for that entity type,
        // and Puff has none registered (she keeps her own store), which rejected every accessory. Validate
        // against the global slot types instead - same validators the player's slot of that type uses.
        try {
            return CuriosApi.isStackValid(new SlotContext(slotType, null, index, false, true), stack);
        } catch (Throwable t) {
            return isValid(slotType, stack, clientSide);
        }
    }

    /**
     * Same rule as the player's Curios slot: an accessory may refuse to come off (curses, "nightmare" items...).
     * Asked with Puff's proxy as the wearer, since that is who the item code knows.
     */
    public static boolean canUnequip(PuffEntity puff, String type, int index, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (net.minecraft.world.item.enchantment.EnchantmentHelper.hasBindingCurse(stack)) return false;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return true;
        try {
            syncInv(puff, proxy);
            SlotContext ctx = new SlotContext(type, proxy, index, false, true);
            return CuriosApi.getCurio(stack).map(c -> c.canUnequip(ctx)).orElse(true);
        } catch (Throwable t) {
            return true;
        }
    }

    @Nullable
    public static ResourceLocation slotIcon(String slotType) {
        try {
            return CuriosApi.getSlot(slotType, true).map(ISlotType::getIcon).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- tick

    /** Ticks accessories + armor through Puff's proxy player and keeps her attribute modifiers in sync. */
    public static void tick(PuffEntity puff) {
        PuffEntity prev = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            tickInner(puff);
        } finally {
            CuriosCompat.ACTING.set(prev);
        }
    }

    private static void tickInner(PuffEntity puff) {
        boolean refresh = puff.tickCount % MODIFIER_REFRESH == 0;
        boolean active = hasAnyCurio(puff) || PuffProxyBridge.hasArmor(puff);
        if (active) puff.proxyLinger = 40;
        else if (puff.proxyLinger > 0 || !puff.getProxyAttributeModifiers().isEmpty()) {
            active = puff.proxyLinger-- > 0;
        }
        PuffFakePlayer proxy = active ? puff.getFakePlayer() : null;
        if (proxy != null) {
            try {
                proxy.flushTo(puff);
                proxy.syncFrom(puff);
                float healthBefore = proxy.getHealth();
                ICuriosItemHandler inv = inventory(proxy);
                if (inv != null) mirror(puff, proxy, inv);
                // Curios ticks the proxy's accessories from LivingTickEvent: curioTick, onEquip / onUnequip,
                // attribute + slot modifiers (slot-adding accessories), invalid-stack handling.
                PuffProxyBridge.postPlayerTick(proxy);
                CuriosCompat.ACTING.set(puff);
                PuffProxyBridge.tickArmor(proxy);
                List<ItemStack> owned = new ArrayList<>();
                if (inv != null) {
                    pull(puff, proxy, inv);
                    resize(puff, inv, owned);
                    if (refresh) saveState(puff, inv);
                }
                proxy.reclaimInventory(puff, owned);
                proxy.syncBackTo(puff, healthBefore);
            } catch (Throwable t) {
                PuffCompanion.LOGGER.debug("[Puff Companion] proxy tick failed: {}", t.toString());
            }
        }
        if (refresh) {
            refreshModifiers(puff);
            tickCursedScrollRegen(puff);
        }
    }

    private static final UUID CURSED_SCROLL_DAMAGE_UUID =
            UUID.nameUUIDFromBytes("puffcompanion:cursed_scroll_damage".getBytes(StandardCharsets.UTF_8));

    public static boolean hasItemEquipped(LivingEntity entity, String modid, String path) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(modid, path));
        if (item == null) return false;
        if (entity instanceof PuffEntity p) {
            for (List<ItemStack> list : p.getCurioStore().all().values()) {
                for (ItemStack s : list) if (!s.isEmpty() && s.is(item)) return true;
            }
            return false;
        }
        try {
            return CuriosApi.getCuriosInventory(entity).map(inv -> !inv.findCurios(item).isEmpty()).orElse(false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int countCurseEnchantments(PuffEntity puff) {
        int curses = 0;
        for (ItemStack s : puff.getAllSlots()) {
            if (s.isEmpty()) continue;
            for (var e : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(s).keySet()) {
                if (e.isCurse()) curses++;
            }
        }
        for (List<ItemStack> list : puff.getCurioStore().all().values()) {
            for (ItemStack s : list) {
                if (s.isEmpty()) continue;
                for (var e : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(s).keySet()) {
                    if (e.isCurse()) curses++;
                }
            }
        }
        return curses;
    }

    /**
     * Enigmatic Legacy's Scroll of a Thousand Curses requires the wearer to bear the Ring of the Seven Curses.
     * When Puff wears the Scroll while her owner (or Puff herself) bears the Seven Curses, share the pact:
     * apply the +4% damage per curse modifier (7 base curses + Puff's cursed gear) and passive regen boost.
     */
    private static int sharedCurseCount(PuffEntity puff) {
        if (!hasItemEquipped(puff, "enigmaticlegacy", "cursed_scroll")) return 0;
        boolean pact = hasItemEquipped(puff, "enigmaticlegacy", "cursed_ring")
                || (puff.getOwner() != null && hasItemEquipped(puff.getOwner(), "enigmaticlegacy", "cursed_ring"));
        if (!pact) return 0;
        return 7 + countCurseEnchantments(puff);
    }

    private static void tickCursedScrollRegen(PuffEntity puff) {
        int curses = sharedCurseCount(puff);
        if (curses <= 0 || puff.getHealth() >= puff.getMaxHealth()) return;
        // 4% regeneration boost per curse, gentle baseline tick every 20 ticks
        puff.heal(Math.min(1.5F, 0.05F * curses));
    }

    /** Accessory attribute modifiers (non-slot attributes) onto Puff herself; remembers each modifier's item. */
    private static void refreshModifiers(PuffEntity puff) {
        Map<Attribute, Map<UUID, AttributeModifier>> wanted = new HashMap<>();
        puff.getCurioModifierSources().clear();
        PuffFakePlayer proxy = puff.getFakePlayer();
        LivingEntity wearer = puff;
        if (proxy != null) {
            syncInv(puff, proxy);
            wearer = proxy;
        }
        final LivingEntity slotWearer = wearer;
        puff.getCurioStore().all().forEach((type, list) -> {
            for (int i = 0; i < list.size(); i++) {
                ItemStack stack = list.get(i);
                if (stack.isEmpty()) continue;
                try {
                    SlotContext ctx = new SlotContext(type, slotWearer, i, false, true);
                    Multimap<Attribute, AttributeModifier> mods = CuriosApi.getAttributeModifiers(ctx, CuriosApi.getSlotUuid(ctx), stack);
                    mods.forEach((attr, mod) -> {
                        if (attr instanceof SlotAttribute) return;   // slot counts live in the proxy inventory
                        wanted.computeIfAbsent(attr, a -> new HashMap<>()).put(mod.getId(), mod);
                        puff.getCurioModifierSources().put(mod.getId(), stack);
                    });
                } catch (Throwable t) {
                    PuffCompanion.LOGGER.debug("[Puff Companion] modifiers of {} failed: {}",
                            ForgeRegistries.ITEMS.getKey(stack.getItem()), t.toString());
                }
            }
        });
        int sharedCurses = sharedCurseCount(puff);
        if (sharedCurses > 0) {
            wanted.computeIfAbsent(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, a -> new HashMap<>())
                    .put(CURSED_SCROLL_DAMAGE_UUID, new AttributeModifier(CURSED_SCROLL_DAMAGE_UUID,
                            "puffcompanion:cursed_scroll_damage", 0.04 * sharedCurses,
                            AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        applyModifiers(puff, wanted);
    }

    // ---------------------------------------------------------------- proxy inventory sync

    @Nullable
    private static ICuriosItemHandler inventory(PuffFakePlayer proxy) {
        try {
            return CuriosApi.getCuriosInventory(proxy).resolve().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static UUID bonusUuid(String type) {
        return UUID.nameUUIDFromBytes(("puffcompanion:slot_bonus:" + type).getBytes(StandardCharsets.UTF_8));
    }

    /** First use of a proxy: restore persisted permanent slot modifiers; always: keep the expander bonus applied. */
    private static void prepare(PuffEntity puff, PuffFakePlayer proxy, ICuriosItemHandler inv) {
        if (!proxy.curiosPrepared) {
            proxy.curiosPrepared = true;
            restoreState(puff, inv);
        }
        Set<String> types = new HashSet<>(proxy.appliedBonus.keySet());
        types.addAll(puff.getCurioSlotBonus().keySet());
        for (String type : types) {
            int want = puff.getCurioSlotBonus(type);
            if (proxy.appliedBonus.getOrDefault(type, 0) == want) continue;
            try {
                ICurioStacksHandler handler = handlerFor(inv, type, want > 0);
                if (handler == null) continue;
                handler.removeModifier(bonusUuid(type));
                if (want > 0) {
                    handler.addTransientModifier(new AttributeModifier(bonusUuid(type), BONUS_NAME, want, AttributeModifier.Operation.ADDITION));
                }
                handler.update();
                proxy.appliedBonus.put(type, want);
            } catch (Throwable t) {
                PuffCompanion.LOGGER.debug("[Puff Companion] slot bonus {} failed: {}", type, t.toString());
            }
        }
    }

    /** The proxy's handler for a slot type; unlocks a locked (0-size) player slot type when {@code unlock}. */
    @Nullable
    private static ICurioStacksHandler handlerFor(ICuriosItemHandler inv, String type, boolean unlock) {
        Optional<ICurioStacksHandler> h = inv.getStacksHandler(type);
        if (h.isEmpty() && unlock) {
            try {
                inv.unlockSlotType(type, 0, true, false);
            } catch (Throwable ignored) {
            }
            h = inv.getStacksHandler(type);
        }
        return h.orElse(null);
    }

    /** Persists the permanent slot modifiers accessories put on the proxy (they would vanish with it otherwise). */
    private static void saveState(PuffEntity puff, ICuriosItemHandler inv) {
        CompoundTag tag = new CompoundTag();
        inv.getCurios().forEach((type, handler) -> {
            ListTag mods = new ListTag();
            for (AttributeModifier m : handler.getPermanentModifiers()) {
                if (m.getId().equals(bonusUuid(type))) continue;
                CompoundTag t = new CompoundTag();
                t.putUUID("Id", m.getId());
                t.putString("Name", m.getName());
                t.putDouble("Amount", m.getAmount());
                t.putInt("Op", m.getOperation().toValue());
                mods.add(t);
            }
            if (!mods.isEmpty()) tag.put(type, mods);
        });
        puff.setCurioSlotState(tag);
    }

    private static void restoreState(PuffEntity puff, ICuriosItemHandler inv) {
        CompoundTag tag = puff.getCurioSlotState();
        for (String type : tag.getAllKeys()) {
            try {
                ICurioStacksHandler handler = handlerFor(inv, type, true);
                if (handler == null) continue;
                ListTag mods = tag.getList(type, Tag.TAG_COMPOUND);
                for (int i = 0; i < mods.size(); i++) {
                    CompoundTag t = mods.getCompound(i);
                    UUID id = t.getUUID("Id");
                    if (handler.getModifiers().containsKey(id)) continue;
                    handler.addPermanentModifier(new AttributeModifier(id, t.getString("Name"), t.getDouble("Amount"),
                            AttributeModifier.Operation.fromValue(t.getInt("Op"))));
                }
                handler.update();
            } catch (Throwable t) {
                PuffCompanion.LOGGER.debug("[Puff Companion] restoring slot modifiers of {} failed: {}", type, t.toString());
            }
        }
    }

    /**
     * Puff's store -> the proxy's real Curios slots (same ItemStack instances, so NBT written by curio code
     * lands in Puff's store). Mods asking "is this player wearing X?" then see her accessories. Changes mods
     * made to the proxy's slots since the last write are taken over first ({@link #pull}).
     */
    private static void mirror(PuffEntity puff, PuffFakePlayer proxy, ICuriosItemHandler inv) {
        try {
            prepare(puff, proxy, inv);
            pull(puff, proxy, inv);
            PuffCurioStore store = puff.getCurioStore();
            inv.getCurios().forEach((type, handler) -> {
                int n = handler.getSlots();
                List<ItemStack> list = store.all().get(type);
                ItemStack[] record = new ItemStack[n];
                for (int i = 0; i < n; i++) {
                    ItemStack want = list != null && i < list.size() ? list.get(i) : ItemStack.EMPTY;
                    if (handler.getStacks().getStackInSlot(i) != want) handler.getStacks().setStackInSlot(i, want);
                    record[i] = want;
                }
                proxy.mirrored.put(type, record);
            });
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] curio mirror failed: {}", t.toString());
        }
    }

    /**
     * Proxy -> Puff: accessory code that changed the proxy's slots (onEquip putting a bound / cursed accessory
     * into a slot it just unlocked, an accessory replacing or removing itself...) is taken over into her store.
     * Puff's own changes (maid panel) win when both sides changed the same slot.
     */
    private static void pull(PuffEntity puff, PuffFakePlayer proxy, ICuriosItemHandler inv) {
        PuffCurioStore store = puff.getCurioStore();
        inv.getCurios().forEach((type, handler) -> {
            ItemStack[] last = proxy.mirrored.get(type);
            if (last == null) return;
            int n = handler.getSlots();
            for (int i = 0; i < n; i++) {
                ItemStack cur = handler.getStacks().getStackInSlot(i);
                ItemStack prev = i < last.length ? last[i] : ItemStack.EMPTY;
                if (cur == prev) continue;
                ItemStack mine = store.get(type, i);
                if (i < last.length && mine != prev) continue;   // changed on Puff's side meanwhile: hers wins
                if (mine.isEmpty() || mine == prev) {
                    store.set(type, i, cur);
                } else if (!cur.isEmpty()) {
                    // new slot that her store already fills (old layout): keep the accessory safe in the nest
                    handler.getStacks().setStackInSlot(i, mine);
                    puff.stashOrDrop(cur);
                }
            }
            proxy.mirrored.remove(type);   // re-recorded by the next mirror
        });
    }

    /**
     * Store size per type follows the proxy's slot counts. More slots: grows at once. Fewer slots (accessory
     * removed, bonus lowered, old save from the previous layout rules): after a short grace the accessories in
     * the removed slots go to the nest (or drop at her feet). {@code owned} collects the stacks still owned by
     * the store's overflow, so the copies Curios hands the proxy as "invalid stacks" are never duplicated.
     */
    private static void resize(PuffEntity puff, ICuriosItemHandler inv, List<ItemStack> owned) {
        PuffCurioStore store = puff.getCurioStore();
        Map<String, Integer> sizes = new HashMap<>();
        inv.getCurios().forEach((type, handler) -> sizes.put(type, handler.getSlots()));
        Map<String, Integer> timers = puff.getCurioShrinkTicks();
        for (String type : new ArrayList<>(store.all().keySet())) {
            int size = sizes.getOrDefault(type, 0);
            List<ItemStack> overflow = store.overflow(type, size);
            if (overflow.isEmpty()) {
                store.shrinkTo(type, size);
                timers.remove(type);
                continue;
            }
            owned.addAll(overflow);
            int t = timers.merge(type, 1, Integer::sum);
            if (t < SHRINK_GRACE) continue;
            timers.remove(type);
            for (ItemStack s : store.shrinkTo(type, size)) puff.stashOrDrop(s);
            if (puff.getOwner() instanceof net.minecraft.world.entity.player.Player owner) {
                owner.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.puffcompanion.curio_slot_returned", overflow.size(),
                        net.minecraft.network.chat.Component.translatable("curios.identifier." + type)), false);
            }
        }
        sizes.forEach((type, size) -> {
            if (size > 0) store.ensureSize(type, size);
        });
    }

    /**
     * Incoming-damage bridge: most accessory effects (Enigmatic Legacy etc.) live in Forge damage events and only
     * act on players wearing the item. Replays Puff's hit as LivingAttackEvent + LivingHurtEvent on her proxy
     * player (who wears the same accessories) and returns the adjusted amount, or -1 if the hit was cancelled.
     * Healing done to the proxy and potion effects given to it are passed back to Puff.
     */
    public static float bridgeIncomingDamage(PuffEntity puff, DamageSource source, float amount) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return amount;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return amount;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            float before = proxy.getHealth();
            float out = amount;
            if (MinecraftForge.EVENT_BUS.post(new LivingAttackEvent(proxy, source, amount))) {
                out = -1;
            } else {
                LivingHurtEvent hurt = new LivingHurtEvent(proxy, source, amount);
                out = MinecraftForge.EVENT_BUS.post(hurt) ? -1 : hurt.getAmount();
            }
            proxy.syncBackTo(puff, before);
            return out;
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] damage bridge failed: {}", t.toString());
            return amount;
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /**
     * Outgoing-damage source while Puff wears accessories: a plain player melee hit by her proxy (direct AND
     * causing entity = proxy). Accessory code checks "attacker is a player wearing X" and very often also
     * "direct entity == that player" for melee bonuses (e.g. Enigmatic Legacy's Scroll of a Thousand Curses),
     * so Puff must not be the direct entity here. The proxy stands at Puff's position and shows Puff's name.
     */
    @Nullable
    public static DamageSource attackSource(PuffEntity puff) {
        if (!hasAnyCurio(puff)) return null;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return null;
        proxy.syncFrom(puff);
        syncInv(puff, proxy);
        return puff.damageSources().playerAttack(proxy);
    }

    /** Post-armor damage (LivingDamageEvent) replayed on the proxy: accessory damage reduction / reactions. */
    public static void bridgeDamage(PuffEntity puff, LivingDamageEvent event) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            float before = proxy.getHealth();
            LivingDamageEvent replay = new LivingDamageEvent(proxy, event.getSource(), event.getAmount());
            if (MinecraftForge.EVENT_BUS.post(replay)) event.setCanceled(true);
            else event.setAmount(replay.getAmount());
            proxy.syncBackTo(puff, before);
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] damage(post-armor) bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /**
     * Wearer death: replays LivingDeathEvent on the proxy (totem / revive accessories). Item and XP spawns are
     * blocked during the replay (some mods drop things when a player dies). Returns true if Puff is saved.
     */
    public static boolean bridgeDeath(PuffEntity puff, DamageSource source) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return false;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return false;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        CuriosCompat.BLOCK_DROPS.set(true);
        try {
            proxy.flushTo(puff);
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            if (!MinecraftForge.EVENT_BUS.post(new LivingDeathEvent(proxy, source))) return false;
            puff.setHealth(Math.max(1.0F, Math.min(puff.getMaxHealth(), proxy.getHealth())));
            proxy.syncBackTo(puff, proxy.getHealth());
            return true;
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] death bridge failed: {}", t.toString());
            return false;
        } finally {
            CuriosCompat.BLOCK_DROPS.set(false);
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /**
     * Before Puff's melee hit: AttackEntityEvent + CriticalHitEvent as her proxy player (on-attack and crit
     * accessories). Returns the damage multiplier, or -1 if an accessory cancelled the attack.
     */
    public static float bridgeMeleePre(PuffEntity puff, net.minecraft.world.entity.Entity target) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return 1F;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return 1F;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            float before = proxy.getHealth();
            if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(proxy, target))) return -1F;
            boolean vanillaCrit = puff.fallDistance > 0.0F && !puff.onGround() && !puff.isInWater();
            CriticalHitEvent crit = new CriticalHitEvent(proxy, target, 1.5F, vanillaCrit);
            MinecraftForge.EVENT_BUS.post(crit);
            proxy.syncBackTo(puff, before);
            boolean isCrit = crit.getResult() == net.minecraftforge.eventbus.api.Event.Result.ALLOW
                    || (vanillaCrit && crit.getResult() == net.minecraftforge.eventbus.api.Event.Result.DEFAULT);
            return isCrit ? crit.getDamageModifier() : 1F;
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] melee bridge failed: {}", t.toString());
            return 1F;
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** A mob picks Puff as its target: replay with the proxy (stealth / "mobs ignore the wearer" accessories). */
    public static void bridgeChangeTarget(PuffEntity puff, LivingChangeTargetEvent event) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            LivingChangeTargetEvent replay = new LivingChangeTargetEvent(event.getEntity(), proxy, event.getTargetType());
            if (MinecraftForge.EVENT_BUS.post(replay)) {
                event.setCanceled(true);
            } else if (replay.getNewTarget() != proxy) {
                event.setNewTarget(replay.getNewTarget());
            }
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] target bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** Puff is about to get an effect: replay Applicable on the proxy (effect-immunity accessories). */
    public static void bridgeEffectApplicable(PuffEntity puff, MobEffectEvent.Applicable event) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            syncInv(puff, proxy);
            MobEffectEvent.Applicable replay = new MobEffectEvent.Applicable(proxy, event.getEffectInstance());
            MinecraftForge.EVENT_BUS.post(replay);
            if (replay.getResult() == net.minecraftforge.eventbus.api.Event.Result.DENY) {
                event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
            }
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] effect bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** Knockback on Puff replayed on the proxy (knockback-resistance accessories). */
    public static void bridgeKnockback(PuffEntity puff, LivingKnockBackEvent event) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            syncInv(puff, proxy);
            LivingKnockBackEvent replay = new LivingKnockBackEvent(proxy, event.getStrength(), event.getRatioX(), event.getRatioZ());
            if (MinecraftForge.EVENT_BUS.post(replay)) {
                event.setCanceled(true);
            } else {
                event.setStrength(replay.getStrength());
                event.setRatioX(replay.getRatioX());
                event.setRatioZ(replay.getRatioZ());
            }
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] knockback bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** Healing replayed on the proxy (regen boosts such as the Scroll of a Thousand Curses). */
    public static void bridgeHeal(PuffEntity puff, net.minecraftforge.event.entity.living.LivingHealEvent event) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            net.minecraftforge.event.entity.living.LivingHealEvent replay =
                    new net.minecraftforge.event.entity.living.LivingHealEvent(proxy, event.getAmount());
            if (MinecraftForge.EVENT_BUS.post(replay)) event.setCanceled(true);
            else event.setAmount(replay.getAmount());
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] heal bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** Fall damage replayed on the proxy (feather-falling / no-fall accessories). */
    public static void bridgeFall(PuffEntity puff, LivingFallEvent event) {
        if (BRIDGING.get() || !hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy == null) return;
        BRIDGING.set(true);
        PuffEntity prevActing = CuriosCompat.ACTING.get();
        CuriosCompat.ACTING.set(puff);
        try {
            proxy.syncFrom(puff);
            syncInv(puff, proxy);
            LivingFallEvent replay = new LivingFallEvent(proxy, event.getDistance(), event.getDamageMultiplier());
            if (MinecraftForge.EVENT_BUS.post(replay)) {
                event.setCanceled(true);
            } else {
                event.setDistance(replay.getDistance());
                event.setDamageMultiplier(replay.getDamageMultiplier());
            }
        } catch (Throwable t) {
            PuffCompanion.LOGGER.debug("[Puff Companion] fall bridge failed: {}", t.toString());
        } finally {
            BRIDGING.set(false);
            CuriosCompat.ACTING.set(prevActing);
        }
    }

    /** Flushes any health/effect/modifier changes recorded on Puff's proxy back onto Puff immediately. */
    public static void flushProxy(PuffEntity puff) {
        if (!hasAnyCurio(puff)) return;
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy != null) proxy.flushTo(puff);
    }

    /** The proxy for Puff, synced (test helper). */
    public static PuffFakePlayer proxyOf(PuffEntity puff) {
        PuffFakePlayer proxy = puff.getFakePlayer();
        if (proxy != null) proxy.syncFrom(puff);
        return proxy;
    }

    private static PuffEntity puffOf(PuffFakePlayer proxy) {
        return proxy.getPuff();
    }

    private static final ThreadLocal<Boolean> BRIDGING = PuffProxyBridge.BRIDGING;

    private static void syncInv(PuffEntity puff, PuffFakePlayer proxy) {
        ICuriosItemHandler inv = inventory(proxy);
        if (inv != null) mirror(puff, proxy, inv);
    }

    private static boolean hasAnyCurio(PuffEntity puff) {
        for (List<ItemStack> list : puff.getCurioStore().all().values()) {
            for (ItemStack s : list) if (!s.isEmpty()) return true;
        }
        return false;
    }

    /** Replaces the curio modifiers applied last time with the current set. */
    private static void applyModifiers(PuffEntity puff, Map<Attribute, Map<UUID, AttributeModifier>> wanted) {
        Map<Attribute, Set<UUID>> applied = puff.getAppliedCurioModifiers();
        applied.forEach((attr, ids) -> {
            AttributeInstance inst = puff.getAttribute(attr);
            if (inst != null) ids.forEach(inst::removeModifier);
        });
        applied.clear();
        wanted.forEach((attr, mods) -> {
            AttributeInstance inst = puff.getAttribute(attr);
            if (inst == null) return;   // attribute not present on Puff (e.g. player-only)
            Set<UUID> ids = new HashSet<>();
            for (AttributeModifier mod : mods.values()) {
                if (inst.getModifier(mod.getId()) == null) inst.addTransientModifier(mod);
                ids.add(mod.getId());
            }
            applied.put(attr, ids);
        });
        if (puff.getHealth() > puff.getMaxHealth()) puff.setHealth(puff.getMaxHealth());
    }
}
