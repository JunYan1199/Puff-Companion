package com.puffcompanion;

import com.mojang.logging.LogUtils;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.registry.ModEntities;
import com.puffcompanion.registry.ModItems;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PuffCompanion.MODID)
public class PuffCompanion {
    public static final String MODID = "puffcompanion";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PuffCompanion() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(modBus);
        ModItems.ITEMS.register(modBus);
        com.puffcompanion.registry.ModCreativeTabs.TABS.register(modBus);
        com.puffcompanion.registry.ModBlocks.BLOCKS.register(modBus);
        com.puffcompanion.registry.ModBlocks.BLOCK_ENTITIES.register(modBus);
        com.puffcompanion.registry.ModMenus.MENUS.register(modBus);
        com.puffcompanion.network.PuffNetwork.register();
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::registerAttributes);
        modBus.addListener(this::modifyAttributes);
        modBus.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST, this::copyPlayerAttributes);
        // Forge-bus handlers (InitialPuffSpawner) self-register via @Mod.EventBusSubscriber
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Puff Companion] loaded, rats present: {}",
                net.minecraftforge.fml.ModList.get().isLoaded("rats"));
    }

    /** ApothicAttributes (attributeslib) soft dependency: give Puff its crit / life-steal / dodge... attributes. */
    /**
     * Runs last: gives Puff every attribute players have (vanilla + any mod's custom ones such as Moonstone's
     * "all attack" / "hurt", ApothicAttributes, Terra Curio...). Accessory bonuses and the mods' damage
     * formulas read these attributes, so without them those accessories would do nothing on Puff.
     */
    private void copyPlayerAttributes(final net.minecraftforge.event.entity.EntityAttributeModificationEvent event) {
        int added = 0;
        for (net.minecraft.world.entity.ai.attributes.Attribute attr : net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValues()) {
            if (event.has(net.minecraft.world.entity.EntityType.PLAYER, attr)
                    && !event.has(com.puffcompanion.registry.ModEntities.PUFF.get(), attr)) {
                event.add(com.puffcompanion.registry.ModEntities.PUFF.get(), attr);
                added++;
            }
        }
        LOGGER.info("[Puff Companion] Copied {} player attributes onto Puff", added);
    }

    private void modifyAttributes(final net.minecraftforge.event.entity.EntityAttributeModificationEvent event) {
        if (com.puffcompanion.compat.AttributesCompat.isLoaded()) {
            com.puffcompanion.compat.AttributesCompat.addToPuff(event);
        }
    }

    private void registerAttributes(final EntityAttributeCreationEvent event) {
        event.put(ModEntities.PUFF.get(), PuffEntity.createAttributes().build());
    }

}
