package com.puffcompanion.client;

import com.puffcompanion.network.PuffSquadSyncPacket;
import com.puffcompanion.network.PuffSquadDetailPacket;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client: latest squad snapshot per Puff entity id (for the maid panel's squad tab). */
public final class PuffSquadCache {
    private static final Map<Integer, PuffSquadSyncPacket> DATA = new HashMap<>();
    @Nullable
    private static PuffSquadDetailPacket DETAIL;
    private static long detailRevision;

    private PuffSquadCache() {}

    public static void put(PuffSquadSyncPacket msg) {
        DATA.put(msg.entityId(), msg);
    }

    /** Optimistic local job change (the button flips at once; the server's sync confirms it). */
    public static void setJob(int entityId, java.util.UUID rat, int job) {
        PuffSquadSyncPacket data = DATA.get(entityId);
        if (data == null) return;
        java.util.List<com.puffcompanion.compat.rats.SquadManager.Row> rows = new java.util.ArrayList<>(data.rows());
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            if (r.id().equals(rat)) {
                rows.set(i, new com.puffcompanion.compat.rats.SquadManager.Row(r.id(), r.name(), job, r.online(), r.upgrades(), "", ""));
            }
        }
        DATA.put(entityId, new PuffSquadSyncPacket(data.entityId(), rows, data.area(), data.hasStorage(), data.buildStatus(), data.autoLootr()));
    }

    public static void toggleLootr(int entityId) {
        PuffSquadSyncPacket d = DATA.get(entityId);
        if (d != null) DATA.put(entityId, new PuffSquadSyncPacket(d.entityId(), d.rows(), d.area(), d.hasStorage(), d.buildStatus(), !d.autoLootr()));
    }

    /** Optimistic local removal when clicking the kick button. */
    public static void kick(int entityId, UUID rat) {
        PuffSquadSyncPacket data = DATA.get(entityId);
        if (data != null) {
            var rows = new java.util.ArrayList<>(data.rows());
            rows.removeIf(r -> r.id().equals(rat));
            DATA.put(entityId, new PuffSquadSyncPacket(data.entityId(), rows, data.area(), data.hasStorage(), data.buildStatus(), data.autoLootr()));
        }
        if (DETAIL != null && DETAIL.entityId() == entityId && DETAIL.detail().id().equals(rat)) {
            clearDetail();
        }
    }

    public static void putDetail(PuffSquadDetailPacket msg) {
        DETAIL = msg;
        detailRevision++;
    }

    @Nullable
    public static PuffSquadDetailPacket getDetail(int entityId, UUID rat) {
        if (DETAIL == null || DETAIL.entityId() != entityId || !DETAIL.detail().id().equals(rat)) return null;
        return DETAIL;
    }

    public static long detailRevision() {
        return detailRevision;
    }

    public static void clearDetail() {
        DETAIL = null;
        detailRevision++;
    }

    @Nullable
    public static PuffSquadSyncPacket get(int entityId) {
        return DATA.get(entityId);
    }
}
