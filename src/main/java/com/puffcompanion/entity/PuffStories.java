package com.puffcompanion.entity;

import com.puffcompanion.PuffCompanion;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Story & ending registry in bit-mask order (bits 0..20 of Puff's synced "stories done" int mask).
 * Drives both replay unlocks and the Visual Story Tree in the management panel.
 */
public final class PuffStories {
    public enum Category { MAIN, SIDE, ENDING, CROSSOVER }

    public record Entry(String key, String id, Category category, @Nullable String modId, String iconName) {
        public Entry(String key, String id, Category category) {
            this(key, id, category, null, key);
        }
        public Entry(String key, String id, Category category, @Nullable String modId) {
            this(key, id, category, modId,
                    (modId != null && (modId.contains("goety") || modId.contains("irons") || modId.contains("traveloptics")))
                            ? "crossover_magic" : "crossover_expedition");
        }
        public String titleKey() { return "puff.story." + this.key + ".title"; }
        public String hintKey() { return "puff.story." + this.key + ".hint"; }
        public ResourceLocation iconTexture() {
            return new ResourceLocation(PuffCompanion.MODID, "textures/gui/story/" + this.iconName + ".png");
        }
    }

    public static final Entry PROLOGUE = new Entry("prologue", "puff_story_prologue", Category.MAIN);
    public static final Entry CHAPTER1 = new Entry("chapter1", "puff_story_ch1_hoard", Category.MAIN);
    public static final Entry SIDE1 = new Entry("side1_cat", "puff_side_cat_panic", Category.SIDE);
    public static final Entry CHAPTER2 = new Entry("chapter2", "puff_story_ch2_workshop", Category.MAIN);
    public static final Entry SIDE2 = new Entry("side2_beach", "puff_side_beach", Category.SIDE);
    public static final Entry CHAPTER3 = new Entry("chapter3", "puff_story_ch3_endgame", Category.MAIN);
    public static final Entry FINALE = new Entry("finale_marriage", "puff_story_finale_marriage", Category.ENDING);
    public static final Entry AFTER_MORNING = new Entry("after_morning", "puff_story_after_morning", Category.SIDE);

    // Stage 8: additional 2 endings (Total = 3 endings: Vow / Adventure / Hearth)
    public static final Entry ENDING_ADVENTURE = new Entry("ending_adventure", "puff_story_ending_adventure", Category.ENDING);
    public static final Entry ENDING_HEARTH = new Entry("ending_hearth", "puff_story_ending_hearth", Category.ENDING);

    // Stage 8: extensible mod crossover Galgame stories (bits 10..15; 16..31 reserved for future mods!)
    public static final Entry MOD_GOETY = new Entry("mod_goety_dark_arts", "puff_mod_goety_dark_arts", Category.CROSSOVER, "goety");
    public static final Entry MOD_GOETY_REV = new Entry("mod_goety_revelation", "puff_mod_goety_revelation", Category.CROSSOVER, "goety_revelation");
    public static final Entry MOD_IRONS = new Entry("mod_irons_arcane", "puff_mod_irons_arcane", Category.CROSSOVER, "irons_spellbooks");
    public static final Entry MOD_TRAVELOPTICS = new Entry("mod_traveloptics_echo", "puff_mod_traveloptics_echo", Category.CROSSOVER, "traveloptics");
    public static final Entry MOD_ALEXSCAVES = new Entry("mod_alexscaves_expedition", "puff_mod_alexscaves_expedition", Category.CROSSOVER, "alexscaves");
    public static final Entry MOD_CATACLYSM = new Entry("mod_cataclysm_trial", "puff_mod_cataclysm_trial", Category.CROSSOVER, "cataclysm");

    // Stage 9: expanded side branches & multi-condition route endings (bits 16..20, preserves 0..15 save compatibility)
    public static final Entry SIDE3_STARLIGHT = new Entry("side3_starlight", "puff_side_starlight", Category.SIDE);
    public static final Entry SIDE4_CHEESE_FEAST = new Entry("side4_cheese_feast", "puff_side_cheese_feast", Category.SIDE);
    public static final Entry SIDE5_RAT_PARADE = new Entry("side5_rat_parade", "puff_side_rat_parade", Category.SIDE);
    public static final Entry ENDING_QUEEN = new Entry("ending_queen", "puff_story_ending_queen", Category.ENDING);
    public static final Entry ENDING_TRUE_VOW = new Entry("ending_true_vow", "puff_story_ending_true_vow", Category.ENDING);

    public static final List<Entry> ORDER = List.of(
            PROLOGUE, CHAPTER1, SIDE1, CHAPTER2, SIDE2, CHAPTER3, FINALE, AFTER_MORNING,
            ENDING_ADVENTURE, ENDING_HEARTH,
            MOD_GOETY, MOD_GOETY_REV, MOD_IRONS, MOD_TRAVELOPTICS, MOD_ALEXSCAVES, MOD_CATACLYSM,
            SIDE3_STARLIGHT, SIDE4_CHEESE_FEAST, SIDE5_RAT_PARADE, ENDING_QUEEN, ENDING_TRUE_VOW
    );

    /** Story flag recorded when Puff has been to the Nether / End / Ratlantis (chapter 3 condition). */
    public static final String FLAG_VISITED_DANGER_DIM = "visited_danger_dim";

    /** Route orientation flags recorded from player choices across main & side stories. */
    public static final String FLAG_ROUTE_ADVENTURE = "route_adventure";
    public static final String FLAG_ROUTE_HEARTH = "route_hearth";
    public static final String FLAG_ROUTE_TRUE = "route_true";

    private PuffStories() {}

    /** Bit index for a story id, or -1. */
    public static int indexOf(String storyId) {
        for (int i = 0; i < ORDER.size(); i++) {
            if (ORDER.get(i).id().equals(storyId)) return i;
        }
        return -1;
    }

    @Nullable
    public static Entry byKey(String key) {
        for (Entry e : ORDER) if (e.key().equals(key)) return e;
        return null;
    }
}
