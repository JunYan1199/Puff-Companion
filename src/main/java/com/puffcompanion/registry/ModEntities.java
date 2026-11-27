package com.puffcompanion.registry;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, PuffCompanion.MODID);

    public static final RegistryObject<EntityType<PuffEntity>> PUFF = ENTITY_TYPES.register("puff",
            () -> EntityType.Builder.<PuffEntity>of(PuffEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.9F)
                    .clientTrackingRange(10)
                    .build(PuffCompanion.MODID + ":puff"));

    private ModEntities() {}
}
