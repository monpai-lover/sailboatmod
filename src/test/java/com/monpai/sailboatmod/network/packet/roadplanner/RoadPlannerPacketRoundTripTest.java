package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadPlannerPacketRoundTripTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
        assertEquals(new OpenRoadPlannerActionMenuPacket(RoadPlannerActionMenuMode.PREVIEW, sessionId),
                roundTrip(new OpenRoadPlannerActionMenuPacket(RoadPlannerActionMenuMode.PREVIEW, sessionId), OpenRoadPlannerActionMenuPacket::encode, OpenRoadPlannerActionMenuPacket::decode));
        assertEquals(new RoadPlannerMenuActionPacket(RoadPlannerMenuActionPacket.Action.OPEN_DEMOLITION_PLANNER),
                roundTrip(new RoadPlannerMenuActionPacket(RoadPlannerMenuActionPacket.Action.OPEN_DEMOLITION_PLANNER), RoadPlannerMenuActionPacket::encode, RoadPlannerMenuActionPacket::decode));
        assertEquals(new RoadPlannerMenuActionPacket(RoadPlannerMenuActionPacket.Action.RETURN_TO_PLANNER),
                roundTrip(new RoadPlannerMenuActionPacket(RoadPlannerMenuActionPacket.Action.RETURN_TO_PLANNER), RoadPlannerMenuActionPacket::encode, RoadPlannerMenuActionPacket::decode));
    }

    @Test
    void previewRequestCreatesGhostBlocksForWorldPreview() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD)
        );

        SyncRoadPlannerPreviewPacket preview = packet.toPreviewPacketForTest();

        assertFalse(preview.ghostBlocks().isEmpty());
        assertEquals(2, preview.pathNodes().size());
        assertEquals(2, preview.pathNodeCount());
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
