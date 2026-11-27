package com.puffcompanion.entity;

import org.jetbrains.annotations.Nullable;

/**
 * Puff's own active work / combat mode (independent of the rat squad's per-rat jobs).
 * Controls what Puff prioritizes when she is not ordered to sit, rest, or ride on the owner's shoulder.
 */
public enum PuffWorkMode {
    FOLLOW_GUARD("follow", true, false),
    COMBAT_PATROL("patrol", true, true),
    SPELL_COMBAT("spell", true, true),
    RANGED_COMBAT("ranged", true, true),
    FARM("farm", false, false),
    LUMBER("lumber", false, false),
    FISH("fish", false, false),
    RANCH("ranch", false, false);

    private final String id;
    private final boolean followOwner;
    private final boolean aggressiveCombat;

    PuffWorkMode(String id, boolean followOwner, boolean aggressiveCombat) {
        this.id = id;
        this.followOwner = followOwner;
        this.aggressiveCombat = aggressiveCombat;
    }

    public String id() { return this.id; }
    public boolean shouldFollowOwner() { return this.followOwner; }
    public boolean isAggressiveCombat() { return this.aggressiveCombat; }
    public boolean isSelfWork() { return !this.followOwner; }

    public String langKey() { return "puff.mode." + this.id; }
    public String descKey() { return "puff.mode." + this.id + ".desc"; }

    public PuffWorkMode next() {
        PuffWorkMode[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    public PuffWorkMode prev() {
        PuffWorkMode[] all = values();
        return all[(this.ordinal() + all.length - 1) % all.length];
    }

    @Nullable
    public static PuffWorkMode byId(String id) {
        for (PuffWorkMode m : values()) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }
}
