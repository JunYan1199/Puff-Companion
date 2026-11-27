package com.puffcompanion.entity;

import org.jetbrains.annotations.Nullable;

/** Fixed chat topics of the interaction menu (doc 03, part 2.1). Text lives in the lang files. */
public enum PuffTopic {
    SCENE("scene"),     // Stage 8: dynamic situational dialogue ("聊聊眼前的事")
    SELF("self"),
    RATS("rats"),
    NEST("nest"),       // unlocked after main story chapter 1
    CAT("cat"),         // unlocked after side story 1 (the cat one)
    FUTURE("future");   // unlocked at growth stage 3

    private final String id;

    PuffTopic(String id) {
        this.id = id;
    }

    public String id() { return this.id; }
    public String titleKey() { return "puff.topic." + this.id + ".title"; }
    public String lineBaseKey() { return "puff.topic." + this.id; }
    public String lockedKey() { return "puff.topic." + this.id + ".locked"; }

    public boolean isUnlocked(PuffEntity puff) {
        return switch (this) {
            case SCENE, SELF, RATS -> true;
            case NEST -> puff.hasStoryFlag(PuffEntity.FLAG_STORY_CHAPTER1);
            case CAT -> puff.hasStoryFlag(PuffEntity.FLAG_STORY_SIDE1);
            case FUTURE -> puff.getGrowthStage() >= 3;
        };
    }

    @Nullable
    public static PuffTopic byId(String id) {
        for (PuffTopic t : values()) if (t.id.equals(id)) return t;
        return null;
    }
}
