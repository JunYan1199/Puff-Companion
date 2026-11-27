package com.puffcompanion.client;

import com.puffcompanion.network.PuffStatsPacket;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Client-side latest stats snapshot per Puff entity id (filled while the maid panel is open). */
public final class PuffStatsCache {
    private static final Map<Integer, PuffStatsPacket> LATEST = new HashMap<>();

    private PuffStatsCache() {}

    public static void put(PuffStatsPacket packet) {
        LATEST.put(packet.entityId(), packet);
    }

    @Nullable
    public static PuffStatsPacket get(int entityId) {
        return LATEST.get(entityId);
    }
}
