package com.puffcompanion.entity;

import com.puffcompanion.PuffCompanion;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry of Puff's 24 preview-verified facial expressions and 6 mouse-ear mood shapes,
 * plus mapping from Galgame script expression aliases to canonical expression & ear IDs.
 */
public final class PuffExpression {
    // ---- 24 canonical expression IDs (match textures/entity/expressions/face_<id>.png & head morphs) ----
    public static final String NEUTRAL = "neutral";
    public static final String BLINK = "blink";
    public static final String CLOSED = "closed";
    public static final String SMILE = "smile";
    public static final String BEAM = "beam";
    public static final String CLOSED_SMILE = "closed_smile";
    public static final String WINK = "wink";
    public static final String WINK_RIGHT = "wink_right";
    public static final String SHY = "shy";
    public static final String SULK = "sulk";
    public static final String DISDAIN = "disdain";
    public static final String DISGUST = "disgust";
    public static final String TEARY = "teary";
    public static final String SURPRISED = "surprised";
    public static final String STARRY = "starry";
    public static final String ANGRY = "angry";
    public static final String SLEEP_DROOL = "sleep_drool";
    public static final String YAWN = "yawn";
    public static final String OVERHEAT_SHY = "overheat_shy";
    public static final String SQUEEZE_SHY = "squeeze_shy";
    public static final String CHEESE_DROOL = "cheese_drool";
    public static final String PANIC_FEAR = "panic_fear";
    public static final String HUNGRY_DRAINED = "hungry_drained";
    public static final String BLISS_BOGGLE = "bliss_boggle";

    // ---- 6 canonical ear shapes (rest = base mesh; others match mouse_girl_ears.morphs.bin) ----
    public static final String EAR_REST = "rest";
    public static final String EAR_ALERT = "alert";
    public static final String EAR_DROOPING = "drooping";
    public static final String EAR_AIRPLANE = "airplane";
    public static final String EAR_FOLDED = "folded";
    public static final String EAR_CURIOUS = "curious";

    public static final Set<String> VALID_EXPRESSIONS = Set.of(
            NEUTRAL, BLINK, CLOSED, SMILE, BEAM, CLOSED_SMILE, WINK, WINK_RIGHT,
            SHY, SULK, DISDAIN, DISGUST, TEARY, SURPRISED, STARRY, ANGRY,
            SLEEP_DROOL, YAWN, OVERHEAT_SHY, SQUEEZE_SHY, CHEESE_DROOL,
            PANIC_FEAR, HUNGRY_DRAINED, BLISS_BOGGLE
    );

    public static final Set<String> VALID_EARS = Set.of(
            EAR_REST, EAR_ALERT, EAR_DROOPING, EAR_AIRPLANE, EAR_FOLDED, EAR_CURIOUS
    );

    private static final Map<String, ResourceLocation> FACE_TEXTURES = new HashMap<>();

    static {
        for (String id : VALID_EXPRESSIONS) {
            FACE_TEXTURES.put(id, new ResourceLocation(PuffCompanion.MODID,
                    "textures/entity/expressions/face_" + id + ".png"));
        }
    }

    private PuffExpression() {}

    /** Normalizes a story/script expression name or alias into one of the 24 canonical expression IDs. */
    public static String normalizeExpression(String raw) {
        if (raw == null || raw.isEmpty()) return NEUTRAL;
        String key = raw.toLowerCase(Locale.ROOT).trim();
        if (VALID_EXPRESSIONS.contains(key)) return key;
        return switch (key) {
            case "happy", "joy", "cheerful" -> BEAM;
            case "gentle", "warm", "soft" -> CLOSED_SMILE;
            case "flustered", "blush", "embarrassed" -> OVERHEAT_SHY;
            case "excited", "sparkle", "proud" -> STARRY;
            case "scared", "panic", "fear", "afraid" -> PANIC_FEAR;
            case "sad", "cry", "crying", "moved" -> TEARY;
            case "hungry", "tired", "drained" -> HUNGRY_DRAINED;
            case "hungry_cheese", "drool", "cheese" -> CHEESE_DROOL;
            case "bliss", "pet", "happy_pet" -> BLISS_BOGGLE;
            case "sleep", "sleepy" -> SLEEP_DROOL;
            case "curious", "think", "thinking", "wonder" -> WINK;
            case "serious", "determined", "battle" -> ANGRY;
            case "pout", "grumpy" -> SULK;
            default -> SMILE;
        };
    }

    /** Suggests the most fitting ear mood shape for a given expression ID. */
    public static String defaultEarFor(String expr) {
        String norm = normalizeExpression(expr);
        return switch (norm) {
            case SURPRISED, STARRY, CHEESE_DROOL, BEAM -> EAR_ALERT;
            case TEARY, SULK, HUNGRY_DRAINED, YAWN -> EAR_DROOPING;
            case PANIC_FEAR, ANGRY, DISGUST, DISDAIN -> EAR_AIRPLANE;
            case SHY, OVERHEAT_SHY, SQUEEZE_SHY, BLISS_BOGGLE, SLEEP_DROOL, CLOSED -> EAR_FOLDED;
            case WINK, WINK_RIGHT, SMILE, CLOSED_SMILE -> EAR_CURIOUS;
            default -> EAR_REST;
        };
    }

    public static String normalizeEar(String raw) {
        if (raw == null || raw.isEmpty()) return EAR_REST;
        String key = raw.toLowerCase(Locale.ROOT).trim();
        return VALID_EARS.contains(key) ? key : EAR_REST;
    }

    public static ResourceLocation faceTexture(String expr) {
        return FACE_TEXTURES.getOrDefault(normalizeExpression(expr), FACE_TEXTURES.get(NEUTRAL));
    }
}
