package com.puffcompanion.compat.attributeslib;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.registry.ModEntities;
import dev.shadowsoffire.attributeslib.api.ALObjects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Only loaded when attributeslib is installed. {@code ALObjects.Attributes} (verified with javap) holds
 * {@code RegistryObject<Attribute>} constants (CRIT_CHANCE, CRIT_DAMAGE, ARMOR_PIERCE, LIFE_STEAL,
 * HEALING_RECEIVED, DODGE_CHANCE, ...). All of them are enumerated so new ones are picked up automatically.
 */
public final class ApothicAttributesImpl {
    private ApothicAttributesImpl() {}

    @SuppressWarnings("unchecked")
    private static List<Attribute> attributes() {
        List<Attribute> out = new ArrayList<>();
        for (Field f : ALObjects.Attributes.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != RegistryObject.class) continue;
            try {
                RegistryObject<Attribute> ro = (RegistryObject<Attribute>) f.get(null);
                if (ro != null && ro.isPresent()) out.add(ro.get());
            } catch (ReflectiveOperationException | ClassCastException e) {
                PuffCompanion.LOGGER.debug("[Puff Companion] skip attributeslib field {}", f.getName());
            }
        }
        return out;
    }

    public static void addToPuff(EntityAttributeModificationEvent event) {
        int added = 0;
        for (Attribute attr : attributes()) {
            if (!event.has(ModEntities.PUFF.get(), attr)) {
                event.add(ModEntities.PUFF.get(), attr);
                added++;
            }
        }
        PuffCompanion.LOGGER.info("[Puff Companion] attributeslib present: added {} special attributes to Puff", added);
    }

    public static List<ResourceLocation> attributeIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Attribute attr : attributes()) {
            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attr);
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
