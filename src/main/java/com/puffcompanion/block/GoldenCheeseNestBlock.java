package com.puffcompanion.block;

import com.puffcompanion.entity.PuffEventTriggers;
import com.puffcompanion.item.CheeseCoreItem;
import com.puffcompanion.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Golden cheese nest: right-click with a spirit cheese core to place it, then add 3 cheeses
 * (rats:cheese / rats:blue_cheese, any "cheese" item) to start the revival ritual. Sneak + empty hand takes the
 * core back out.
 */
public class GoldenCheeseNestBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 6, 15);

    public GoldenCheeseNestBlock(Properties props) {
        super(props);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GoldenCheeseNestBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlocks.GOLDEN_CHEESE_NEST_BE.get(), GoldenCheeseNestBlockEntity::serverTick);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof GoldenCheeseNestBlockEntity nest)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ItemStack stack = player.getItemInHand(hand);

        if (stack.getItem() instanceof CheeseCoreItem) {
            if (nest.insertCore(stack)) {
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS, 1.0F, 1.2F);
                player.displayClientMessage(Component.translatable("message.puffcompanion.nest.core_in",
                        GoldenCheeseNestBlockEntity.CHEESE_NEEDED), true);
            }
            return InteractionResult.CONSUME;
        }
        if (PuffEventTriggers.isCheese(stack) && nest.hasCore()) {
            if (nest.addCheese(player)) {
                if (!player.getAbilities().instabuild) stack.shrink(1);
                player.displayClientMessage(nest.ritualRunning()
                        ? Component.translatable("message.puffcompanion.nest.ritual")
                        : Component.translatable("message.puffcompanion.nest.cheese", nest.cheese(),
                        GoldenCheeseNestBlockEntity.CHEESE_NEEDED), true);
            }
            return InteractionResult.CONSUME;
        }
        if (stack.isEmpty() && player.isShiftKeyDown() && nest.hasCore()) {
            ItemStack core = nest.takeCore();
            if (!core.isEmpty() && !player.getInventory().add(core)) player.drop(core, false);
            return InteractionResult.CONSUME;
        }
        player.displayClientMessage(nest.hasCore()
                ? Component.translatable("message.puffcompanion.nest.cheese", nest.cheese(), GoldenCheeseNestBlockEntity.CHEESE_NEEDED)
                : Component.translatable("message.puffcompanion.nest.empty"), true);
        return InteractionResult.CONSUME;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof GoldenCheeseNestBlockEntity nest) {
            nest.dropContents(level, pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
