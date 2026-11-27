package com.puffcompanion.entity;

import com.puffcompanion.compat.rats.SquadStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puff's own self-work AI when {@link PuffEntity#getWorkMode()} is set to FARM, LUMBER, FISH, or RANCH.
 * Uses the staff's bound work area if present, otherwise searches a 10-block radius around Puff.
 * All outputs flow through {@link SquadStorage#deposit} (bound container -> Puff's 18-slot nest -> drop).
 */
public class PuffSelfWorkGoal extends Goal {
    private static final int DEFAULT_RADIUS = 14;
    private static final int HOME_WORK_RADIUS = 18;
    private static final int SCAN_INTERVAL = 25;
    private static final int WORK_COOLDOWN = 28;
    private static final int FISH_INTERVAL = 110;
    private static final int MAX_RANCH_ANIMALS = 16;

    private final PuffEntity puff;
    private int scanDelay;
    private int workCooldown;
    @Nullable private BlockPos targetPos;
    @Nullable private Animal targetAnimal;

    public PuffSelfWorkGoal(PuffEntity puff) {
        this.puff = puff;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.puff.isTame() || this.puff.isOrderedToSit() || this.puff.isResting() || this.puff.isPassenger()) {
            return false;
        }
        if (this.puff.isPanicking() || this.puff.getTarget() != null) return false;
        return this.puff.getWorkMode().isSelfWork();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void stop() {
        this.targetPos = null;
        this.targetAnimal = null;
    }

    @Override
    public void tick() {
        if (!(this.puff.level() instanceof ServerLevel level)) return;
        if (this.workCooldown > 0) this.workCooldown--;

        PuffWorkMode mode = this.puff.getWorkMode();
        switch (mode) {
            case FARM -> tickFarm(level);
            case LUMBER -> tickLumber(level);
            case FISH -> tickFish(level);
            case RANCH -> tickRanch(level);
            default -> {}
        }
        if (this.targetPos != null || this.targetAnimal != null) {
            this.puff.keepHeldItemsVisible(20);
        }
    }

    private BlockPos centerPos() {
        return this.puff.isHomeMode() && this.puff.getHomePos() != null
                ? this.puff.getHomePos()
                : this.puff.blockPosition();
    }

    private BlockPos minCorner() {
        if (this.puff.isHomeMode() && this.puff.getHomePos() != null) {
            return this.puff.getHomePos().offset(-HOME_WORK_RADIUS, -6, -HOME_WORK_RADIUS);
        }
        BlockPos min = this.puff.getSquad().areaMin();
        return min != null ? min : centerPos().offset(-DEFAULT_RADIUS, -4, -DEFAULT_RADIUS);
    }

    private BlockPos maxCorner() {
        if (this.puff.isHomeMode() && this.puff.getHomePos() != null) {
            return this.puff.getHomePos().offset(HOME_WORK_RADIUS, 10, HOME_WORK_RADIUS);
        }
        BlockPos max = this.puff.getSquad().areaMax();
        return max != null ? max : centerPos().offset(DEFAULT_RADIUS, 8, DEFAULT_RADIUS);
    }

    private AABB workBox() {
        BlockPos min = minCorner();
        BlockPos max = maxCorner();
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
    }

    private boolean moveToOrReady(BlockPos pos) {
        if (this.puff.blockPosition().distSqr(pos) > 12.25) {
            this.puff.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.15D);
            return false;
        }
        this.puff.getNavigation().stop();
        this.puff.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return this.workCooldown <= 0;
    }

    // ---------------- FARM ----------------
    private void tickFarm(ServerLevel level) {
        if (this.targetPos == null || !isMatureCrop(level.getBlockState(this.targetPos))) {
            if (--this.scanDelay > 0) return;
            this.scanDelay = SCAN_INTERVAL;
            this.targetPos = findNearestBlock(level, s -> isMatureCrop(s));
            if (this.targetPos == null) return;
        }
        if (!moveToOrReady(this.targetPos)) return;

        BlockState state = level.getBlockState(this.targetPos);
        List<ItemStack> drops = Block.getDrops(state, level, this.targetPos, null, this.puff, ItemStack.EMPTY);
        level.destroyBlock(this.targetPos, false);
        for (ItemStack d : drops) SquadStorage.deposit(this.puff, this.puff, d);

        ItemStack seed = SquadStorage.takeOne(this.puff, level,
                s -> s.is(Tags.Items.SEEDS) && s.getItem() instanceof BlockItem);
        if (!seed.isEmpty() && seed.getItem() instanceof BlockItem bi) {
            BlockState plant = bi.getBlock().defaultBlockState();
            if (plant.canSurvive(level, this.targetPos)) {
                level.setBlock(this.targetPos, plant, 3);
            } else {
                SquadStorage.deposit(this.puff, this.puff, seed);
            }
        }
        this.puff.playSwingAnim();
        this.targetPos = null;
        this.workCooldown = WORK_COOLDOWN;
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    // ---------------- LUMBER ----------------
    private void tickLumber(ServerLevel level) {
        if (this.targetPos == null || !level.getBlockState(this.targetPos).is(BlockTags.LOGS)) {
            if (--this.scanDelay > 0) return;
            this.scanDelay = SCAN_INTERVAL;
            this.targetPos = findNearestBlock(level, s -> s.is(BlockTags.LOGS));
            if (this.targetPos == null) return;
            // Walk toward the trunk base so elevated logs on slopes don't block reach
            while (level.getBlockState(this.targetPos.below()).is(BlockTags.LOGS)) {
                this.targetPos = this.targetPos.below();
            }
        }
        if (!moveToOrReady(this.targetPos)) return;

        BlockPos base = this.targetPos;
        while (level.getBlockState(base.below()).is(BlockTags.LOGS)) base = base.below();
        fellTree(level, this.targetPos);

        if (level.isEmptyBlock(base)) {
            ItemStack sapling = SquadStorage.takeOne(this.puff, level,
                    s -> s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem);
            if (!sapling.isEmpty() && sapling.getItem() instanceof BlockItem bi) {
                BlockState sState = bi.getBlock().defaultBlockState();
                if (sState.canSurvive(level, base)) level.setBlock(base, sState, 3);
                else SquadStorage.deposit(this.puff, this.puff, sapling);
            }
        }
        this.puff.playSwingAnim();
        this.targetPos = null;
        this.workCooldown = WORK_COOLDOWN + 10;
    }

    private void fellTree(ServerLevel level, BlockPos start) {
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        q.add(start);
        seen.add(start);
        int felled = 0;
        while (!q.isEmpty() && felled < 64) {
            BlockPos p = q.poll();
            BlockState st = level.getBlockState(p);
            if (!st.is(BlockTags.LOGS)) continue;
            for (ItemStack d : Block.getDrops(st, level, p, null, this.puff, ItemStack.EMPTY)) {
                SquadStorage.deposit(this.puff, this.puff, d);
            }
            level.destroyBlock(p, false);
            felled++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (seen.add(n) && level.getBlockState(n).is(BlockTags.LOGS)) q.add(n);
                    }
                }
            }
        }
    }

    // ---------------- FISH ----------------
    private void tickFish(ServerLevel level) {
        if (this.targetPos == null || !level.getFluidState(this.targetPos).is(FluidTags.WATER)) {
            if (--this.scanDelay > 0) return;
            this.scanDelay = SCAN_INTERVAL;
            this.targetPos = findNearestBlock(level, s -> s.getFluidState().is(FluidTags.WATER));
            if (this.targetPos == null) return;
        }
        if (!moveToOrReady(this.targetPos)) return;

        var tableKey = level.random.nextFloat() < 0.08F ? BuiltInLootTables.FISHING_TREASURE : BuiltInLootTables.FISHING;
        LootTable table = level.getServer().getLootData().getLootTable(tableKey);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(this.targetPos))
                .withParameter(LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD))
                .withParameter(LootContextParams.THIS_ENTITY, this.puff)
                .create(LootContextParamSets.FISHING);
        for (ItemStack loot : table.getRandomItems(params)) {
            SquadStorage.deposit(this.puff, this.puff, loot);
        }
        this.puff.playSwingAnim();
        this.workCooldown = FISH_INTERVAL;
    }

    // ---------------- RANCH ----------------
    private void tickRanch(ServerLevel level) {
        if (this.targetAnimal == null || !this.targetAnimal.isAlive()) {
            if (--this.scanDelay > 0) return;
            this.scanDelay = SCAN_INTERVAL;
            List<Animal> animals = level.getEntitiesOfClass(Animal.class, workBox(), Animal::isAlive);
            for (Animal a : animals) {
                if (a instanceof Sheep sheep && !sheep.isSheared() && !sheep.isBaby()) {
                    this.targetAnimal = sheep;
                    break;
                }
                if (!a.isBaby() && a.canFallInLove() && a.getAge() == 0
                        && countSameSpecies(animals, a) < MAX_RANCH_ANIMALS
                        && SquadStorage.has(this.puff, level, a::isFood)) {
                    this.targetAnimal = a;
                    break;
                }
            }
            if (this.targetAnimal == null) return;
        }

        if (this.puff.distanceToSqr(this.targetAnimal) > 6.25) {
            this.puff.getNavigation().moveTo(this.targetAnimal, 1.15D);
            return;
        }
        this.puff.getNavigation().stop();
        this.puff.getLookControl().setLookAt(this.targetAnimal, 30.0F, 30.0F);
        if (this.workCooldown > 0) return;

        if (this.targetAnimal instanceof Sheep sheep && !sheep.isSheared() && !sheep.isBaby()) {
            sheep.setSheared(true);
            int count = 1 + level.random.nextInt(3);
            SquadStorage.deposit(this.puff, this.puff, new ItemStack(Items.WHITE_WOOL, count));
        } else if (this.targetAnimal instanceof Chicken && level.random.nextFloat() < 0.35F) {
            SquadStorage.deposit(this.puff, this.puff, new ItemStack(Items.EGG));
        } else if (!this.targetAnimal.isBaby() && this.targetAnimal.canFallInLove() && this.targetAnimal.getAge() == 0) {
            ItemStack food = SquadStorage.takeOne(this.puff, level, this.targetAnimal::isFood);
            if (!food.isEmpty()) {
                this.targetAnimal.setInLove(null);
            }
        }
        this.puff.playSwingAnim();
        this.targetAnimal = null;
        this.workCooldown = WORK_COOLDOWN + 10;
    }

    private static int countSameSpecies(List<Animal> all, Animal sample) {
        int c = 0;
        for (Animal a : all) if (a.getType() == sample.getType()) c++;
        return c;
    }

    @Nullable
    private BlockPos findNearestBlock(ServerLevel level, java.util.function.Predicate<BlockState> test) {
        BlockPos min = minCorner();
        BlockPos max = maxCorner();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(p)) continue;
            if (!test.test(level.getBlockState(p))) continue;
            double d = p.distSqr(this.puff.blockPosition());
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }
}
