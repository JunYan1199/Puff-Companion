package com.puffcompanion.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-save record of Puffs that died and have not been revived yet: Puff UUID -> full entity snapshot.
 * It is the single source of truth for revival, so a death can be revived exactly once (cheese core in the
 * nest OR bell + cheese block), never duplicated.
 */
public class PuffGraveyard extends SavedData {
    private static final String NAME = "puffcompanion_graveyard";
    private final Map<UUID, CompoundTag> dead = new HashMap<>();

    public static PuffGraveyard get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PuffGraveyard::load, PuffGraveyard::new, NAME);
    }

    private static PuffGraveyard load(CompoundTag tag) {
        PuffGraveyard g = new PuffGraveyard();
        CompoundTag entries = tag.getCompound("Dead");
        for (String key : entries.getAllKeys()) {
            try {
                g.dead.put(UUID.fromString(key), entries.getCompound(key));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return g;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag entries = new CompoundTag();
        this.dead.forEach((id, snap) -> entries.put(id.toString(), snap));
        tag.put("Dead", entries);
        return tag;
    }

    public void record(UUID puff, CompoundTag snapshot) {
        this.dead.put(puff, snapshot.copy());
        this.setDirty();
    }

    public boolean isDead(UUID puff) {
        return this.dead.containsKey(puff);
    }

    public java.util.Set<UUID> deadIds() {
        return java.util.Set.copyOf(this.dead.keySet());
    }

    @Nullable
    public CompoundTag peek(UUID puff) {
        return this.dead.get(puff);
    }

    /** Removes and returns the snapshot (revival consumes it). */
    @Nullable
    public CompoundTag take(UUID puff) {
        CompoundTag snap = this.dead.remove(puff);
        if (snap != null) this.setDirty();
        return snap;
    }
}
