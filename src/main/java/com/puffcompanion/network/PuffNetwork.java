package com.puffcompanion.network;

import com.puffcompanion.PuffCompanion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Forge SimpleChannel for all Puff Companion packets. */
public final class PuffNetwork {
    private static final String PROTOCOL = "1";

    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PuffCompanion.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int nextId = 0;

    private PuffNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(PuffBubblePacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PuffBubblePacket::encode)
                .decoder(PuffBubblePacket::decode)
                .consumerMainThread(PuffBubblePacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffInteractionPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffInteractionPacket::encode)
                .decoder(PuffInteractionPacket::decode)
                .consumerMainThread(PuffInteractionPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffStoryStartPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PuffStoryStartPacket::encode)
                .decoder(PuffStoryStartPacket::decode)
                .consumerMainThread(PuffStoryStartPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffStoryChoicePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffStoryChoicePacket::encode)
                .decoder(PuffStoryChoicePacket::decode)
                .consumerMainThread(PuffStoryChoicePacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffStatsPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PuffStatsPacket::encode)
                .decoder(PuffStatsPacket::decode)
                .consumerMainThread(PuffStatsPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffStaffModePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffStaffModePacket::encode)
                .decoder(PuffStaffModePacket::decode)
                .consumerMainThread(PuffStaffModePacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffSquadSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PuffSquadSyncPacket::encode)
                .decoder(PuffSquadSyncPacket::decode)
                .consumerMainThread(PuffSquadSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffSquadJobPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffSquadJobPacket::encode)
                .decoder(PuffSquadJobPacket::decode)
                .consumerMainThread(PuffSquadJobPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffSquadKickPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffSquadKickPacket::encode)
                .decoder(PuffSquadKickPacket::decode)
                .consumerMainThread(PuffSquadKickPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffSquadDetailRequestPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffSquadDetailRequestPacket::encode)
                .decoder(PuffSquadDetailRequestPacket::decode)
                .consumerMainThread(PuffSquadDetailRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffSquadDetailPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PuffSquadDetailPacket::encode)
                .decoder(PuffSquadDetailPacket::decode)
                .consumerMainThread(PuffSquadDetailPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffCurioExpandOpenPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PuffCurioExpandOpenPacket::encode)
                .decoder(PuffCurioExpandOpenPacket::decode)
                .consumerMainThread(PuffCurioExpandOpenPacket::handle)
                .add();
        CHANNEL.messageBuilder(PuffCurioExpandPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PuffCurioExpandPacket::encode)
                .decoder(PuffCurioExpandPacket::decode)
                .consumerMainThread(PuffCurioExpandPacket::handle)
                .add();
    }

    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Sends to every client that can currently see the entity (owner included). */
    public static void sendToTracking(Entity entity, Object packet) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet);
    }
}
