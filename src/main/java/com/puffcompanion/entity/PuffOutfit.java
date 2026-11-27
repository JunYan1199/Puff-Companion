package com.puffcompanion.entity;

import org.jetbrains.annotations.Nullable;

/** The five outfits. Bit index = position in the synced unlock mask. Models arrive in stage 4. */
public enum PuffOutfit {
    EARLY("early"),
    MID("mid"),
    LATE("late"),
    SWIM("swim"),
    WEDDING("wedding");

    private final String id;

    PuffOutfit(String id) {
        this.id = id;
    }

    public String id() { return this.id; }
    public int bit() { return 1 << this.ordinal(); }
    public String nameKey() { return "puff.outfit." + this.id + ".name"; }
    public String shortKey() { return "puff.outfit." + this.id + ".short"; }
    public String lineBaseKey() { return "puff.outfit." + this.id; }

    /** Unlocked from the start: early adventure wear + swimsuit (current model). */
    public static int defaultMask() {
        return EARLY.bit() | SWIM.bit();
    }

    @Nullable
    public static PuffOutfit byId(String id) {
        for (PuffOutfit o : values()) if (o.id.equals(id)) return o;
        return null;
    }
}
