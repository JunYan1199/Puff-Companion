package com.puffcompanion.entity;

import org.jetbrains.annotations.Nullable;

/**
 * How Puff addresses her owner. Pure logic (no client classes) so both sides can use it;
 * the actual words live in the lang files.
 *
 * <ul>
 *   <li>Stage 1: the player's own name</li>
 *   <li>Stage 2/3: {@code puff.call.brother} (哥哥) or {@code puff.call.sister} (姐姐)</li>
 *   <li>Stage 4: {@code puff.call.darling} (亲爱的)</li>
 * </ul>
 */
public final class PuffCalls {
    public static final String KEY_BROTHER = "puff.call.brother";
    public static final String KEY_SISTER = "puff.call.sister";
    public static final String KEY_DARLING = "puff.call.darling";

    private PuffCalls() {}

    /** @return translation key of the call word, or null when Puff should use the player's name. */
    @Nullable
    public static String callKey(int growthStage, String callMode) {
        if (growthStage >= 4) return KEY_DARLING;
        if (growthStage >= 2) return PuffEntity.CALL_SISTER.equals(callMode) ? KEY_SISTER : KEY_BROTHER;
        return null;
    }

    /** Replaces {player} and {call} in an already-translated line. */
    public static String format(String text, String playerName, String call) {
        return text.replace("{player}", playerName).replace("{call}", call);
    }

    /** Key of the brother/sister word regardless of stage, for story lines like chapter 2's first "big brother". */
    public static String siblingKey(String callMode) {
        return PuffEntity.CALL_SISTER.equals(callMode) ? KEY_SISTER : KEY_BROTHER;
    }

    /** Like {@link #format} plus {sibling}. */
    public static String format(String text, String playerName, String call, String sibling) {
        return format(text, playerName, call).replace("{sibling}", sibling);
    }
}
