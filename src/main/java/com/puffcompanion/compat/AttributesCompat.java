package com.puffcompanion.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * Entry point for ApothicAttributes ({@code attributeslib}). Since stage 5 it is a HARD dependency (declared
 * mandatory in mods.toml), but the isLoaded() guard is kept so a broken install degrades instead of crashing.
 */
public final class AttributesCompat {
    public static final String MODID = "attributeslib";

    private AttributesCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /** Adds every attributeslib attribute (crit chance/damage, armor pierce, life steal, healing, dodge...) to Puff. */
    public static void addToPuff(EntityAttributeModificationEvent event) {
        if (isLoaded()) com.puffcompanion.compat.attributeslib.ApothicAttributesImpl.addToPuff(event);
    }

    /** Registry ids of attributeslib's attributes, for the stats page (empty when not installed). */
    public static List<ResourceLocation> attributeIds() {
        return isLoaded() ? com.puffcompanion.compat.attributeslib.ApothicAttributesImpl.attributeIds() : List.of();
    }
}
