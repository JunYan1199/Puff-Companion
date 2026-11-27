package com.puffcompanion.client;

import com.puffcompanion.PuffCompanion;
import com.puffcompanion.client.ai.PuffAiConfig;
import com.puffcompanion.client.gui.PuffInteractionScreen;
import com.puffcompanion.entity.PuffEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Client-side voiceover player for Puff's Japanese voice pack (bubbles, interactions, and Galgame stories).
 * Ensures only one Puff voice clip plays at a time and updates volume dynamically from {@link PuffAiConfig#voiceVolume}.
 */
public final class PuffVoicePlayer {
    private static final Set<String> SISTER_VARIANT_KEYS = Set.of(
            "puff.story.chapter2.pat.1",
            "puff.story.chapter2.cheese.0",
            "puff.story.chapter2.outro.0"
    );

    @Nullable
    private static PuffVoiceInstance currentVoice;

    private PuffVoicePlayer() {}

    /** Plays a voice line triggered by a world/interaction speech bubble. */
    public static void playBubble(@Nullable PuffEntity puff, @Nullable String langKey) {
        playInternal(puff, langKey, false);
    }

    /** Plays a voice line inside the Galgame story screen (centered, unattenuated). */
    public static void playStory(@Nullable PuffEntity puff, @Nullable String langKey) {
        playInternal(puff, langKey, true);
    }

    private static void playInternal(@Nullable PuffEntity puff, @Nullable String langKey, boolean storyMode) {
        if (langKey == null || langKey.isEmpty()) return;
        float vol = PuffAiConfig.get().voiceVolume;
        if (vol <= 0.001F) {
            stop();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        String resolvedKey = langKey.toLowerCase(Locale.ROOT);
        if (puff != null && PuffEntity.CALL_SISTER.equals(puff.getCallMode())
                && SISTER_VARIANT_KEYS.contains(resolvedKey)) {
            resolvedKey = resolvedKey + ".sister";
        }

        ResourceLocation soundId = ResourceLocation.tryBuild(PuffCompanion.MODID, "voice." + resolvedKey);
        if (soundId == null || mc.getSoundManager().getSoundEvent(soundId) == null) {
            return;
        }

        stop();
        PuffVoiceInstance inst = new PuffVoiceInstance(soundId, puff, storyMode, vol);
        currentVoice = inst;
        mc.getSoundManager().play(inst);
    }

    /** Stops any currently playing Puff voice line immediately. */
    public static void stop() {
        if (currentVoice != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.getSoundManager().stop(currentVoice);
            }
            currentVoice.forceStop();
            currentVoice = null;
        }
    }

    /** Returns true while Puff's current voice line is still actively playing. */
    public static boolean isPlaying() {
        if (currentVoice == null || currentVoice.isStopped()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getSoundManager().isActive(currentVoice);
    }

    private static final class PuffVoiceInstance extends AbstractTickableSoundInstance {
        @Nullable
        private final PuffEntity puff;
        private final boolean storyMode;

        PuffVoiceInstance(ResourceLocation soundId, @Nullable PuffEntity puff, boolean storyMode, float initialVolume) {
            super(SoundEvent.createVariableRangeEvent(soundId), SoundSource.VOICE, SoundInstance.createUnseededRandom());
            this.puff = puff;
            this.storyMode = storyMode;
            this.looping = false;
            this.delay = 0;
            this.pitch = 1.0F;
            this.volume = Math.max(0.01F, initialVolume);

            Minecraft mc = Minecraft.getInstance();
            boolean unattenuated = storyMode
                    || puff == null
                    || (mc.screen instanceof PuffInteractionScreen)
                    || (mc.player != null && (puff.getVehicle() == mc.player || puff.distanceToSqr(mc.player) <= 16.0));

            if (unattenuated) {
                this.relative = true;
                this.attenuation = Attenuation.NONE;
                this.x = 0.0;
                this.y = 0.0;
                this.z = 0.0;
            } else {
                this.relative = false;
                this.attenuation = Attenuation.LINEAR;
                this.x = puff.getX();
                this.y = puff.getEyeY();
                this.z = puff.getZ();
            }
        }

        void forceStop() {
            this.stop();
        }

        @Override
        public void tick() {
            float cfgVol = PuffAiConfig.get().voiceVolume;
            if (cfgVol <= 0.001F) {
                this.stop();
                return;
            }
            this.volume = cfgVol;

            if (!this.storyMode && this.puff != null) {
                if (!this.puff.isAlive() || this.puff.isRemoved()) {
                    this.stop();
                    return;
                }
                if (!this.relative) {
                    this.x = this.puff.getX();
                    this.y = this.puff.getEyeY();
                    this.z = this.puff.getZ();
                }
            }
        }
    }
}
