package com.puffcompanion.entity;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.RatsCompat;
import com.puffcompanion.compat.crossover.ModCrossoverRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side event dispatcher that makes Puff speak (doc 03, parts 1/3/4). Every event has its own
 * cooldown; automatic events also respect a global gap so bubbles never stack, and at most one fires per
 * check. Order below = priority. Owner-hurt / respawn / dimension events are driven by Forge events.
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PuffEventTriggers {
    // ---- event ids (= lang key bases) ----
    public static final String EVENT_CREEPER = "puff.alert.creeper";
    public static final String EVENT_CAT = "puff.panic.cat";
    public static final String EVENT_CHEESE = "puff.crazy.cheese";
    public static final String EVENT_PET = "puff.action.pet";
    public static final String EVENT_PAYDAY = "puff.rats.payday";
    public static final String EVENT_HUNGRY = "puff.state.hungry";
    public static final String EVENT_RATS_NPC = "puff.rats.plague_doctor";
    public static final String EVENT_HOARD = "puff.hoard.pickup";
    public static final String EVENT_PROTECT = "puff.affection.protect";
    public static final String EVENT_CARE = "puff.affection.care";
    public static final String EVENT_HURT = "puff.on_hurt";
    public static final String EVENT_TREASURE = "puff.alert.treasure";
    public static final String EVENT_THUNDER = "puff.weather.thunder";
    public static final String EVENT_DUSK = "puff.time.dusk";
    public static final String EVENT_NIGHT = "puff.time.night";
    public static final String EVENT_CLING = "puff.idle.cling";
    public static final String EVENT_RESPAWN = "puff.player_respawn";

    // ---- cooldowns (ticks) ----
    public static final int CD_CREEPER = 20 * 20;
    public static final int CD_CAT = 15 * 20;
    public static final int CD_CHEESE = 20 * 20;
    public static final int CD_PET = 5 * 20;
    public static final int CD_PAYDAY = 90 * 20;
    public static final int CD_HUNGRY = 60 * 20;
    public static final int CD_RATS_NPC = 120 * 20;
    public static final int CD_HOARD = 30 * 20;
    public static final int CD_PROTECT = 25 * 20;
    public static final int CD_CARE = 35 * 20;
    public static final int CD_HURT = 8 * 20;
    public static final int CD_TREASURE = 90 * 20;
    public static final int CD_THUNDER = 90 * 20;
    public static final int CD_CLING = 90 * 20;

    // ---- ranges / thresholds ----
    private static final double CREEPER_RANGE = 12.0;
    private static final double CAT_RANGE = 8.0;
    private static final double CHEESE_RANGE = 6.0;
    private static final int CHEESE_BLOCK_RADIUS = 6;
    private static final double RATS_NPC_RANGE = 14.0;
    private static final double RAT_SCAN_RANGE = 16.0;
    public static final double RAT_AURA_RANGE = 12.0;
    private static final int TREASURE_RADIUS = 10;
    private static final double CLING_RANGE = 4.0;
    private static final int CLING_STILL_TICKS = 45 * 20;
    private static final double RESPAWN_RANGE = 10.0;
    private static final float CARE_HEALTH_RATIO = 0.35F;
    public static final int HUNGRY_THRESHOLD = 30;
    /** Owner must be reasonably close for Puff to warn / react at all. */
    private static final double OWNER_RANGE = 24.0;

    static final int CHECK_INTERVAL = 10;
    /** Minimum gap between two automatic bubbles. */
    static final int GLOBAL_GAP = 60;
    /** Slow / scanning checks run on this cadence (ticks). */
    private static final int SLOW_INTERVAL = 100;

    /** Owners who died and respawned; Puff greets them when they come back within range. */
    private static final Set<UUID> PENDING_REUNION = ConcurrentHashMap.newKeySet();

    private PuffEventTriggers() {}

    static void tick(PuffEntity puff) {
        if (!(puff.level() instanceof ServerLevel level)) return;
        if (!(puff.getOwner() instanceof Player owner) || owner.level() != level) return;
        long now = level.getGameTime();

        // Background work that is not speech
        if (puff.tickCount % 40 == 0) RatsCompat.applyLeaderAura(puff, RAT_AURA_RANGE);
        puff.trackOwnerStillness(owner, now);

        if (puff.distanceToSqr(owner) > OWNER_RANGE * OWNER_RANGE) return;
        if (!puff.globalGapPassed()) return;
        boolean slow = puff.tickCount % SLOW_INTERVAL == 0;

        // 1. Cat / ocelot: bloodline terror
        if (puff.cooldownReady(EVENT_CAT)
                && (anyNearby(puff, CAT_RANGE, Cat.class) || anyNearby(puff, CAT_RANGE, Ocelot.class))) {
            fire(puff, EVENT_CAT, CD_CAT, 3, 90, true, PuffEntity.ANIM_CAT_PANIC,
                    PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE);
            puff.startPanic();
            return;
        }
        // 2. Reunion after the owner died and respawned
        if (PENDING_REUNION.contains(owner.getUUID()) && puff.distanceToSqr(owner) <= RESPAWN_RANGE * RESPAWN_RANGE) {
            PENDING_REUNION.remove(owner.getUUID());
            puff.say(EVENT_RESPAWN, 2, 120, true, PuffEntity.ANIM_SHY,
                    PuffExpression.TEARY, PuffExpression.EAR_FOLDED);
            hearts(level, puff, 5);
            return;
        }
        // 3. Owner badly hurt
        if (puff.cooldownReady(EVENT_CARE) && owner.isAlive()
                && owner.getHealth() < owner.getMaxHealth() * CARE_HEALTH_RATIO) {
            fire(puff, EVENT_CARE, CD_CARE, 3, 100, true, PuffEntity.ANIM_ALERT,
                    PuffExpression.TEARY, PuffExpression.EAR_DROOPING);
            return;
        }
        // 4. Creeper hearing alert (through walls: plain distance, no line of sight)
        if (puff.cooldownReady(EVENT_CREEPER) && anyNearby(puff, CREEPER_RANGE, Creeper.class)) {
            fire(puff, EVENT_CREEPER, CD_CREEPER, 3, 100, true, PuffEntity.ANIM_ALERT_SNIFF,
                    PuffExpression.SURPRISED, PuffExpression.EAR_ALERT);
            return;
        }
        // 5. Hungry ("out of battery")
        if (puff.cooldownReady(EVENT_HUNGRY) && puff.getPuffHunger() < HUNGRY_THRESHOLD) {
            fire(puff, EVENT_HUNGRY, CD_HUNGRY, 3, 100, false, PuffEntity.ANIM_GROUND_REST,
                    PuffExpression.HUNGRY_DRAINED, PuffExpression.EAR_DROOPING);
            return;
        }
        // 6. Cheese: owner holding it, or a Rats cheese block nearby (block scan on the slow cadence)
        if (puff.cooldownReady(EVENT_CHEESE)
                && ((puff.distanceToSqr(owner) <= CHEESE_RANGE * CHEESE_RANGE
                && (isCheese(owner.getMainHandItem()) || isCheese(owner.getOffhandItem())))
                || (puff.tickCount % 40 == 0 && RatsCompat.cheeseBlockNearby(level, puff.blockPosition(), CHEESE_BLOCK_RADIUS)))) {
            fire(puff, EVENT_CHEESE, CD_CHEESE, 3, 90, false, PuffEntity.ANIM_EAT_CHEESE,
                    PuffExpression.CHEESE_DROOL, PuffExpression.EAR_ALERT);
            hearts(level, puff, 6);
            return;
        }
        // 7. Rats: plague doctor / mouse apothecary nearby
        if (puff.cooldownReady(EVENT_RATS_NPC) && puff.tickCount % 40 == 0 && RatsCompat.ratsNpcNearby(puff, RATS_NPC_RANGE)) {
            fire(puff, EVENT_RATS_NPC, CD_RATS_NPC, 1, 140, false, PuffEntity.ANIM_ALERT_SNIFF,
                    PuffExpression.WINK, PuffExpression.EAR_CURIOUS);
            return;
        }
        // 8. Rats payday: an owned rat is working / hauling nearby
        if (puff.cooldownReady(EVENT_PAYDAY) && puff.tickCount % 40 == 0 && RatsCompat.anyRatWorking(puff, RAT_SCAN_RANGE)) {
            fire(puff, EVENT_PAYDAY, CD_PAYDAY, 3, 110, true, PuffEntity.ANIM_COMMAND_RATS,
                    PuffExpression.BEAM, PuffExpression.EAR_ALERT);
            puff.openPaydayWindow(now);
            return;
        }
        // 9. Treasure sniffing (unopened loot container or spawner)
        if (slow && puff.cooldownReady(EVENT_TREASURE) && treasureNearby(level, puff.blockPosition(), TREASURE_RADIUS)) {
            fire(puff, EVENT_TREASURE, CD_TREASURE, 3, 110, true, PuffEntity.ANIM_HOARD_PICKUP,
                    PuffExpression.STARRY, PuffExpression.EAR_ALERT);
            return;
        }
        // 9.5 Mod crossover proximity reactions (mod bosses, iconic mobs, signature held items)
        if (slow && ModCrossoverRegistry.tickProximity(puff, level, owner)) {
            return;
        }
        // 9.6 Biome entry reactions (Alex's Caves 6 biomes + notable vanilla biomes)
        if (slow && PuffSceneDialogue.checkBiomeEntry(puff, level, owner)) {
            return;
        }
        // 10. Thunderstorm
        if (puff.cooldownReady(EVENT_THUNDER) && level.isThundering()) {
            fire(puff, EVENT_THUNDER, CD_THUNDER, 3, 100, false, PuffEntity.ANIM_CAT_PANIC,
                    PuffExpression.PANIC_FEAR, PuffExpression.EAR_FOLDED);
            return;
        }
        // 11. Dusk / late night (once per in-game day each)
        long day = level.getDayTime() / 24000L;
        long time = level.getDayTime() % 24000L;
        if (time >= 12000 && time < 13000 && puff.claimDailyEvent(EVENT_DUSK, day)) {
            puff.say(EVENT_DUSK, 2, 110, true, PuffEntity.ANIM_WAVE,
                    PuffExpression.CLOSED_SMILE, PuffExpression.EAR_CURIOUS);
            return;
        }
        if (time >= 13500 && time < 22500 && !owner.isSleeping() && puff.claimDailyEvent(EVENT_NIGHT, day)) {
            puff.say(EVENT_NIGHT, 2, 110, false, PuffEntity.ANIM_STRETCH,
                    PuffExpression.YAWN, PuffExpression.EAR_DROOPING);
            return;
        }
        // 12. Owner standing still nearby for a while: cling
        if (puff.cooldownReady(EVENT_CLING) && puff.distanceToSqr(owner) <= CLING_RANGE * CLING_RANGE
                && puff.ownerStillFor(now) >= CLING_STILL_TICKS && !puff.isOrderedToSit()) {
            fire(puff, EVENT_CLING, CD_CLING, 2, 100, false, PuffEntity.ANIM_CHECK_CUFF,
                    PuffExpression.SHY, PuffExpression.EAR_FOLDED);
        }
    }

    private static void fire(PuffEntity puff, String event, int cooldown, int variants, int duration,
                             boolean toChat, String anim, String expr, String ear) {
        puff.startCooldown(event, cooldown);
        puff.say(event, variants, duration, toChat, anim, expr, ear);
    }

    // ---------------- Forge-driven events ----------------

    /** Owner attacked by a living thing: Puff (if able to fight) steps in. Targeting is done by the goals. */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer owner)) return;
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker) || attacker == owner) return;
        if (attacker instanceof PuffEntity) return;
        for (PuffEntity puff : owner.level().getEntitiesOfClass(PuffEntity.class, owner.getBoundingBox().inflate(16.0),
                p -> p.isOwnedBy(owner) && p.canFight())) {
            if (puff.cooldownReady(EVENT_PROTECT)) {
                puff.startCooldown(EVENT_PROTECT, CD_PROTECT);
                puff.say(EVENT_PROTECT, 3, 90, true, PuffEntity.ANIM_SHOUT,
                        PuffExpression.ANGRY, PuffExpression.EAR_AIRPLANE);
            }
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered()) PENDING_REUNION.add(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING_REUNION.remove(event.getEntity().getUUID());
    }

    /**
     * Owner went through a portal: following Puffs near where they left come along (vanilla pets do not),
     * which is what makes the per-dimension lines ({@link PuffEntity#onDimensionEntered}) reachable.
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer owner)) return;
        ServerLevel from = owner.server.getLevel(event.getFrom());
        ServerLevel to = owner.server.getLevel(event.getTo());
        if (from == null || to == null) return;
        // Collect first: changing dimension removes the entity from the level we are iterating
        java.util.List<PuffEntity> followers = new java.util.ArrayList<>();
        for (Entity e : from.getAllEntities()) {
            // Compare UUIDs: isOwnedBy() resolves the owner in Puff's own level, where the owner no longer is
            if (e instanceof PuffEntity puff && owner.getUUID().equals(puff.getOwnerUUID()) && puff.isAlive()
                    && puff.willFollowAcrossPortal()) {
                followers.add(puff);
            }
        }
        followers.forEach(puff -> puff.followOwnerToDimension(owner, to));
    }

    /** Dimension → lang key of its arrival line, or null if Puff has nothing special to say. */
    @Nullable
    static String dimensionLine(ResourceKey<Level> dim) {
        ResourceLocation id = dim.location();
        if (Level.NETHER.equals(dim)) return "puff.dim.nether";
        if (Level.END.equals(dim)) return "puff.dim.end";
        if ("rats".equals(id.getNamespace()) && "ratlantis".equals(id.getPath())) return "puff.rats.ratlantis";
        return null;
    }

    // ---------------- helpers ----------------

    private static boolean anyNearby(PuffEntity puff, double range, Class<? extends LivingEntity> type) {
        double r2 = range * range;
        return !puff.level().getEntitiesOfClass(type, puff.getBoundingBox().inflate(range),
                e -> e.isAlive() && e.distanceToSqr(puff) <= r2).isEmpty();
    }

    /** Unopened loot container (still has a pending LootTable) or a mob spawner within the radius. */
    private static boolean treasureNearby(ServerLevel level, BlockPos center, int radius) {
        int r2 = radius * radius;
        ChunkPos c = new ChunkPos(center);
        for (int cx = c.x - 1; cx <= c.x + 1; cx++) {
            for (int cz = c.z - 1; cz <= c.z + 1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (e.getKey().distSqr(center) > r2) continue;
                    BlockEntity be = e.getValue();
                    if (be instanceof SpawnerBlockEntity) return true;
                    // lootTable is protected in 1.20.1 (no getter): read it from the saved tag instead
                    if (be instanceof RandomizableContainerBlockEntity rc
                            && rc.saveWithoutMetadata().contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** rats:cheese, rats:blue_cheese, or any item whose registry path mentions cheese. */
    public static boolean isCheese(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.getPath().contains("cheese");
    }

    public static void hearts(ServerLevel level, PuffEntity puff, int count) {
        level.sendParticles(ParticleTypes.HEART, puff.getX(), puff.getY() + puff.getBbHeight() + 0.15, puff.getZ(),
                count, 0.35, 0.15, 0.35, 0.02);
    }
}
