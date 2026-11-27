package com.puffcompanion.network;

import com.puffcompanion.compat.rats.SquadManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server -> client: on-demand live details and position of a single squad rat. */
public record PuffSquadDetailPacket(int entityId, SquadManager.RatDetail detail) {
    private static final int MAX_UPGRADES = 12;

    public static void encode(PuffSquadDetailPacket msg, FriendlyByteBuf buf) {
        SquadManager.RatDetail d = msg.detail;
        buf.writeVarInt(msg.entityId);
        buf.writeUUID(d.id());
        buf.writeUtf(d.name(), 64);
        buf.writeVarInt(d.job());
        buf.writeBoolean(d.online());
        buf.writeFloat(d.hp());
        buf.writeFloat(d.maxHp());
        buf.writeVarInt(d.x());
        buf.writeVarInt(d.y());
        buf.writeVarInt(d.z());
        buf.writeUtf(d.dimension(), 64);
        buf.writeVarInt(d.distance());
        buf.writeUtf(d.command(), 32);
        buf.writeBoolean(d.sitting());
        buf.writeBoolean(d.inCage());
        buf.writeItem(d.heldItem());
        int n = Math.min(MAX_UPGRADES, d.upgrades().size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeItem(d.upgrades().get(i));
        buf.writeUtf(d.statusKey(), 128);
        buf.writeUtf(d.statusArg(), 128);
    }

    public static PuffSquadDetailPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID id = buf.readUUID();
        String name = buf.readUtf(64);
        int job = buf.readVarInt();
        boolean online = buf.readBoolean();
        float hp = buf.readFloat();
        float maxHp = buf.readFloat();
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        String dimension = buf.readUtf(64);
        int distance = buf.readVarInt();
        String command = buf.readUtf(32);
        boolean sitting = buf.readBoolean();
        boolean inCage = buf.readBoolean();
        ItemStack held = buf.readItem();
        int n = Math.min(MAX_UPGRADES, buf.readVarInt());
        List<ItemStack> upgrades = new ArrayList<>();
        for (int i = 0; i < n; i++) upgrades.add(buf.readItem());
        String statusKey = buf.readUtf(128);
        String statusArg = buf.readUtf(128);
        return new PuffSquadDetailPacket(entityId, new SquadManager.RatDetail(
                id, name, job, online, hp, maxHp, x, y, z, dimension, distance,
                command, sitting, inCage, held, upgrades, statusKey, statusArg));
    }

    public static void handle(PuffSquadDetailPacket msg, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.puffcompanion.client.PuffSquadCache.putDetail(msg));
        ctx.get().setPacketHandled(true);
    }
}
