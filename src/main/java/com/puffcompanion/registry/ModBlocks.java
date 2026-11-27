package com.puffcompanion.registry;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.block.GoldenCheeseNestBlock;
import com.puffcompanion.block.GoldenCheeseNestBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, PuffCompanion.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, PuffCompanion.MODID);

    public static final RegistryObject<Block> GOLDEN_CHEESE_NEST = BLOCKS.register("golden_cheese_nest",
            () -> new GoldenCheeseNestBlock(BlockBehaviour.Properties.of().mapColor(MapColor.GOLD)
                    .strength(2.0F, 6.0F).sound(SoundType.WOOL).noOcclusion().lightLevel(s -> 7)));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<GoldenCheeseNestBlockEntity>> GOLDEN_CHEESE_NEST_BE =
            BLOCK_ENTITIES.register("golden_cheese_nest",
                    () -> BlockEntityType.Builder.of(GoldenCheeseNestBlockEntity::new, GOLDEN_CHEESE_NEST.get()).build(null));

    private ModBlocks() {}
}
