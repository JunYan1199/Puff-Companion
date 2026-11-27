package com.puffcompanion.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Galgame script loaded from {@code data/<ns>/puff_stories/<key>.json}. Contains only structure and
 * translation keys; all display text lives in the lang files. Shared by server (validation/rewards) and
 * client (rendering), which parses the same JSON it receives in {@code PuffStoryStartPacket}.
 *
 * <pre>
 * {
 *   "id": "puff_story_prologue",            // written to CompletedStories
 *   "title": "puff.story.prologue.title",
 *   "start": "intro",
 *   "nodes": {
 *     "intro": {
 *       "lines": [ { "speaker": "puff|narrator|player", "expression": "happy",
 *                    "action": "lang.key (stage direction, optional)", "text": "lang.key",
 *                    "anim": "action_shy (optional)" } ],
 *       "choices": [ { "text": "lang.key", "next": "a", "affection": 5, "flags": ["..."] } ],
 *       "next": "outro"                       // when there are no choices; omit to end
 *     }
 *   },
 *   "on_complete": { "affection": 0, "flags": [], "setStage": 2, "unlockOutfits": ["mid"],
 *                    "equipOutfit": "mid", "giveItems": [ { "item": "minecraft:bread", "count": 2 } ] }
 * }
 * </pre>
 */
public record PuffStory(String id, String titleKey, String start, Map<String, Node> nodes, Reward onComplete) {

    public record Line(String speaker, @Nullable String expression, @Nullable String ear,
                       @Nullable String action, String text, @Nullable String anim) {}

    public record Choice(String text, @Nullable String next, int affection, List<String> flags) {}

    public record Node(List<Line> lines, List<Choice> choices, @Nullable String next) {}

    /** One stack handed to the player on completion (skipped if the item id is unknown, e.g. Rats absent). */
    public record GiveItem(String item, int count) {}

    /**
     * Completion settlement. {@code setStage} 0 = unchanged; {@code equipOutfit} null = unchanged.
     * Applied once (never on replay).
     */
    public record Reward(int affection, List<String> flags, int setStage, List<String> unlockOutfits,
                         @Nullable String equipOutfit, List<GiveItem> giveItems) {
        static final Reward NONE = new Reward(0, List.of(), 0, List.of(), null, List.of());
    }

    public static final String SPEAKER_PUFF = "puff";
    public static final String SPEAKER_NARRATOR = "narrator";
    public static final String SPEAKER_PLAYER = "player";

    @Nullable
    public Node node(@Nullable String nodeId) {
        return nodeId == null ? null : this.nodes.get(nodeId);
    }

    /**
     * Follows {@code next} links from {@code fromNode} and returns the first node that offers choices, or
     * null if the path ends without one (i.e. the story finishes on that path).
     */
    @Nullable
    public String firstChoiceNode(@Nullable String fromNode) {
        Set<String> seen = new HashSet<>();
        String cur = fromNode;
        while (cur != null && seen.add(cur)) {
            Node n = this.nodes.get(cur);
            if (n == null) return null;
            if (!n.choices().isEmpty()) return cur;
            cur = n.next();
        }
        return null;
    }

    public static PuffStory parse(JsonObject json) {
        String id = GsonHelper.getAsString(json, "id");
        String title = GsonHelper.getAsString(json, "title", id);
        String start = GsonHelper.getAsString(json, "start");
        Map<String, Node> nodes = new LinkedHashMap<>();
        JsonObject nodesJson = GsonHelper.getAsJsonObject(json, "nodes");
        for (Map.Entry<String, JsonElement> e : nodesJson.entrySet()) {
            nodes.put(e.getKey(), parseNode(GsonHelper.convertToJsonObject(e.getValue(), "node " + e.getKey())));
        }
        Reward reward = Reward.NONE;
        if (json.has("on_complete")) {
            JsonObject r = GsonHelper.getAsJsonObject(json, "on_complete");
            List<GiveItem> give = new ArrayList<>();
            for (JsonElement el : GsonHelper.getAsJsonArray(r, "giveItems", new JsonArray())) {
                JsonObject g = GsonHelper.convertToJsonObject(el, "giveItems");
                give.add(new GiveItem(GsonHelper.getAsString(g, "item"), GsonHelper.getAsInt(g, "count", 1)));
            }
            reward = new Reward(GsonHelper.getAsInt(r, "affection", 0), strings(r, "flags"),
                    GsonHelper.getAsInt(r, "setStage", 0), strings(r, "unlockOutfits"),
                    r.has("equipOutfit") ? GsonHelper.getAsString(r, "equipOutfit") : null, List.copyOf(give));
        }
        PuffStory story = new PuffStory(id, title, start, nodes, reward);
        story.validate();
        return story;
    }

    private static Node parseNode(JsonObject json) {
        List<Line> lines = new ArrayList<>();
        for (JsonElement el : GsonHelper.getAsJsonArray(json, "lines", new JsonArray())) {
            JsonObject l = GsonHelper.convertToJsonObject(el, "line");
            lines.add(new Line(
                    GsonHelper.getAsString(l, "speaker", SPEAKER_PUFF),
                    l.has("expression") ? GsonHelper.getAsString(l, "expression") : null,
                    l.has("ear") ? GsonHelper.getAsString(l, "ear") : null,
                    l.has("action") ? GsonHelper.getAsString(l, "action") : null,
                    GsonHelper.getAsString(l, "text"),
                    l.has("anim") ? GsonHelper.getAsString(l, "anim") : null));
        }
        List<Choice> choices = new ArrayList<>();
        for (JsonElement el : GsonHelper.getAsJsonArray(json, "choices", new JsonArray())) {
            JsonObject c = GsonHelper.convertToJsonObject(el, "choice");
            choices.add(new Choice(
                    GsonHelper.getAsString(c, "text"),
                    c.has("next") ? GsonHelper.getAsString(c, "next") : null,
                    GsonHelper.getAsInt(c, "affection", 0),
                    strings(c, "flags")));
        }
        String next = json.has("next") ? GsonHelper.getAsString(json, "next") : null;
        return new Node(List.copyOf(lines), List.copyOf(choices), next);
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : GsonHelper.getAsJsonArray(json, key, new JsonArray())) {
            out.add(GsonHelper.convertToString(el, key));
        }
        return List.copyOf(out);
    }

    private void validate() {
        if (!this.nodes.containsKey(this.start)) {
            throw new JsonParseException("start node '" + this.start + "' does not exist");
        }
        for (Map.Entry<String, Node> e : this.nodes.entrySet()) {
            Node n = e.getValue();
            if (n.next() != null && !this.nodes.containsKey(n.next())) {
                throw new JsonParseException("node '" + e.getKey() + "' -> unknown next '" + n.next() + "'");
            }
            for (Choice c : n.choices()) {
                if (c.next() != null && !this.nodes.containsKey(c.next())) {
                    throw new JsonParseException("choice in '" + e.getKey() + "' -> unknown next '" + c.next() + "'");
                }
            }
        }
    }
}
