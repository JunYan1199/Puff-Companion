package com.puffcompanion.entity;

import com.puffcompanion.compat.RatsCompat;
import com.puffcompanion.compat.crossover.ModCrossoverRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dynamic situational dialogue ("聊聊眼前的事") and biome-entry bubble triggers.
 * Inspects nearby mod bosses, modded biomes (e.g. Alex's Caves), dimensions, weather,
 * time of day, vanilla biomes, and local environment, always pairing with expressions & ear shapes.
 */
public final class PuffSceneDialogue {
    private static final int CD_BIOME_ENTRY = 75 * 20;

    private PuffSceneDialogue() {}

    /** Triggered when the player clicks "聊聊眼前的事" in the Topics menu. */
    public static void speakAboutSurroundings(PuffEntity puff, ServerPlayer player) {
        if (!(puff.level() instanceof ServerLevel level)) return;

        // 1. Nearby mod boss or iconic crossover mob
        ModCrossoverRegistry.SpeechCue modMob = ModCrossoverRegistry.findNearbyEncounterForScene(puff, level);
        if (modMob != null) {
            ModCrossoverRegistry.speakCue(puff, modMob);
            return;
        }

        // 2. Cat nearby: immediate panic commentary!
        if (!level.getEntitiesOfClass(Cat.class, puff.getBoundingBox().inflate(10.0)).isEmpty()
                || !level.getEntitiesOfClass(Ocelot.class, puff.getBoundingBox().inflate(10.0)).isEmpty()) {
            puff.say("puff.scene.cat_near", 2, 110, false,
                    PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE);
            return;
        }

        // 3. Modded biome (e.g. Alex's Caves 6 biomes)
        Holder<Biome> biomeHolder = level.getBiome(puff.blockPosition());
        String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("");
        ModCrossoverRegistry.BiomeRule modBiome = ModCrossoverRegistry.findBiomeRule(biomeId);
        if (modBiome != null) {
            ModCrossoverRegistry.speakCue(puff, modBiome.sceneCue());
            return;
        }

        // 4. Special dimension
        ResourceLocation dimId = level.dimension().location();
        if (Level.NETHER.equals(level.dimension())) {
            puff.say("puff.scene.nether", 2, 120, false,
                    PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.OVERHEAT_SHY, PuffExpression.EAR_DROOPING);
            return;
        }
        if (Level.END.equals(level.dimension())) {
            puff.say("puff.scene.end", 2, 120, false,
                    PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS);
            return;
        }
        if ("rats".equals(dimId.getNamespace()) && "ratlantis".equals(dimId.getPath())) {
            puff.say("puff.scene.ratlantis", 2, 120, false,
                    PuffEntity.ANIM_WAVE, PuffExpression.CHEESE_DROOL, PuffExpression.EAR_ALERT);
            return;
        }

        // 5. Nearby campfire / cozy home nest
        if (hasBlockNearby(level, puff.blockPosition(), 6, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE)) {
            puff.say("puff.scene.campfire", 2, 120, false,
                    PuffEntity.ANIM_GROUND_REST, PuffExpression.CLOSED_SMILE, PuffExpression.EAR_FOLDED);
            return;
        }
        if (puff.isHomeMode()) {
            puff.say("puff.scene.home", 2, 120, false,
                    PuffEntity.ANIM_STRETCH, PuffExpression.BLISS_BOGGLE, PuffExpression.EAR_FOLDED);
            return;
        }

        // 6. Weather
        if (level.isThundering()) {
            puff.say("puff.scene.thunder", 2, 120, false,
                    PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_FOLDED);
            return;
        }
        if (level.isRainingAt(puff.blockPosition().above())) {
            puff.say("puff.scene.rain", 2, 120, false,
                    PuffEntity.ANIM_CHECK_CUFF, PuffExpression.SULK, PuffExpression.EAR_DROOPING);
            return;
        }

        // 7. Notable vanilla biomes
        if (biomeId.contains("cherry_grove")) {
            puff.say("puff.scene.cherry", 2, 120, false,
                    PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT);
            return;
        }
        if (biomeId.contains("lush_caves")) {
            puff.say("puff.scene.lush_caves", 2, 120, false,
                    PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS);
            return;
        }
        if (biomeId.contains("deep_dark")) {
            puff.say("puff.scene.deep_dark", 2, 120, false,
                    PuffEntity.ANIM_ALERT, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE);
            return;
        }
        if (biomeHolder.is(BiomeTags.IS_BEACH) || biomeHolder.is(BiomeTags.IS_OCEAN)) {
            puff.say("puff.scene.beach", 2, 120, false,
                    PuffEntity.ANIM_STRETCH, PuffExpression.BEAM, PuffExpression.EAR_CURIOUS);
            return;
        }
        if (biomeHolder.value().coldEnoughToSnow(puff.blockPosition())) {
            puff.say("puff.scene.snow", 2, 120, false,
                    PuffEntity.ANIM_SHY, PuffExpression.SQUEEZE_SHY, PuffExpression.EAR_FOLDED);
            return;
        }
        if (biomeId.contains("desert") || biomeHolder.is(BiomeTags.IS_BADLANDS)) {
            puff.say("puff.scene.desert", 2, 120, false,
                    PuffEntity.ANIM_CHECK_CUFF, PuffExpression.HUNGRY_DRAINED, PuffExpression.EAR_DROOPING);
            return;
        }
        if (!level.canSeeSky(puff.blockPosition()) && puff.getY() < 52) {
            puff.say("puff.scene.underground", 2, 120, false,
                    PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.WINK, PuffExpression.EAR_ALERT);
            return;
        }

        // 8. Time of day fallback
        long time = level.getDayTime() % 24000L;
        if (time >= 13000 && time < 22500) {
            puff.say("puff.scene.night", 2, 120, false,
                    PuffEntity.ANIM_SHY, PuffExpression.YAWN, PuffExpression.EAR_DROOPING);
        } else if (time >= 11500 && time < 13000) {
            puff.say("puff.scene.dusk", 2, 120, false,
                    PuffEntity.ANIM_WAVE, PuffExpression.CLOSED_SMILE, PuffExpression.EAR_CURIOUS);
        } else if (time >= 22500 || time < 1500) {
            puff.say("puff.scene.dawn", 2, 120, false,
                    PuffEntity.ANIM_STRETCH, PuffExpression.SMILE, PuffExpression.EAR_ALERT);
        } else if (RatsCompat.anyRatWorking(puff, 16.0)) {
            puff.say("puff.scene.rats_busy", 2, 120, false,
                    PuffEntity.ANIM_COMMAND_RATS, PuffExpression.BEAM, PuffExpression.EAR_ALERT);
        } else {
            puff.say("puff.scene.day", 2, 120, false,
                    PuffEntity.ANIM_WAVE, PuffExpression.SMILE, PuffExpression.EAR_CURIOUS);
        }
    }

    /** Slow-tick check: fires a fresh single-sided bubble when Puff enters a new notable biome. */
    public static boolean checkBiomeEntry(PuffEntity puff, ServerLevel level, Player owner) {
        Holder<Biome> holder = level.getBiome(puff.blockPosition());
        String biomeId = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
        if (biomeId.isEmpty()) return false;
        String prev = puff.getLastBiomeId();
        if (prev == null) {
            puff.setLastBiomeId(biomeId);
            return false;
        }
        if (biomeId.equals(prev)) return false;
        puff.setLastBiomeId(biomeId);

        if (!puff.isCooldownReady("biome_entry:" + biomeId)) return false;

        // Modded biome entry (Alex's Caves, etc.)
        ModCrossoverRegistry.BiomeRule modRule = ModCrossoverRegistry.findBiomeRule(biomeId);
        if (modRule != null) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            ModCrossoverRegistry.speakCue(puff, modRule.entryCue());
            return true;
        }

        // Notable vanilla biomes
        if (biomeId.contains("cherry_grove")) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            puff.say("puff.biome.enter.cherry", 2, 120, false, PuffEntity.ANIM_WAVE, PuffExpression.BEAM, PuffExpression.EAR_ALERT);
            return true;
        }
        if (biomeId.contains("deep_dark")) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            puff.say("puff.biome.enter.deep_dark", 2, 120, true, PuffEntity.ANIM_CAT_PANIC, PuffExpression.PANIC_FEAR, PuffExpression.EAR_AIRPLANE);
            return true;
        }
        if (biomeId.contains("lush_caves")) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            puff.say("puff.biome.enter.lush_caves", 2, 120, false, PuffEntity.ANIM_ALERT_SNIFF, PuffExpression.STARRY, PuffExpression.EAR_CURIOUS);
            return true;
        }
        if (holder.is(BiomeTags.IS_BEACH)) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            puff.say("puff.biome.enter.beach", 2, 110, false, PuffEntity.ANIM_STRETCH, PuffExpression.BEAM, PuffExpression.EAR_CURIOUS);
            return true;
        }
        if (holder.value().coldEnoughToSnow(puff.blockPosition())) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            puff.say("puff.biome.enter.snow", 2, 110, false, PuffEntity.ANIM_SHY, PuffExpression.SQUEEZE_SHY, PuffExpression.EAR_FOLDED);
            return true;
        }
        if (biomeId.contains("desert") || holder.is(BiomeTags.IS_BADLANDS)) {
            puff.markCooldown("biome_entry:" + biomeId, CD_BIOME_ENTRY);
            puff.say("puff.biome.enter.desert", 2, 110, false, PuffEntity.ANIM_CHECK_CUFF, PuffExpression.HUNGRY_DRAINED, PuffExpression.EAR_DROOPING);
            return true;
        }
        return false;
    }

    private static boolean hasBlockNearby(ServerLevel level, BlockPos center, int radius, net.minecraft.world.level.block.Block... targets) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz += 2) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState st = level.getBlockState(cursor);
                    for (var target : targets) {
                        if (st.is(target)) return true;
                    }
                }
            }
        }
        return false;
    }
}
