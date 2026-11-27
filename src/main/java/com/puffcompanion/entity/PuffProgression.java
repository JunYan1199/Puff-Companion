package com.puffcompanion.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Puff's port of the Rats: Reboot training system (RatProgression / RatTrainingItem, read from the Rats source):
 * <ul>
 *   <li>Level 1..100; rare candy small / super / grand = +1 / +3 / +10 levels (Rats values), never above the
 *       current level cap.</li>
 *   <li>Genes 0.5..2.5 per stat, each gene treat +0.25 (Rats values). Every treat also widens the level cap by
 *       10 (starts at 40), so treats "widen the growth range" and candy "fills it".</li>
 *   <li>Growth curve {@code ((L-1)/99)^2} (Rats {@code RatProgression.growth}); bonuses are rescaled to companion
 *       sized numbers instead of the rats' 3000 HP / 100 damage graduation.</li>
 * </ul>
 * Item ids are matched by registry name, so no Rats class is referenced here.
 */
public final class PuffProgression {
    public enum Gene {
        HEALTH, DAMAGE, ARMOR, SPEED, REGEN, GOETY, IRON, COOLDOWN;

        public String key() { return this.name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final int MAX_LEVEL = 100;
    public static final int BASE_LEVEL_CAP = 40;
    public static final int CAP_PER_TREAT = 10;
    public static final float GENE_MIN = 0.5F, GENE_START = 1.0F, GENE_MAX = 2.5F, GENE_STEP = 0.25F;

    /** Bonus at level 100 with gene 1.0 (scaled linearly by the gene). */
    private static final double MAX_HEALTH_BONUS = 40.0;
    private static final double MAX_DAMAGE_BONUS = 8.0;
    private static final double MAX_ARMOR_BONUS = 10.0;
    private static final double MAX_SPEED_BONUS = 0.05;
    /** HP regenerated per second at level 100, gene 1.0. */
    private static final double MAX_REGEN_PER_SEC = 0.5;

    private static final UUID HEALTH_ID = UUID.fromString("6a1c7d2e-1f0b-4a51-9a7e-3c1f0d2b5a01");
    private static final UUID DAMAGE_ID = UUID.fromString("6a1c7d2e-1f0b-4a51-9a7e-3c1f0d2b5a02");
    private static final UUID ARMOR_ID = UUID.fromString("6a1c7d2e-1f0b-4a51-9a7e-3c1f0d2b5a03");
    private static final UUID SPEED_ID = UUID.fromString("6a1c7d2e-1f0b-4a51-9a7e-3c1f0d2b5a04");

    private static final Map<String, Integer> CANDIES = Map.of(
            "rats:rare_candy_small", 1, "rats:rare_candy_super", 3, "rats:rare_candy_grand", 10);

    private int level = 1;
    private int treatsEaten;
    private final EnumMap<Gene, Float> genes = new EnumMap<>(Gene.class);

    public PuffProgression() {
        for (Gene g : Gene.values()) this.genes.put(g, GENE_START);
    }

    // ---------------- Items ----------------

    /** Candy levels for a Rats rare candy, or 0. */
    public static int candyLevels(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? 0 : CANDIES.getOrDefault(id.toString(), 0);
    }

    /** Gene for a Rats gene treat ("rats:gene_treat_<gene>"), or null. */
    @Nullable
    public static Gene treatGene(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !"rats".equals(id.getNamespace()) || !id.getPath().startsWith("gene_treat_")) return null;
        String g = id.getPath().substring("gene_treat_".length());
        for (Gene gene : Gene.values()) if (gene.key().equals(g)) return gene;
        return null;
    }

    public static boolean isTrainingItem(ItemStack stack) {
        return candyLevels(stack) > 0 || treatGene(stack) != null;
    }

    /** @return levels actually gained (0 = already at the cap). */
    public int eatCandy(int levels) {
        int target = Math.min(this.levelCap(), this.level + levels);
        int gained = target - this.level;
        this.level = Math.max(this.level, target);
        return Math.max(0, gained);
    }

    /** @return new gene value, or -1 if this gene is already maxed. */
    public float eatTreat(Gene gene) {
        float old = this.gene(gene);
        if (old >= GENE_MAX) return -1;
        float value = Mth.clamp(old + GENE_STEP, GENE_MIN, GENE_MAX);
        this.genes.put(gene, value);
        this.treatsEaten++;
        return value;
    }

    // ---------------- Values ----------------

    public int level() { return this.level; }
    public int levelCap() { return Math.min(MAX_LEVEL, BASE_LEVEL_CAP + CAP_PER_TREAT * this.treatsEaten); }
    public float gene(Gene gene) { return this.genes.getOrDefault(gene, GENE_START); }

    static double growth(int level) {
        double f = (Mth.clamp(level, 1, MAX_LEVEL) - 1.0) / (MAX_LEVEL - 1.0);
        return f * f;
    }

    public double healthBonus(int lvl) { return MAX_HEALTH_BONUS * this.gene(Gene.HEALTH) * growth(lvl); }
    public double damageBonus(int lvl) { return MAX_DAMAGE_BONUS * this.gene(Gene.DAMAGE) * growth(lvl); }
    public double armorBonus(int lvl) { return MAX_ARMOR_BONUS * this.gene(Gene.ARMOR) * growth(lvl); }
    public double speedBonus(int lvl) { return MAX_SPEED_BONUS * this.gene(Gene.SPEED) * growth(lvl); }
    public double regenPerSecond() { return MAX_REGEN_PER_SEC * this.gene(Gene.REGEN) * growth(this.level); }

    /** Re-applies the transient growth modifiers to Puff (call after any change and after loading). */
    public void apply(PuffEntity puff) {
        set(puff, Attributes.MAX_HEALTH, HEALTH_ID, "puff_growth_health", this.healthBonus(this.level));
        set(puff, Attributes.ATTACK_DAMAGE, DAMAGE_ID, "puff_growth_damage", this.damageBonus(this.level));
        set(puff, Attributes.ARMOR, ARMOR_ID, "puff_growth_armor", this.armorBonus(this.level));
        set(puff, Attributes.MOVEMENT_SPEED, SPEED_ID, "puff_growth_speed", this.speedBonus(this.level));
        if (puff.getHealth() > puff.getMaxHealth()) puff.setHealth(puff.getMaxHealth());
    }

    private static void set(PuffEntity puff, Attribute attr, UUID id, String name, double amount) {
        AttributeInstance inst = puff.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADDITION));
    }

    // ---------------- NBT ----------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Level", this.level);
        tag.putInt("TreatsEaten", this.treatsEaten);
        CompoundTag g = new CompoundTag();
        this.genes.forEach((gene, v) -> g.putFloat(gene.key(), v));
        tag.put("Genes", g);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.level = Mth.clamp(tag.getInt("Level"), 1, MAX_LEVEL);
        this.treatsEaten = Math.max(0, tag.getInt("TreatsEaten"));
        CompoundTag g = tag.getCompound("Genes");
        for (Gene gene : Gene.values()) {
            this.genes.put(gene, g.contains(gene.key()) ? Mth.clamp(g.getFloat(gene.key()), GENE_MIN, GENE_MAX) : GENE_START);
        }
    }
}
