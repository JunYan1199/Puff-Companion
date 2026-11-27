package com.puffcompanion.compat.crossover;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffExpression;
import com.puffcompanion.story.PuffStoryManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extensible mod crossover registry for Puff Companion.
 *
 * <p>Each external mod registers a {@link ModCrossoverPack} keyed by its modId. All entity/item/biome/advancement
 * checks use string ResourceLocations so optional mods never cause ClassNotFoundException when absent.
 * Adding future mod crossovers only requires registering a new {@link ModCrossoverPack} (plus lang/story JSONs).
 */
@Mod.EventBusSubscriber(modid = PuffCompanion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModCrossoverRegistry {

    public record SpeechCue(String langKey, int variants, int durationTicks, boolean toChat,
                            String anim, String expr, String ear) {}

    public record AdvancementRule(String advancementId, @Nullable String storyKey, @Nullable SpeechCue fallbackBubble) {}

    public record EncounterRule(String entityId, double range, String cooldownKey, int cooldownTicks, SpeechCue cue) {}

    public record BossKillRule(String entityId, @Nullable String storyKey, SpeechCue cue) {}

    public record HeldItemRule(String itemIdContains, String cooldownKey, int cooldownTicks, SpeechCue cue) {}

    public record BiomeRule(String biomeId, SpeechCue entryCue, SpeechCue sceneCue) {}

    public static final class ModCrossoverPack {
        public final String modId;
        public final List<AdvancementRule> advancements = new ArrayList<>();
        public final List<EncounterRule> encounters = new ArrayList<>();
        public final List<BossKillRule> bossKills = new ArrayList<>();
        public final List<HeldItemRule> heldItems = new ArrayList<>();
        public final Map<String, BiomeRule> biomes = new LinkedHashMap<>();

        public ModCrossoverPack(String modId) {
            this.modId = modId;
        }

        public ModCrossoverPack onAdvancement(String advId, @Nullable String storyKey, @Nullable SpeechCue bubble) {
            this.advancements.add(new AdvancementRule(advId, storyKey, bubble));
            return this;
        }

        public ModCrossoverPack onEncounter(String entityId, double range, int cooldownTicks, SpeechCue cue) {
            this.encounters.add(new EncounterRule(entityId, range, "mod_enc:" + entityId, cooldownTicks, cue));
            return this;
        }

        public ModCrossoverPack onBossKill(String entityId, @Nullable String storyKey, SpeechCue cue) {
            this.bossKills.add(new BossKillRule(entityId, storyKey, cue));
            return this;
        }

        public ModCrossoverPack onHeldItem(String itemIdContains, int cooldownTicks, SpeechCue cue) {
            this.heldItems.add(new HeldItemRule(itemIdContains, "mod_item:" + itemIdContains, cooldownTicks, cue));
            return this;
        }

        public ModCrossoverPack onBiome(String biomeId, SpeechCue entryCue, SpeechCue sceneCue) {
            this.biomes.put(biomeId, new BiomeRule(biomeId, entryCue, sceneCue));
            return this;
        }
    }

    private static final Map<String, ModCrossoverPack> PACKS = new LinkedHashMap<>();
    private static final double EVENT_OWNER_RANGE = 28.0;

    static {
        registerBuiltInPacks();
    }

    private ModCrossoverRegistry() {}

    public static void register(ModCrossoverPack pack) {
        PACKS.put(pack.modId, pack);
    }

    private static boolean isLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    // ---------------- Built-in Crossover Packs ----------------

    private static void registerBuiltInPacks() {
        // 1. Goety (诡厄巫法)
        register(new ModCrossoverPack("goety")
                .onAdvancement("goety:goety/become_lich", "mod_goety_dark_arts",
                        cue("puff.mod.goety.lich", 2, 140, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onAdvancement("goety:goety/craft_arca", "mod_goety_dark_arts",
                        cue("puff.mod.goety.arca", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.WINK, PuffExpression.EAR_CURIOUS))
                .onEncounter("goety:apostle", 24.0, 90 * 20,
                        cue("puff.mod.goety.meet_apostle", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("goety:vizier", 20.0, 90 * 20,
                        cue("puff.mod.goety.meet_vizier", 2, 120, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.ANGRY, PuffExpression.EAR_ALERT))
                .onEncounter("goety:wraith", 14.0, 75 * 20,
                        cue("puff.mod.goety.meet_wraith", 2, 100, false, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_FOLDED))
                .onBossKill("goety:apostle", "mod_goety_dark_arts",
                        cue("puff.mod.goety.kill_apostle", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.STARRY, PuffExpression.EAR_ALERT))
                .onHeldItem("goety:dark_wand", 120 * 20,
                        cue("puff.mod.goety.hold_wand", 2, 110, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.WINK, PuffExpression.EAR_CURIOUS))
                .onHeldItem("goety:soul_staff", 120 * 20,
                        cue("puff.mod.goety.hold_wand", 2, 110, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.WINK, PuffExpression.EAR_CURIOUS)));

        // 2. Goety Revelation (诡厄启示录)
        register(new ModCrossoverPack("goety_revelation")
                .onAdvancement("goety_revelation:find_the_frostyard", "mod_goety_revelation",
                        cue("puff.mod.goety_rev.frostyard", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onAdvancement("goety_revelation:read_god_scroll", "mod_goety_revelation",
                        cue("puff.mod.goety_rev.god_scroll", 2, 130, true, PuffEntity.ANIM_SHY, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS))
                .onAdvancement("goety_revelation:kill_apollyon", "mod_goety_revelation",
                        cue("puff.mod.goety_rev.kill_apollyon", 2, 150, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onEncounter("goety_revelation:apollyon", 28.0, 90 * 20,
                        cue("puff.mod.goety_rev.meet_apollyon", 2, 140, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("goety_revelation:ice_hermit", 22.0, 90 * 20,
                        cue("puff.mod.goety_rev.meet_ice_hermit", 2, 120, true, PuffEntity.ANIM_ALERT, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onBossKill("goety_revelation:apollyon", "mod_goety_revelation",
                        cue("puff.mod.goety_rev.kill_apollyon", 2, 150, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT)));

        // 3. Iron's Spells 'n Spellbooks (铁魔法)
        register(new ModCrossoverPack("irons_spellbooks")
                .onAdvancement("irons_spellbooks:irons_spellbooks/spell_book_equip", "mod_irons_arcane",
                        cue("puff.mod.irons.spellbook", 2, 120, true, PuffEntity.ANIM_SPELLCAST, PuffExpression.STARRY, PuffExpression.EAR_ALERT))
                .onAdvancement("irons_spellbooks:irons_spellbooks/enter_catacombs", "mod_irons_arcane",
                        cue("puff.mod.irons.catacombs", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onAdvancement("irons_spellbooks:irons_spellbooks/spell_book_dead_king", "mod_irons_arcane",
                        cue("puff.mod.irons.dead_king_book", 2, 130, true, PuffEntity.ANIM_WAVE, PuffExpression.STARRY, PuffExpression.EAR_ALERT))
                .onEncounter("irons_spellbooks:dead_king", 24.0, 90 * 20,
                        cue("puff.mod.irons.meet_dead_king", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("irons_spellbooks:pyromancer", 18.0, 80 * 20,
                        cue("puff.mod.irons.meet_mage", 2, 110, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.ANGRY, PuffExpression.EAR_ALERT))
                .onEncounter("irons_spellbooks:cryomancer", 18.0, 80 * 20,
                        cue("puff.mod.irons.meet_mage", 2, 110, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.ANGRY, PuffExpression.EAR_ALERT))
                .onEncounter("irons_spellbooks:archevoker", 18.0, 80 * 20,
                        cue("puff.mod.irons.meet_mage", 2, 110, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.ANGRY, PuffExpression.EAR_ALERT))
                .onBossKill("irons_spellbooks:dead_king", "mod_irons_arcane",
                        cue("puff.mod.irons.kill_dead_king", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onHeldItem("irons_spellbooks:blood_staff", 120 * 20,
                        cue("puff.mod.irons.hold_staff", 2, 110, false, PuffEntity.ANIM_SPELLCAST, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS))
                .onHeldItem("irons_spellbooks:graybeard_staff", 120 * 20,
                        cue("puff.mod.irons.hold_staff", 2, 110, false, PuffEntity.ANIM_SPELLCAST, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS)));

        // 4. TravelOptics (旅人光学 / T.O Magic 'n Extras)
        register(new ModCrossoverPack("traveloptics")
                .onAdvancement("traveloptics:discover_echo_shrine", "mod_traveloptics_echo",
                        cue("puff.mod.to.echo_shrine", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS))
                .onAdvancement("traveloptics:free_the_nightwarden", "mod_traveloptics_echo",
                        cue("puff.mod.to.nightwarden", 2, 140, true, PuffEntity.ANIM_ALERT, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onAdvancement("traveloptics:access_lab_computer", null,
                        cue("puff.mod.to.lab_computer", 2, 120, true, PuffEntity.ANIM_CHECK_CUFF, PuffExpression.WINK, PuffExpression.EAR_CURIOUS))
                .onEncounter("traveloptics:the_nightwarden", 26.0, 90 * 20,
                        cue("puff.mod.to.meet_nightwarden", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onBossKill("traveloptics:the_nightwarden", "mod_traveloptics_echo",
                        cue("puff.mod.to.kill_nightwarden", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onHeldItem("traveloptics:", 140 * 20,
                        cue("puff.mod.to.hold_relic", 2, 110, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS)));

        // 5. Alex's Caves (Alex 的洞穴)
        register(new ModCrossoverPack("alexscaves")
                .onAdvancement("alexscaves:alexscaves/cave_book", "mod_alexscaves_expedition",
                        cue("puff.mod.ac.cave_book", 2, 120, true, PuffEntity.ANIM_WAVE, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS))
                .onBiome("alexscaves:magnetic_caves",
                        cue("puff.mod.ac.biome.magnetic.enter", 2, 120, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT),
                        cue("puff.mod.ac.biome.magnetic.scene", 2, 120, false, PuffEntity.ANIM_CHECK_CUFF, PuffExpression.WINK, PuffExpression.EAR_CURIOUS))
                .onBiome("alexscaves:primordial_caves",
                        cue("puff.mod.ac.biome.primordial.enter", 2, 120, true, PuffEntity.ANIM_ALERT, PuffExpression.STARRY, PuffExpression.EAR_ALERT),
                        cue("puff.mod.ac.biome.primordial.scene", 2, 120, false, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_CURIOUS))
                .onBiome("alexscaves:toxic_caves",
                        cue("puff.mod.ac.biome.toxic.enter", 2, 120, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.DISGUST, PuffExpression.EAR_AIRPLANE),
                        cue("puff.mod.ac.biome.toxic.scene", 2, 120, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.SULK, PuffExpression.EAR_DROOPING))
                .onBiome("alexscaves:abyssal_chasm",
                        cue("puff.mod.ac.biome.abyssal.enter", 2, 120, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.SURPRISED, PuffExpression.EAR_FOLDED),
                        cue("puff.mod.ac.biome.abyssal.scene", 2, 120, false, PuffEntity.ANIM_SHY, PuffExpression.TEARY, PuffExpression.EAR_FOLDED))
                .onBiome("alexscaves:forlorn_hollows",
                        cue("puff.mod.ac.biome.forlorn.enter", 2, 120, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE),
                        cue("puff.mod.ac.biome.forlorn.scene", 2, 120, false, PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_FOLDED))
                .onBiome("alexscaves:candy_cavity",
                        cue("puff.mod.ac.biome.candy.enter", 2, 130, true, PuffEntity.ANIM_EAT_CHEESE, PuffExpression.CHEESE_DROOL, PuffExpression.EAR_ALERT),
                        cue("puff.mod.ac.biome.candy.scene", 2, 120, false, PuffEntity.ANIM_WAVE, PuffExpression.BLISS_BOGGLE, PuffExpression.EAR_ALERT))
                .onEncounter("alexscaves:tremorsaurus", 22.0, 90 * 20,
                        cue("puff.mod.ac.meet_dino", 2, 120, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onEncounter("alexscaves:hullbreaker", 26.0, 90 * 20,
                        cue("puff.mod.ac.meet_hullbreaker", 2, 130, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("alexscaves:forsaken", 22.0, 90 * 20,
                        cue("puff.mod.ac.meet_forsaken", 2, 130, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("alexscaves:nucleeper", 16.0, 60 * 20,
                        cue("puff.mod.ac.meet_nucleeper", 2, 110, true, PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("alexscaves:gingerbread_man", 14.0, 80 * 20,
                        cue("puff.mod.ac.meet_candy_mob", 2, 110, false, PuffEntity.ANIM_EAT_CHEESE, PuffExpression.CHEESE_DROOL, PuffExpression.EAR_ALERT))
                .onHeldItem("alexscaves:nuclear_bomb", 90 * 20,
                        cue("puff.mod.ac.hold_nuke", 2, 120, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE)));

        // 6. L_Ender's Cataclysm (灾变)
        register(new ModCrossoverPack("cataclysm")
                .onAdvancement("cataclysm:find_burning_arena", null,
                        cue("puff.mod.cataclysm.find_arena", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onAdvancement("cataclysm:find_sunken_city", null,
                        cue("puff.mod.cataclysm.find_sunken", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS))
                .onAdvancement("cataclysm:find_ancient_factory", null,
                        cue("puff.mod.cataclysm.find_factory", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.WINK, PuffExpression.EAR_CURIOUS))
                .onAdvancement("cataclysm:find_cursed_pyramid", null,
                        cue("puff.mod.cataclysm.find_pyramid", 2, 130, true, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onAdvancement("cataclysm:find_frosted_prison", null,
                        cue("puff.mod.cataclysm.find_prison", 2, 130, true, PuffEntity.ANIM_SHY, PuffExpression.PANIC_FEAR, PuffExpression.EAR_FOLDED))
                .onAdvancement("cataclysm:kill_ignis", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onAdvancement("cataclysm:kill_leviathan", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onAdvancement("cataclysm:kill_harbinger", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onAdvancement("cataclysm:kill_maledictus", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onAdvancement("cataclysm:kill_scylla", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onEncounter("cataclysm:ignis", 26.0, 90 * 20,
                        cue("puff.mod.cataclysm.meet_ignis", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.ANGRY, PuffExpression.EAR_AIRPLANE))
                .onEncounter("cataclysm:the_leviathan", 28.0, 90 * 20,
                        cue("puff.mod.cataclysm.meet_leviathan", 2, 130, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("cataclysm:the_harbinger", 26.0, 90 * 20,
                        cue("puff.mod.cataclysm.meet_harbinger", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onEncounter("cataclysm:maledictus", 26.0, 90 * 20,
                        cue("puff.mod.cataclysm.meet_maledictus", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE))
                .onEncounter("cataclysm:ancient_remnant", 26.0, 90 * 20,
                        cue("puff.mod.cataclysm.meet_remnant", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onEncounter("cataclysm:scylla", 26.0, 90 * 20,
                        cue("puff.mod.cataclysm.meet_scylla", 2, 130, true, PuffEntity.ANIM_ALERT, PuffExpression.SURPRISED, PuffExpression.EAR_ALERT))
                .onBossKill("cataclysm:ignis", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onBossKill("cataclysm:the_leviathan", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onBossKill("cataclysm:the_harbinger", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onBossKill("cataclysm:maledictus", "mod_cataclysm_trial",
                        cue("puff.mod.cataclysm.kill_boss", 2, 140, true, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT))
                .onHeldItem("cataclysm:the_incinerator", 140 * 20,
                        cue("puff.mod.cataclysm.hold_weapon", 2, 110, false, PuffEntity.ANIM_WAVE, PuffExpression.STARRY, PuffExpression.EAR_ALERT))
                .onHeldItem("cataclysm:tidal_claws", 140 * 20,
                        cue("puff.mod.cataclysm.hold_weapon", 2, 110, false, PuffEntity.ANIM_WAVE, PuffExpression.STARRY, PuffExpression.EAR_ALERT))
                .onHeldItem("cataclysm:soul_render", 140 * 20,
                        cue("puff.mod.cataclysm.hold_weapon", 2, 110, false, PuffEntity.ANIM_WAVE, PuffExpression.STARRY, PuffExpression.EAR_ALERT)));
    }

    private static SpeechCue cue(String key, int variants, int duration, boolean toChat,
                                 String anim, String expr, String ear) {
        return new SpeechCue(key, variants, duration, toChat, anim, expr, ear);
    }

    // ---------------- Runtime Queries (called by PuffEventTriggers & PuffSceneDialogue) ----------------

    @Nullable
    public static BiomeRule findBiomeRule(String biomeId) {
        for (ModCrossoverPack pack : PACKS.values()) {
            if (!isLoaded(pack.modId)) continue;
            BiomeRule rule = pack.biomes.get(biomeId);
            if (rule != null) return rule;
        }
        return null;
    }

    /** Slow-tick proximity check for original non-advancement mod encounters & signature held items. */
    public static boolean tickProximity(PuffEntity puff, ServerLevel level, Player owner) {
        // 1. Nearby mod bosses / iconic mobs
        List<Entity> nearby = level.getEntities(puff, puff.getBoundingBox().inflate(28.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != puff && e != owner);
        if (!nearby.isEmpty()) {
            for (ModCrossoverPack pack : PACKS.values()) {
                if (!isLoaded(pack.modId) || pack.encounters.isEmpty()) continue;
                for (EncounterRule rule : pack.encounters) {
                    if (!puff.isCooldownReady(rule.cooldownKey())) continue;
                    double r2 = rule.range() * rule.range();
                    for (Entity e : nearby) {
                        if (e.distanceToSqr(puff) > r2) continue;
                        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                        if (id != null && id.toString().equals(rule.entityId())) {
                            puff.markCooldown(rule.cooldownKey(), rule.cooldownTicks());
                            speakCue(puff, rule.cue());
                            return true;
                        }
                    }
                }
            }
        }

        // 2. Owner holding a signature mod item nearby
        if (puff.distanceToSqr(owner) <= 10.0 * 10.0) {
            String mainId = itemId(owner.getMainHandItem());
            String offId = itemId(owner.getOffhandItem());
            for (ModCrossoverPack pack : PACKS.values()) {
                if (!isLoaded(pack.modId) || pack.heldItems.isEmpty()) continue;
                for (HeldItemRule rule : pack.heldItems) {
                    if (!puff.isCooldownReady(rule.cooldownKey())) continue;
                    if ((mainId != null && mainId.contains(rule.itemIdContains()))
                            || (offId != null && offId.contains(rule.itemIdContains()))) {
                        puff.markCooldown(rule.cooldownKey(), rule.cooldownTicks());
                        speakCue(puff, rule.cue());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Scene-chat helper: returns a cue if a crossover mob is right in front of Puff. */
    @Nullable
    public static SpeechCue findNearbyEncounterForScene(PuffEntity puff, ServerLevel level) {
        List<Entity> nearby = level.getEntities(puff, puff.getBoundingBox().inflate(24.0),
                e -> e instanceof LivingEntity && e.isAlive() && e != puff);
        for (ModCrossoverPack pack : PACKS.values()) {
            if (!isLoaded(pack.modId)) continue;
            for (EncounterRule rule : pack.encounters) {
                for (Entity e : nearby) {
                    ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                    if (id != null && id.toString().equals(rule.entityId())) {
                        return rule.cue();
                    }
                }
            }
        }
        return null;
    }

    public static void speakCue(PuffEntity puff, SpeechCue cue) {
        puff.say(cue.langKey(), cue.variants(), cue.durationTicks(), cue.toChat(),
                cue.anim(), cue.expr(), cue.ear());
    }

    @Nullable
    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    @Nullable
    private static PuffEntity findNearOwnedPuff(ServerPlayer player) {
        List<PuffEntity> list = player.level().getEntitiesOfClass(PuffEntity.class,
                player.getBoundingBox().inflate(EVENT_OWNER_RANGE),
                p -> p.isAlive() && p.isOwnedBy(player));
        return list.isEmpty() ? null : list.get(0);
    }

    // ---------------- Forge Event Hooks ----------------

    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ResourceLocation advId = event.getAdvancement().getId();
        ModCrossoverPack pack = PACKS.get(advId.getNamespace());
        if (pack == null || !isLoaded(pack.modId)) return;

        String fullId = advId.toString();
        for (AdvancementRule rule : pack.advancements) {
            if (!rule.advancementId().equals(fullId)) continue;
            PuffEntity puff = findNearOwnedPuff(player);
            if (puff == null) return;
            puff.addStoryFlag("mod_seen_" + pack.modId);
            if (rule.storyKey() != null && PuffStoryManager.tryAutoStartCrossover(player, puff, rule.storyKey())) {
                return;
            }
            if (rule.fallbackBubble() != null) {
                speakCue(puff, rule.fallbackBubble());
            }
            return;
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType());
        if (id == null) return;
        ModCrossoverPack pack = PACKS.get(id.getNamespace());
        if (pack == null || !isLoaded(pack.modId)) return;

        String fullId = id.toString();
        BossKillRule matched = null;
        for (BossKillRule rule : pack.bossKills) {
            if (rule.entityId().equals(fullId)) {
                matched = rule;
                break;
            }
        }
        if (matched == null) return;

        ServerPlayer credit = null;
        Entity src = event.getSource().getEntity();
        if (src instanceof ServerPlayer sp) {
            credit = sp;
        } else if (src instanceof PuffEntity p && p.getOwner() instanceof ServerPlayer sp) {
            credit = sp;
        } else if (victim.getKillCredit() instanceof ServerPlayer sp) {
            credit = sp;
        }
        if (credit == null) return;
        PuffEntity puff = findNearOwnedPuff(credit);
        if (puff == null) return;
        puff.addStoryFlag("mod_boss_" + pack.modId);
        if (matched.storyKey() != null && PuffStoryManager.tryAutoStartCrossover(credit, puff, matched.storyKey())) {
            return;
        }
        speakCue(puff, matched.cue());
    }
}
