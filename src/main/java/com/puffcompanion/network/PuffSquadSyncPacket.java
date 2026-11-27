package com.puffcompanion.network;

import com.puffcompanion.compat.rats.SquadManager;
import com.puffcompanion.entity.PuffEntity;
import com.puffcompanion.entity.PuffSquad;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server -> owner client (maid panel open): squad rows + area / storage / build / Lootr state for the squad tab. */
public record PuffSquadSyncPacket(int entityId, List<SquadManager.Row> rows, String area, boolean hasStorage,
                                  String buildStatus, boolean autoLootr) {
    private static final int MAX_UPGRADES = 6;

    public static PuffSquadSyncPacket of(PuffEntity puff) {
        PuffSquad squad = puff.getSquad();
        String area = "";
        if (squad.hasArea()) {
            BlockPos lo = squad.areaMin(), hi = squad.areaMax();
            area = (hi.getX() - lo.getX() + 1) + "x" + (hi.getY() - lo.getY() + 1) + "x" + (hi.getZ() - lo.getZ() + 1);
        }
        return new PuffSquadSyncPacket(puff.getId(), SquadManager.rows(puff), area, squad.storage() != null,
                squad.buildStatus, squad.autoLootr);
    }

    public static void encode(PuffSquadSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeVarInt(msg.rows.size());
        for (SquadManager.Row r : msg.rows) {
            buf.writeUUID(r.id());
            buf.writeUtf(r.name(), 64);
            buf.writeVarInt(r.job());
            buf.writeBoolean(r.online());
            int n = Math.min(MAX_UPGRADES, r.upgrades().size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) buf.writeItem(r.upgrades().get(i));
            buf.writeUtf(r.statusKey(), 128);
            buf.writeUtf(r.statusArg(), 128);
        }
        buf.writeUtf(msg.area, 32);
        buf.writeBoolean(msg.hasStorage);
        buf.writeUtf(msg.buildStatus, 128);
        buf.writeBoolean(msg.autoLootr);
    }

    public static PuffSquadSyncPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int n = Math.min(64, buf.readVarInt());
        List<SquadManager.Row> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID rid = buf.readUUID();
            String name = buf.readUtf(64);
            int job = buf.readVarInt();
            boolean online = buf.readBoolean();
            int u = Math.min(MAX_UPGRADES, buf.readVarInt());
            List<ItemStack> ups = new ArrayList<>();
            for (int k = 0; k < u; k++) ups.add(buf.readItem());
            rows.add(new SquadManager.Row(rid, name, job, online, ups, buf.readUtf(128), buf.readUtf(128)));
        }
        return new PuffSquadSyncPacket(id, rows, buf.readUtf(32), buf.readBoolean(), buf.readUtf(128), buf.readBoolean());
    }

    public static void handle(PuffSquadSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.puffcompanion.client.PuffSquadCache.put(msg));
        ctx.get().setPacketHandled(true);
    }
}
