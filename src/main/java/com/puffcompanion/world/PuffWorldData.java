package com.puffcompanion.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Per-save (stored in the overworld's data folder as {@code data/puffcompanion_world.dat}) record of
 * whether the "first meeting" Puff has already been spawned, so she is never spawned twice.
 */
public class PuffWorldData extends SavedData {
    public static final String DATA_NAME = "puffcompanion_world";
    private static final String TAG_SPAWNED = "InitialPuffSpawned";
    private static final String TAG_PUFF = "InitialPuffUUID";
    private static final String TAG_OWNER = "InitialPuffOwner";

    private boolean initialPuffSpawned;
    @Nullable private UUID initialPuffUUID;
    @Nullable private UUID initialPuffOwner;

    public static PuffWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(PuffWorldData::load, PuffWorldData::new, DATA_NAME);
    }

    public static PuffWorldData load(CompoundTag tag) {
        PuffWorldData data = new PuffWorldData();
        data.initialPuffSpawned = tag.getBoolean(TAG_SPAWNED);
        if (tag.hasUUID(TAG_PUFF)) data.initialPuffUUID = tag.getUUID(TAG_PUFF);
        if (tag.hasUUID(TAG_OWNER)) data.initialPuffOwner = tag.getUUID(TAG_OWNER);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean(TAG_SPAWNED, this.initialPuffSpawned);
        if (this.initialPuffUUID != null) tag.putUUID(TAG_PUFF, this.initialPuffUUID);
        if (this.initialPuffOwner != null) tag.putUUID(TAG_OWNER, this.initialPuffOwner);
        return tag;
    }

    public boolean isInitialPuffSpawned() {
        return this.initialPuffSpawned;
    }

    public void markInitialPuffSpawned(UUID puff, UUID owner) {
        this.initialPuffSpawned = true;
        this.initialPuffUUID = puff;
        this.initialPuffOwner = owner;
        this.setDirty();
    }

    @Nullable public UUID getInitialPuffUUID() { return this.initialPuffUUID; }
    @Nullable public UUID getInitialPuffOwner() { return this.initialPuffOwner; }
}
