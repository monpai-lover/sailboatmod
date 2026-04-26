package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerPacketRoundTripTest {
    @Test
    void snapshotRequestRoundTripsCorridorFields() {
        RoadMapSnapshotRequestPacket packet = new RoadMapSnapshotRequestPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                new BlockPos(384, 70, 0),
                List.of(new BlockPos(128, 65, 0)),
                128,
                MapLod.LOD_4);

        RoadMapSnapshotRequestPacket decoded = roundTrip(packet, RoadMapSnapshotRequestPacket::encode, RoadMapSnapshotRequestPacket::decode);

        assertEquals(packet, decoded);
    }

    @Test
    void snapshotSyncRoundTripsPixels() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                0,
                0,
                128,
                MapLod.LOD_4,
                32,
                32,
                new int[]{0xFF00AA00, 0xFF0000AA});

        RoadMapSnapshotSyncPacket decoded = roundTrip(packet, RoadMapSnapshotSyncPacket::encode, RoadMapSnapshotSyncPacket::decode);

        assertEquals(packet, decoded);
        assertEquals(0xFF0000AA, decoded.argbPixels()[1]);
    }

    @Test
    void strokeAndBuildControlPacketsRoundTrip() {
        UUID sessionId = UUID.randomUUID();
        assertEquals(new RoadPlannerRegionNavigationPacket(sessionId, 2),
                roundTrip(new RoadPlannerRegionNavigationPacket(sessionId, 2), RoadPlannerRegionNavigationPacket::encode, RoadPlannerRegionNavigationPacket::decode));
        assertEquals(new RoadPlannerConfirmBuildPacket(sessionId),
                roundTrip(new RoadPlannerConfirmBuildPacket(sessionId), RoadPlannerConfirmBuildPacket::encode, RoadPlannerConfirmBuildPacket::decode));
        assertEquals(new RoadPlannerCancelJobPacket(sessionId),
                roundTrip(new RoadPlannerCancelJobPacket(sessionId), RoadPlannerCancelJobPacket::encode, RoadPlannerCancelJobPacket::decode));
    }

    private <T> T roundTrip(T packet, PacketEncoder<T> encoder, PacketDecoder<T> decoder) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        encoder.encode(packet, buffer);
        return decoder.decode(buffer);
    }

    @FunctionalInterface
    private interface PacketEncoder<T> {
        void encode(T packet, FriendlyByteBuf buffer);
    }

    @FunctionalInterface
    private interface PacketDecoder<T> {
        T decode(FriendlyByteBuf buffer);
    }
}
