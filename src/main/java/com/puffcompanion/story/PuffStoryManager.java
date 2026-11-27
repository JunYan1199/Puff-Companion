package com.puffcompanion.story;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.puffcompanion.PuffCompanion;
import com.puffcompanion.compat.RatsCompat;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffEventTriggers;
import com.puffcompanion.entity.PuffOutfit;
import com.puffcompanion.entity.PuffStories;
import com.puffcompanion.network.PuffNetwork;
import com.puffcompanion.network.PuffStoryChoicePacket;
import com.puffcompanion.network.PuffStoryStartPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the local Galgame system: loads scripts from the data pack, decides when each story
 * auto-plays, starts sessions and is the only place where story choices / completion change Puff's state.
 * Clients only report which choice they clicked on which node; anything not matching the session is ignored.
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PuffStoryManager {
    public static final String DIRECTORY = "puff_stories";
    public static final String PROLOGUE_KEY = PuffStories.PROLOGUE.key();

    /** Minimum gap between two automatic story prompts for the same owner (ticks). */
    private static final long AUTO_RETRY_TICKS = 60 * 20;
    private static final double AUTO_TRIGGER_RANGE = 16.0;
    private static final double NEAR_OWNER = 12.0;
    private static final double CHOICE_RANGE = 32.0;
    private static final double GREETING_RANGE = 12.0;

    /** Items accepted as a proposal token (ids verified in the Rats jar). */
    private static final Set<ResourceLocation> PROPOSAL_TOKENS = Set.of(
            new ResourceLocation("minecraft", "diamond"),
            new ResourceLocation("rats", "gem_of_ratlantis"),
            new ResourceLocation("rats", "chunky_cheese_token"));

    private static final Gson GSON = new GsonBuilder().create();

    private static volatile Map<String, PuffStory> stories = Map.of();
    private static volatile Map<String, String> rawJson = Map.of();

    private record Session(String storyKey, int puffId, @Nullable String awaitingNode, boolean replay) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_AUTO_PROMPT = new ConcurrentHashMap<>();

    public enum ProposeResult { STARTED, NOT_READY, NO_TOKEN, ALREADY_MARRIED }

    private PuffStoryManager() {}

    // ---------------- Loading ----------------

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(GSON, DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<String, PuffStory> parsed = new HashMap<>();
            Map<String, String> raw = new HashMap<>();
            files.forEach((loc, json) -> {
                try {
                    PuffStory story = PuffStory.parse(GsonHelper.convertToJsonObject(json, loc.toString()));
                    parsed.put(loc.getPath(), story);
                    raw.put(loc.getPath(), json.toString());
                } catch (Exception e) {
                    PuffCompanion.LOGGER.error("[Puff Companion] Bad story script {}: {}", loc, e.getMessage());
                }
            });
            stories = Map.copyOf(parsed);
            rawJson = Map.copyOf(raw);
            PuffCompanion.LOGGER.info("[Puff Companion] Loaded {} story script(s): {}", parsed.size(), parsed.keySet());
        }
    }

    public static Collection<String> keys() {
        return stories.keySet();
    }

    /** Accepts a file key ("prologue") or a story id ("puff_story_prologue"). */
    @Nullable
    public static String resolveKey(String keyOrId) {
        if (stories.containsKey(keyOrId)) return keyOrId;
        for (Map.Entry<String, PuffStory> e : stories.entrySet()) {
            if (e.getValue().id().equals(keyOrId)) return e.getKey();
        }
        return null;
    }

    @Nullable
    public static PuffStory get(String key) {
        return stories.get(key);
    }

    // ---------------- Sessions ----------------

    /** True while this Puff is actively participating in an open story session with its owner. */
    public static boolean isInStorySession(@Nullable PuffEntity puff) {
        if (puff == null) return false;
        UUID ownerId = puff.getOwnerUUID();
        if (ownerId == null) return false;
        Session s = SESSIONS.get(ownerId);
        return s != null && s.puffId() == puff.getId();
    }

    /** Starts (or replays) a story for the owner. Returns false if the story is unknown. */
    public static boolean start(ServerPlayer player, PuffEntity puff, String key) {
        PuffStory story = stories.get(key);
        String json = rawJson.get(key);
        if (story == null || json == null) return false;
        boolean replay = puff.hasCompletedStory(story.id());
        player.closeContainer();   // e.g. replaying from the maid panel
        SESSIONS.put(player.getUUID(), new Session(key, puff.getId(), story.firstChoiceNode(story.start()), replay));
        PuffNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PuffStoryStartPacket(puff.getId(), key, json));
        return true;
    }

    /**
     * Auto-starts a mod crossover Galgame story once if not yet completed and the player is free.
     * Returns true if the story screen was launched (caller can skip fallback bubble).
     */
    public static boolean tryAutoStartCrossover(ServerPlayer player, PuffEntity puff, String key) {
        PuffStory story = stories.get(key);
        if (story == null) return false;
        if (puff.hasCompletedStory(story.id())) return false;
        if (player.isSpectator() || player.isSleeping() || SESSIONS.containsKey(player.getUUID())) return false;
        long now = puff.level().getGameTime();
        Long last = LAST_AUTO_PROMPT.get(player.getUUID());
        if (last != null && now - last < 20 * 20) return false;
        LAST_AUTO_PROMPT.put(player.getUUID(), now);
        return start(player, puff, key);
    }

    /** Called from Puff's server tick once per second: plays the first story whose conditions are met. */
    public static void autoTrigger(PuffEntity puff) {
        if (!(puff.getOwner() instanceof ServerPlayer owner) || owner.level() != puff.level()) return;
        if (owner.isSpectator() || owner.isSleeping() || puff.distanceToSqr(owner) > AUTO_TRIGGER_RANGE * AUTO_TRIGGER_RANGE) return;
        if (SESSIONS.containsKey(owner.getUUID())) return;
        long now = puff.level().getGameTime();
        Long last = LAST_AUTO_PROMPT.get(owner.getUUID());
        if (last != null && now - last < AUTO_RETRY_TICKS) return;
        String key = pickAutoStory(puff, owner);
        if (key == null || !stories.containsKey(key)) return;
        LAST_AUTO_PROMPT.put(owner.getUUID(), now);
        start(owner, puff, key);
    }

    /** Story conditions, highest priority first. Finale (proposal) and after-morning (wake-up) are not here. */
    @Nullable
    private static String pickAutoStory(PuffEntity puff, ServerPlayer owner) {
        if (!done(puff, PuffStories.PROLOGUE)) return PuffStories.PROLOGUE.key();
        int aff = puff.getAffection();
        boolean near = puff.distanceToSqr(owner) <= NEAR_OWNER * NEAR_OWNER;
        ServerLevel level = (ServerLevel) puff.level();

        if (!done(puff, PuffStories.SIDE1) && aff >= 35 && catNearby(puff, 10.0)) {
            return PuffStories.SIDE1.key();
        }
        if (!done(puff, PuffStories.CHAPTER1) && aff >= 25 && near
                && (!puff.getHoard().isEmpty() || level.getDayTime() / 24000L >= 3)) {
            return PuffStories.CHAPTER1.key();
        }
        if (done(puff, PuffStories.CHAPTER1) && !done(puff, PuffStories.CHAPTER2) && aff >= 50
                && (!RatsCompat.ownedRats(owner, owner.getUUID(), 16.0).isEmpty() || carriesCheese(owner))) {
            return PuffStories.CHAPTER2.key();
        }
        if (done(puff, PuffStories.CHAPTER2) && !done(puff, PuffStories.SIDE2) && aff >= 65
                && level.isDay() && !level.isRaining() && isBeachy(level, puff.blockPosition()) && isBeachy(level, owner.blockPosition())) {
            return PuffStories.SIDE2.key();
        }
        // Side 3: Starlight Campfire (nighttime outdoors, affection >= 45)
        if (done(puff, PuffStories.CHAPTER1) && !done(puff, PuffStories.SIDE3_STARLIGHT) && aff >= 45 && near
                && !level.isDay() && !level.isRaining() && level.canSeeSky(puff.blockPosition())) {
            return PuffStories.SIDE3_STARLIGHT.key();
        }
        // Side 4: Midnight Cheese Feast (after Chapter 1, affection >= 55, holding cheese or cozy home mode)
        if (done(puff, PuffStories.CHAPTER1) && !done(puff, PuffStories.SIDE4_CHEESE_FEAST) && aff >= 55 && near
                && (carriesCheese(owner) || puff.isHomeMode())) {
            return PuffStories.SIDE4_CHEESE_FEAST.key();
        }
        // Side 5: Moonlit Rat Parade (after Chapter 2, affection >= 70, rats nearby or cheese on hand)
        if (done(puff, PuffStories.CHAPTER2) && !done(puff, PuffStories.SIDE5_RAT_PARADE) && aff >= 70 && near
                && (!RatsCompat.ownedRats(owner, owner.getUUID(), 20.0).isEmpty() || carriesCheese(owner))) {
            return PuffStories.SIDE5_RAT_PARADE.key();
        }
        if (done(puff, PuffStories.CHAPTER2) && !done(puff, PuffStories.CHAPTER3) && aff >= 80 && near
                && puff.getStoryFlags().contains(PuffStories.FLAG_VISITED_DANGER_DIM)) {
            return PuffStories.CHAPTER3.key();
        }
        // True Ending E: Twin-Star True Vow (Chapter 3 + >=3 side stories + affection >= 95 + both Adventure & Hearth memories or high bond)
        if (done(puff, PuffStories.CHAPTER3) && !done(puff, PuffStories.ENDING_TRUE_VOW) && aff >= 95 && near
                && puff.getCompletedSideCount() >= 3
                && ((puff.getAdventureRouteScore() >= 2 && puff.getHearthRouteScore() >= 2) || puff.getBondRouteScore() >= 5)) {
            return PuffStories.ENDING_TRUE_VOW.key();
        }
        // Ending D: Little Cheese Queen (Chapter 2 + >=2 side stories or Rat Parade done + affection >= 85 + rats/cheese)
        if (done(puff, PuffStories.CHAPTER2) && !done(puff, PuffStories.ENDING_QUEEN) && aff >= 85 && near
                && (done(puff, PuffStories.SIDE5_RAT_PARADE) || puff.getCompletedSideCount() >= 2)
                && (!RatsCompat.ownedRats(owner, owner.getUUID(), 20.0).isEmpty() || carriesCheese(owner))) {
            return PuffStories.ENDING_QUEEN.key();
        }
        // Ending B: Adventure Ending (Chapter 3 + Adventure route tendency >= Hearth + danger dim or mod bosses)
        if (done(puff, PuffStories.CHAPTER3) && !done(puff, PuffStories.ENDING_ADVENTURE) && aff >= 85 && near
                && puff.getAdventureRouteScore() >= puff.getHearthRouteScore()
                && (puff.getStoryFlags().contains(PuffStories.FLAG_VISITED_DANGER_DIM)
                || puff.getStoryFlags().stream().anyMatch(f -> f.startsWith("mod_boss_")))) {
            return PuffStories.ENDING_ADVENTURE.key();
        }
        // Ending C: Hearth Ending (Chapter 2 + home mode + Hearth route tendency >= 2 or Cheese Feast done)
        if (done(puff, PuffStories.CHAPTER2) && !done(puff, PuffStories.ENDING_HEARTH) && aff >= 80 && near
                && puff.isHomeMode()
                && (puff.getHearthRouteScore() >= 2 || done(puff, PuffStories.SIDE4_CHEESE_FEAST))) {
            return PuffStories.ENDING_HEARTH.key();
        }
        return null;
    }

    private static boolean done(PuffEntity puff, PuffStories.Entry entry) {
        return puff.hasCompletedStory(entry.id());
    }

    private static boolean catNearby(PuffEntity puff, double range) {
        return !puff.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, puff.getBoundingBox().inflate(range),
                e -> (e instanceof Cat || e instanceof Ocelot) && e.isAlive() && e.distanceToSqr(puff) <= range * range).isEmpty();
    }

    private static boolean carriesCheese(Player player) {
        if (PuffEventTriggers.isCheese(player.getOffhandItem())) return true;
        for (ItemStack stack : player.getInventory().items) {
            if (PuffEventTriggers.isCheese(stack)) return true;
        }
        return false;
    }

    private static boolean isBeachy(ServerLevel level, BlockPos pos) {
        var biome = level.getBiome(pos);
        return biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.WARM_OCEAN);
    }

    // ---------------- Proposal (finale) ----------------

    public static boolean isProposalToken(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return !stack.isEmpty() && id != null && PROPOSAL_TOKENS.contains(id);
    }

    /** Stage 3 + affection 100 + a token in the main hand: consume one token and play the finale. */
    public static ProposeResult tryPropose(ServerPlayer player, PuffEntity puff) {
        if (done(puff, PuffStories.FINALE) || puff.getGrowthStage() >= 4) return ProposeResult.ALREADY_MARRIED;
        if (puff.getGrowthStage() != 3 || puff.getAffection() < 100) return ProposeResult.NOT_READY;
        ItemStack hand = player.getMainHandItem();
        if (!isProposalToken(hand)) return ProposeResult.NO_TOKEN;
        if (!start(player, puff, PuffStories.FINALE.key())) return ProposeResult.NOT_READY;
        if (!player.getAbilities().instabuild) hand.shrink(1);
        return ProposeResult.STARTED;
    }

    // ---------------- Choices & completion ----------------

    /** Server-authoritative handling of a client choice ({@code choice >= 0}) or end-of-story report (-1). */
    public static void handleChoice(ServerPlayer player, PuffStoryChoicePacket msg) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.storyKey().equals(msg.storyKey()) || session.puffId() != msg.entityId()) return;
        PuffStory story = stories.get(session.storyKey());
        if (story == null || !(player.level().getEntity(session.puffId()) instanceof PuffEntity puff)
                || !puff.isAlive() || !puff.isOwnedBy(player)
                || puff.distanceToSqr(player) > CHOICE_RANGE * CHOICE_RANGE) {
            SESSIONS.remove(player.getUUID());
            return;
        }

        if (msg.choice() < 0) {
            SESSIONS.remove(player.getUUID());
            if (session.awaitingNode() == null) complete(player, puff, story, session.replay());
            return;
        }

        if (!msg.nodeId().equals(session.awaitingNode())) return;   // out of order / forged
        PuffStory.Node node = story.node(msg.nodeId());
        if (node == null || msg.choice() >= node.choices().size()) return;
        PuffStory.Choice choice = node.choices().get(msg.choice());

        if (!session.replay()) {
            if (choice.affection() != 0) puff.setAffection(puff.getAffection() + choice.affection());
            choice.flags().forEach(puff::addStoryFlag);
        }
        String nextAwaiting = story.firstChoiceNode(choice.next());
        if (nextAwaiting == null) {
            SESSIONS.remove(player.getUUID());
            complete(player, puff, story, session.replay());
        } else {
            SESSIONS.put(player.getUUID(), new Session(session.storyKey(), session.puffId(), nextAwaiting, session.replay()));
        }
    }

    /** Applies the story's settlement exactly once; replays only re-watch. */
    private static void complete(ServerPlayer player, PuffEntity puff, PuffStory story, boolean replay) {
        if (replay) return;
        puff.markStoryCompleted(story.id());
        PuffStory.Reward r = story.onComplete();
        if (r.affection() != 0) puff.setAffection(puff.getAffection() + r.affection());
        r.flags().forEach(puff::addStoryFlag);
        if (r.setStage() > puff.getGrowthStage()) puff.setGrowthStage(r.setStage());
        for (String id : r.unlockOutfits()) {
            PuffOutfit outfit = PuffOutfit.byId(id);
            if (outfit != null) puff.unlockOutfit(outfit);
        }
        if (r.equipOutfit() != null) puff.setCurrentOutfit(r.equipOutfit());
        for (PuffStory.GiveItem give : r.giveItems()) {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(give.item()));
            if (item == null || item == Items.AIR) continue;   // e.g. rats:cheese without Rats
            ItemStack stack = new ItemStack(item, Math.max(1, give.count()));
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
        PuffCompanion.LOGGER.info("[Puff Companion] Story {} completed for Puff {}", story.id(), puff.getUUID());
    }

    // ---------------- Bed / wake-up hooks ----------------

    @Nullable
    private static PuffEntity nearestOwnedPuff(ServerPlayer player, double range) {
        return player.level().getEntitiesOfClass(PuffEntity.class, player.getBoundingBox().inflate(range),
                        p -> p.isAlive() && p.isOwnedBy(player))
                .stream().min(Comparator.comparingDouble(p -> p.distanceToSqr(player))).orElse(null);
    }

    /** Morning: the married epilogue once, otherwise the stage-specific good-morning bubble. */
    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        long time = player.level().getDayTime() % 24000L;
        if (time > 3000) return;   // woken up mid-night by a monster etc.
        PuffEntity puff = nearestOwnedPuff(player, GREETING_RANGE);
        if (puff == null) return;
        if (done(puff, PuffStories.FINALE) && !done(puff, PuffStories.AFTER_MORNING) && stories.containsKey(PuffStories.AFTER_MORNING.key())) {
            start(player, puff, PuffStories.AFTER_MORNING.key());
            return;
        }
        puff.say("puff.greet.morning.stage" + Math.max(1, Math.min(4, puff.getGrowthStage())), 1, 120, true, "");
    }

    /** Getting into bed at night: stage-specific good-night bubble. */
    @SubscribeEvent
    public static void onSleepInBed(PlayerSleepInBedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isDay()) return;
        PuffEntity puff = nearestOwnedPuff(player, GREETING_RANGE);
        if (puff != null) {
            puff.say("puff.greet.night.stage" + Math.max(1, Math.min(4, puff.getGrowthStage())), 1, 120, true, "");
        }
    }

    /** Proposal refusal hint shown in the action bar. */
    public static Component proposeMessage(ProposeResult result) {
        return Component.translatable("message.puffcompanion.propose." + result.name().toLowerCase(java.util.Locale.ROOT));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
        LAST_AUTO_PROMPT.remove(event.getEntity().getUUID());
    }
}
