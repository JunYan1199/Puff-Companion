package com.puffcompanion.compat.rats;

import com.github.alexthe666.rats.registry.RatsItemRegistry;
import com.github.alexthe666.rats.server.entity.rat.TamedRat;
import com.github.alexthe666.rats.server.misc.RatUpgradeUtils;
import com.puffcompanion.compat.CreateCompat;
import com.puffcompanion.compat.create.SchematicPlan;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffSquad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Work goal Puff's squad rats get (priority 0, MOVE + LOOK, so it outranks the rat's own wandering / work
 * goals while a job is assigned). The job, work area and storage come from the squad on Puff; the rat only
 * remembers which Puff it belongs to ({@link SquadManager#DATA}). Wearing the matching Rats work upgrade
 * ({@link SquadJob#boostItems()}) halves every work interval.
 */
public class PuffRatJobGoal extends Goal {
    /** Rat UUID -> status line for the panel {lang key, argument}. */
    public static final Map<UUID, String[]> STATUS = new HashMap<>();
    private static final int CHEESE_YIELDS = 4;
    private static final int RANCH_CAP = 16;

    private final TamedRat rat;
    @Nullable private PuffEntity puff;
    private SquadJob job = SquadJob.NONE;
    private int resolveTimer;
    @Nullable private BlockPos target;
    @Nullable private Entity targetEntity;
    private int targetAge;
    private int actTimer;
    private int scanTimer;
    private int cheeseCredits;
    private int idleTimer;

    public PuffRatJobGoal(TamedRat rat) {
        this.rat = rat;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ---------------------------------------------------------------- goal plumbing

    private boolean resolve() {
        if (--this.resolveTimer > 0 && this.puff != null) {
            return this.puff.isAlive() && this.job != SquadJob.NONE;
        }
        this.resolveTimer = 20;
        this.puff = SquadManager.puffOf(this.rat);
        PuffSquad.Member m = this.puff == null ? null : this.puff.getSquad().member(this.rat.getUUID());
        SquadJob j = m == null ? SquadJob.NONE : SquadJob.byId(m.job);
        if (j == SquadJob.BUILDER && !CreateCompat.isLoaded()) j = SquadJob.NONE;
        if (j != this.job) {
            this.job = j;
            this.target = null;
            this.targetEntity = null;
        }
        return this.puff != null && j != SquadJob.NONE;
    }

    @Override
    public boolean canUse() {
        if (!(this.rat.level() instanceof ServerLevel) || this.rat.isPassenger() || this.rat.isOrderedToSit()) return false;
        return this.resolve() && this.puff.getSquad().hasArea() && this.puff.level() == this.rat.level();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        this.rat.getNavigation().stop();
        this.target = null;
        this.targetEntity = null;
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) this.rat.level();
        PuffEntity puff = this.puff;
        if (puff == null) return;
        if (this.actTimer > 0) this.actTimer--;
        if (this.scanTimer > 0) this.scanTimer--;
        try {
            switch (this.job) {
                case LUMBERJACK -> this.lumberjack(level, puff);
                case MINER -> this.miner(level, puff);
                case BUILDER -> this.builder(level, puff);
                case FARMER -> this.farmer(level, puff);
                case FISHER -> this.fisher(level, puff);
                case PATROL -> this.patrol(level, puff);
                case RANCHER -> this.rancher(level, puff);
                default -> { }
            }
        } catch (Throwable t) {
            com.puffcompanion.PuffCompanion.LOGGER.debug("[Puff Companion] squad job {} failed: {}", this.job, t.toString());
            this.target = null;
            this.actTimer = 40;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void status(String key, String arg) {
        STATUS.put(this.rat.getUUID(), new String[]{key, arg});
    }

    private boolean boosted() {
        for (Item item : this.job.boostItems()) {
            if (RatUpgradeUtils.hasUpgrade(this.rat, item)) return true;
        }
        return false;
    }

    private int interval(int base) {
        return this.boosted() ? Math.max(1, base / 2) : base;
    }

    /** Walks next to {@code pos}; true once there (or after a while: unreachable spots still get worked). */
    private boolean near(BlockPos pos, double reach) {
        Vec3 c = Vec3.atCenterOf(pos);
        this.rat.getLookControl().setLookAt(c.x, c.y, c.z);
        if (this.rat.position().distanceToSqr(c) <= reach * reach) {
            this.rat.getNavigation().stop();
            return true;
        }
        if (++this.targetAge > 240) return true;
        if (this.rat.getNavigation().isDone() || this.targetAge % 20 == 0) {
            this.rat.getNavigation().moveTo(c.x, pos.getY(), c.z, 1.2D);
        }
        return false;
    }

    private boolean nearEntity(Entity e, double reach) {
        this.rat.getLookControl().setLookAt(e, 30F, 30F);
        if (this.rat.distanceToSqr(e) <= reach * reach) {
            this.rat.getNavigation().stop();
            return true;
        }
        if (this.rat.getNavigation().isDone() || ++this.targetAge % 15 == 0) this.rat.getNavigation().moveTo(e, 1.25D);
        return false;
    }

    /** Nearest block in the work area matching {@code test} (scans at most every 2 s). */
    @Nullable
    private BlockPos find(ServerLevel level, PuffSquad squad, Predicate<BlockState> test) {
        if (this.scanTimer > 0) return null;
        this.scanTimer = 40;
        BlockPos lo = squad.areaMin(), hi = squad.areaMax();
        if (lo == null) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (!level.isLoaded(p)) continue;
            if (!test.test(level.getBlockState(p))) continue;
            double d = p.distToCenterSqr(this.rat.position());
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        this.targetAge = 0;
        return best;
    }

    private void wander(PuffSquad squad) {
        if (++this.idleTimer % 100 != 0) return;
        BlockPos lo = squad.areaMin(), hi = squad.areaMax();
        if (lo == null) return;
        double x = lo.getX() + this.rat.getRandom().nextInt(hi.getX() - lo.getX() + 1) + 0.5;
        double z = lo.getZ() + this.rat.getRandom().nextInt(hi.getZ() - lo.getZ() + 1) + 0.5;
        this.rat.getNavigation().moveTo(x, lo.getY() + (hi.getY() - lo.getY()) / 2.0, z, 1.0D);
    }

    private void breakEffects(ServerLevel level, BlockPos pos, BlockState state) {
        level.levelEvent(2001, pos, Block.getId(state));
        this.rat.swing(InteractionHand.MAIN_HAND);
    }

    private static boolean isCheese(ItemStack s) {
        ResourceLocationHolder.init();
        return s.is(ResourceLocationHolder.CHEESE) || s.is(ResourceLocationHolder.BLUE_CHEESE);
    }

    /** Rats cheese items, resolved once (rats is a required dependency). */
    private static final class ResourceLocationHolder {
        static Item CHEESE, BLUE_CHEESE;

        static void init() {
            if (CHEESE != null) return;
            CHEESE = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("rats", "cheese"));
            BLUE_CHEESE = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("rats", "blue_cheese"));
        }
    }

    // ---------------------------------------------------------------- 1. lumberjack

    private void lumberjack(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        if (this.target != null && !level.getBlockState(this.target).is(BlockTags.LOGS)) this.target = null;
        if (this.target == null) this.target = this.find(level, squad, s -> s.is(BlockTags.LOGS));
        if (this.target == null) {
            this.status("puff.squad.status.no_trees", "");
            this.collectItems(level, puff, squad, s -> s.is(ItemTags.SAPLINGS) || s.is(Items.APPLE) || s.is(Items.STICK));
            this.wander(squad);
            return;
        }
        this.status("puff.squad.status.chopping", "");
        if (!this.near(this.target, 2.5) || this.actTimer > 0) return;
        this.actTimer = this.interval(40);
        // whole tree: every connected log (diagonals too), capped
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(this.target);
        seen.add(this.target);
        BlockPos hi = squad.areaMax();
        while (!queue.isEmpty() && logs.size() < 192) {
            BlockPos p = queue.poll();
            if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
            logs.add(p);
            for (BlockPos n : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
                if (n.getY() > hi.getY() + 24 || !seen.add(n.immutable())) continue;
                queue.add(n.immutable());
            }
        }
        BlockPos base = this.target;
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        List<ItemStack> drops = new ArrayList<>();
        for (BlockPos p : logs) {
            BlockState s = level.getBlockState(p);
            drops.addAll(Block.getDrops(s, level, p, level.getBlockEntity(p), this.rat, axe));
            level.destroyBlock(p, false, this.rat);
            if (p.getY() < base.getY()) base = p;
        }
        this.rat.swing(InteractionHand.MAIN_HAND);
        // replant on the stump's soil
        if (level.isEmptyBlock(base) && level.getBlockState(base.below()).is(BlockTags.DIRT)) {
            ItemStack sapling = ItemStack.EMPTY;
            for (ItemStack d : drops) {
                if (d.is(ItemTags.SAPLINGS)) {
                    sapling = d.split(1);
                    break;
                }
            }
            if (sapling.isEmpty()) sapling = SquadStorage.takeOne(puff, level, s -> s.is(ItemTags.SAPLINGS));
            if (sapling.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().canSurvive(level, base)) {
                level.setBlock(base, bi.getBlock().defaultBlockState(), Block.UPDATE_ALL);
            } else {
                drops.add(sapling);
            }
        }
        for (ItemStack d : drops) SquadStorage.deposit(puff, this.rat, d);
        this.target = null;
    }

    /** Picks up loose items of the given kinds lying in the work area (fallen saplings, eggs, wool...). */
    private void collectItems(ServerLevel level, PuffEntity puff, PuffSquad squad, Predicate<ItemStack> kinds) {
        if (this.actTimer > 0) return;
        this.actTimer = 40;
        AABB box = squad.areaBox();
        if (box == null) return;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box.inflate(1), e -> e.isAlive() && kinds.test(e.getItem()))) {
            SquadStorage.deposit(puff, this.rat, item.getItem().copy());
            item.discard();
        }
    }

    // ---------------------------------------------------------------- 2. infinite miner

    private void miner(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        if (this.target != null && !level.getBlockState(this.target).is(Tags.Blocks.ORES)) this.target = null;
        if (this.target == null) this.target = this.find(level, squad, s -> s.is(Tags.Blocks.ORES));
        if (this.target == null) {
            this.status("puff.squad.status.no_ores", "");
            this.wander(squad);
            return;
        }
        if (!this.near(this.target, 2.5)) return;
        if (this.rat.tickCount % 10 == 0) this.rat.swing(InteractionHand.MAIN_HAND);   // keeps knocking
        if (this.actTimer > 0) return;
        this.actTimer = this.interval(60);
        if (this.cheeseCredits <= 0) {
            ItemStack cheese = SquadStorage.takeOne(puff, level, PuffRatJobGoal::isCheese);
            if (cheese.isEmpty()) {
                this.status("puff.squad.status.no_cheese", "");
                return;
            }
            this.cheeseCredits = CHEESE_YIELDS;
        }
        this.cheeseCredits--;
        BlockState ore = level.getBlockState(this.target);
        // the ore stays; its real loot table yields again and again
        List<ItemStack> drops = Block.getDrops(ore, level, this.target, null, this.rat, new ItemStack(Items.DIAMOND_PICKAXE));
        boolean doubled = RatUpgradeUtils.hasUpgrade(this.rat, RatsItemRegistry.RAT_UPGRADE_ORE_DOUBLING.get());
        for (ItemStack d : drops) {
            if (doubled) d.setCount(Math.min(d.getMaxStackSize(), d.getCount() * 2));
            SquadStorage.deposit(puff, this.rat, d);
        }
        this.breakEffects(level, this.target, ore);
        this.status("puff.squad.status.mining", ore.getBlock().getName().getString());
        if (this.rat.getRandom().nextInt(4) == 0) this.target = null;   // spread over the area's ores
    }

    // ---------------------------------------------------------------- 3. Create blueprint builder

    private void builder(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        SchematicPlan plan = SchematicPlan.of(level, squad);
        if (plan == null) {
            this.status(squad.blueprint.isEmpty() ? "puff.squad.status.no_blueprint" : "puff.squad.status.bad_blueprint", "");
            this.wander(squad);
            return;
        }
        SchematicPlan.Step step = plan.next(level, squad);
        if (step == null) {
            this.status("puff.squad.status.built", "");
            squad.buildStatus = "done";
            this.wander(squad);
            return;
        }
        Item need = step.state().getBlock().asItem();
        if (!SquadStorage.has(puff, level, s -> s.is(need))) {
            this.status("puff.squad.status.missing", need.getDescription().getString());
            squad.buildStatus = need.getDescription().getString();
            return;
        }
        this.status("puff.squad.status.building", (squad.buildIndex + 1) + "/" + plan.size());
        squad.buildStatus = "";
        if (this.target == null || !this.target.equals(step.pos())) {
            this.target = step.pos();
            this.targetAge = 0;
        }
        if (!this.near(step.pos(), 4.5) || this.actTimer > 0) return;
        this.actTimer = this.interval(10);
        ItemStack item = SquadStorage.takeOne(puff, level, s -> s.is(need));
        if (item.isEmpty()) return;
        level.setBlock(step.pos(), step.state(), Block.UPDATE_ALL);
        level.playSound(null, step.pos(), step.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.7F, 1.0F);
        this.rat.swing(InteractionHand.MAIN_HAND);
        squad.buildIndex = step.index() + 1;
        this.target = null;
    }

    // ---------------------------------------------------------------- 4. farmer

    private static boolean ripe(BlockState s) {
        if (s.getBlock() instanceof CropBlock crop) return crop.isMaxAge(s);
        if (s.getBlock() instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE) >= 3;
        return false;
    }

    private void farmer(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        if (this.target != null && !ripe(level.getBlockState(this.target))) this.target = null;
        if (this.target == null) this.target = this.find(level, squad, PuffRatJobGoal::ripe);
        if (this.target == null) {
            this.status("puff.squad.status.no_crops", "");
            this.wander(squad);
            return;
        }
        this.status("puff.squad.status.farming", "");
        if (!this.near(this.target, 2.0) || this.actTimer > 0) return;
        this.actTimer = this.interval(20);
        BlockState state = level.getBlockState(this.target);
        Item seed = state.getBlock().getCloneItemStack(level, this.target, state).getItem();
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, this.target, null, this.rat, ItemStack.EMPTY));
        level.destroyBlock(this.target, false, this.rat);
        ItemStack replant = ItemStack.EMPTY;
        for (ItemStack d : drops) {
            if (d.is(seed)) {
                replant = d.split(1);
                break;
            }
        }
        if (replant.isEmpty()) replant = SquadStorage.takeOne(puff, level, s -> s.is(seed));
        if (!replant.isEmpty()) {
            BlockState young = state.getBlock() instanceof CropBlock crop ? crop.getStateForAge(0) : state.getBlock().defaultBlockState();
            if (young.canSurvive(level, this.target)) level.setBlock(this.target, young, Block.UPDATE_ALL);
            else drops.add(replant);
        }
        for (ItemStack d : drops) SquadStorage.deposit(puff, this.rat, d);
        this.rat.swing(InteractionHand.MAIN_HAND);
        this.target = null;
    }

    // ---------------------------------------------------------------- 5. fisher

    private void fisher(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        Predicate<BlockPos> water = p -> level.getFluidState(p).is(Fluids.WATER) && level.getFluidState(p).isSource() && level.isEmptyBlock(p.above());
        if (this.target != null && !water.test(this.target)) this.target = null;
        if (this.target == null && this.scanTimer <= 0) {
            BlockPos found = this.find(level, squad, s -> s.getFluidState().is(Fluids.WATER) && s.getFluidState().isSource());
            this.target = found != null && water.test(found) ? found : null;
        }
        if (this.target == null) {
            this.status("puff.squad.status.no_water", "");
            this.wander(squad);
            return;
        }
        this.status("puff.squad.status.fishing", "");
        if (!this.near(this.target, 3.0)) return;
        if (this.rat.tickCount % 40 == 0) {
            level.sendParticles(ParticleTypes.FISHING, this.target.getX() + 0.5, this.target.getY() + 1.0, this.target.getZ() + 0.5, 3, 0.2, 0, 0.2, 0.02);
        }
        if (this.actTimer > 0) return;
        this.actTimer = this.interval(400);
        boolean treasure = this.rat.getRandom().nextInt(20) == 0;
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(this.target))
                .withParameter(LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD))
                .withParameter(LootContextParams.THIS_ENTITY, this.rat)
                .withLuck(0F)
                .create(LootContextParamSets.FISHING);
        var table = level.getServer().getLootData().getLootTable(treasure ? BuiltInLootTables.FISHING_TREASURE : BuiltInLootTables.FISHING);
        for (ItemStack d : table.getRandomItems(params)) SquadStorage.deposit(puff, this.rat, d);
        level.playSound(null, this.target, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.4F, 1.2F);
        this.rat.swing(InteractionHand.MAIN_HAND);
    }

    // ---------------------------------------------------------------- 6. patrol

    private void patrol(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        AABB box = squad.areaBox();
        if (box == null) return;
        if (!(this.targetEntity instanceof Mob m) || !m.isAlive() || !box.inflate(4).contains(m.position())) {
            this.targetEntity = null;
            if (this.scanTimer <= 0) {
                this.scanTimer = 20;
                Mob best = null;
                double bestD = Double.MAX_VALUE;
                for (Mob e : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof Enemy && e.isAlive())) {
                    double d = e.distanceToSqr(this.rat);
                    if (d < bestD) {
                        bestD = d;
                        best = e;
                    }
                }
                this.targetEntity = best;
                this.targetAge = 0;
            }
        }
        if (this.targetEntity == null) {
            this.status("puff.squad.status.patrolling", "");
            this.wander(squad);
            return;
        }
        this.status("puff.squad.status.fighting", this.targetEntity.getName().getString());
        if (!this.nearEntity(this.targetEntity, 2.0) || this.actTimer > 0) return;
        this.actTimer = this.interval(20);
        this.rat.swing(InteractionHand.MAIN_HAND);
        this.rat.doHurtTarget(this.targetEntity);
    }

    // ---------------------------------------------------------------- 7. rancher

    private void rancher(ServerLevel level, PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        AABB box = squad.areaBox();
        if (box == null) return;
        if (this.rat.tickCount % 60 == 0) {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box.inflate(1),
                    e -> e.isAlive() && (e.getItem().is(Items.EGG) || e.getItem().is(ItemTags.WOOL) || e.getItem().is(Items.FEATHER)))) {
                SquadStorage.deposit(puff, this.rat, item.getItem().copy());
                item.discard();
            }
        }
        if (!(this.targetEntity instanceof Animal a) || !a.isAlive() || !this.wantsCare(level, puff, a, box)) {
            this.targetEntity = null;
            if (this.scanTimer <= 0) {
                this.scanTimer = 40;
                for (Animal e : level.getEntitiesOfClass(Animal.class, box, e -> e.isAlive() && this.wantsCare(level, puff, e, box))) {
                    this.targetEntity = e;
                    this.targetAge = 0;
                    break;
                }
            }
        }
        if (this.targetEntity == null) {
            this.status("puff.squad.status.ranch_idle", "");
            this.wander(squad);
            return;
        }
        Animal animal = (Animal) this.targetEntity;
        this.status("puff.squad.status.ranching", animal.getName().getString());
        if (!this.nearEntity(animal, 2.0) || this.actTimer > 0) return;
        this.actTimer = this.interval(40);
        this.rat.swing(InteractionHand.MAIN_HAND);
        if (animal instanceof Sheep sheep && sheep.readyForShearing()) {
            sheep.shear(SoundSource.NEUTRAL);
        } else {
            ItemStack food = SquadStorage.takeOne(puff, level, animal::isFood);
            if (!food.isEmpty()) animal.setInLove(null);
        }
        this.targetEntity = null;
    }

    private boolean wantsCare(ServerLevel level, PuffEntity puff, Animal a, AABB box) {
        if (a instanceof Sheep s && s.readyForShearing()) return true;
        if (a.getAge() != 0 || a.isInLove() || !a.canFallInLove()) return false;
        int same = level.getEntitiesOfClass(a.getClass(), box, e -> e.isAlive()).size();
        return same >= 2 && same < RANCH_CAP && SquadStorage.has(puff, level, a::isFood);
    }
}
