package com.puffcompanion.network;

import com.puffcompanion.compat.AttributesCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffProgression;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> client (while the maid panel is open): Puff's live attribute values (vanilla + attributeslib,
 * including equipment / curios / growth modifiers) and her training data. Many attributes such as attack
 * damage are not client-synced by vanilla, so the stats page reads this snapshot instead.
 */
public record PuffStatsPacket(int entityId, Map<ResourceLocation, double[]> attributes, int level, int levelCap,
                              float[] genes, double[] growthNow, double[] growthCap, double regen,
                              List<AttrSnap> full) {

    /** One modifier on one of Puff's attributes; {@code source} is the item that granted it (or empty). */
    public record ModSnap(UUID id, String name, double amount, int op, ItemStack source) {}

    /** Every attribute Puff has: base value + all modifiers (the ApothicAttributes-style panel rebuilds these). */
    public record AttrSnap(ResourceLocation id, double base, List<ModSnap> mods) {}

    private static final List<Attribute> VANILLA = List.of(Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE,
            Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.MOVEMENT_SPEED, Attributes.KNOCKBACK_RESISTANCE,
            Attributes.ATTACK_KNOCKBACK);

    public static PuffStatsPacket of(PuffEntity puff) {
        Map<ResourceLocation, double[]> attrs = new LinkedHashMap<>();
        List<Attribute> all = new ArrayList<>(VANILLA);
        for (ResourceLocation id : AttributesCompat.attributeIds()) {
            Attribute a = ForgeRegistries.ATTRIBUTES.getValue(id);
            if (a != null) all.add(a);
        }
        for (Attribute a : all) {
            AttributeInstance inst = puff.getAttribute(a);
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(a);
            if (inst != null && id != null) attrs.put(id, new double[]{inst.getValue(), inst.getBaseValue()});
        }
        PuffProgression p = puff.getProgression();
        float[] genes = new float[PuffProgression.Gene.values().length];
        for (PuffProgression.Gene g : PuffProgression.Gene.values()) genes[g.ordinal()] = p.gene(g);
        int lvl = p.level(), cap = p.levelCap();
        return new PuffStatsPacket(puff.getId(), attrs, lvl, cap, genes,
                new double[]{p.healthBonus(lvl), p.damageBonus(lvl), p.armorBonus(lvl), p.speedBonus(lvl)},
                new double[]{p.healthBonus(cap), p.damageBonus(cap), p.armorBonus(cap), p.speedBonus(cap)},
                p.regenPerSecond(), fullSnapshot(puff));
    }

    private static List<AttrSnap> fullSnapshot(PuffEntity puff) {
        // Which equipped item granted which modifier (equipment + curios), so the panel can show the source icon.
        Map<UUID, ItemStack> sources = new HashMap<>(puff.getCurioModifierSources());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = puff.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            stack.getAttributeModifiers(slot).values().forEach(m -> sources.putIfAbsent(m.getId(), stack));
        }
        List<AttrSnap> out = new ArrayList<>();
        for (Attribute a : ForgeRegistries.ATTRIBUTES.getValues()) {
            if (!puff.getAttributes().hasAttribute(a)) continue;
            AttributeInstance inst = puff.getAttribute(a);
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(a);
            if (inst == null || id == null) continue;
            List<ModSnap> mods = new ArrayList<>();
            for (AttributeModifier m : inst.getModifiers()) {
                ItemStack src = sources.getOrDefault(m.getId(), ItemStack.EMPTY);
                mods.add(new ModSnap(m.getId(), m.getName(), m.getAmount(), m.getOperation().ordinal(),
                        src.isEmpty() ? ItemStack.EMPTY : src.copyWithCount(1)));
            }
            out.add(new AttrSnap(id, inst.getBaseValue(), mods));
        }
        return out;
    }

    public static void encode(PuffStatsPacket m, FriendlyByteBuf buf) {
        buf.writeVarInt(m.entityId);
        buf.writeVarInt(m.attributes.size());
        m.attributes.forEach((id, v) -> {
            buf.writeResourceLocation(id);
            buf.writeDouble(v[0]);
            buf.writeDouble(v[1]);
        });
        buf.writeVarInt(m.level);
        buf.writeVarInt(m.levelCap);
        buf.writeVarInt(m.genes.length);
        for (float g : m.genes) buf.writeFloat(g);
        for (double d : m.growthNow) buf.writeDouble(d);
        for (double d : m.growthCap) buf.writeDouble(d);
        buf.writeDouble(m.regen);
        buf.writeVarInt(m.full.size());
        for (AttrSnap a : m.full) {
            buf.writeResourceLocation(a.id());
            buf.writeDouble(a.base());
            buf.writeVarInt(a.mods().size());
            for (ModSnap mod : a.mods()) {
                buf.writeUUID(mod.id());
                buf.writeUtf(mod.name(), 256);
                buf.writeDouble(mod.amount());
                buf.writeByte(mod.op());
                buf.writeItem(mod.source());
            }
        }
    }

    public static PuffStatsPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int n = buf.readVarInt();
        Map<ResourceLocation, double[]> attrs = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) attrs.put(buf.readResourceLocation(), new double[]{buf.readDouble(), buf.readDouble()});
        int level = buf.readVarInt(), cap = buf.readVarInt();
        float[] genes = new float[buf.readVarInt()];
        for (int i = 0; i < genes.length; i++) genes[i] = buf.readFloat();
        double[] now = new double[4], capB = new double[4];
        for (int i = 0; i < 4; i++) now[i] = buf.readDouble();
        for (int i = 0; i < 4; i++) capB[i] = buf.readDouble();
        double regen = buf.readDouble();
        int fn = buf.readVarInt();
        List<AttrSnap> full = new ArrayList<>(fn);
        for (int i = 0; i < fn; i++) {
            ResourceLocation aid = buf.readResourceLocation();
            double base = buf.readDouble();
            int mn = buf.readVarInt();
            List<ModSnap> mods = new ArrayList<>(mn);
            for (int j = 0; j < mn; j++) {
                mods.add(new ModSnap(buf.readUUID(), buf.readUtf(256), buf.readDouble(), buf.readByte(), buf.readItem()));
            }
            full.add(new AttrSnap(aid, base, mods));
        }
        return new PuffStatsPacket(id, attrs, level, cap, genes, now, capB, regen, full);
    }

    public static void handle(PuffStatsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.puffcompanion.client.PuffStatsCache.put(msg));
        ctx.get().setPacketHandled(true);
    }
}
