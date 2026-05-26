package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoadPlannerPacketRoundTripTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void snapshotRequestRoundTripsViewportFields() {
        RoadMapSnapshotRequestPacket packet = new RoadMapSnapshotRequestPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                42L,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT,
                new BlockPos(128, 0, -64),
                256,
                MapLod.LOD_4);

        RoadMapSnapshotRequestPacket decoded = roundTrip(packet, RoadMapSnapshotRequestPacket::encode, RoadMapSnapshotRequestPacket::decode);

        assertEquals(packet, decoded);
    }

    @Test
    void snapshotSyncRoundTripsPixelsAndRequestIdentity() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                42L,
                RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER,
                0,
                0,
                128,
                MapLod.LOD_4,
                32,
                32,
                new int[]{0xFF00AA00, 0xFF0000AA});

        RoadMapSnapshotSyncPacket decoded = roundTrip(packet, RoadMapSnapshotSyncPacket::encode, RoadMapSnapshotSyncPacket::decode);

        assertEquals(packet, decoded);
        assertEquals(42L, decoded.requestId());
        assertEquals(RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER, decoded.purpose());
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

    @Test
    void preloadPacketsRoundTripIdentityAndPixels() {
        UUID sessionId = UUID.randomUUID();
        RoadPlannerMapPreloadRequestPacket request = new RoadPlannerMapPreloadRequestPacket(
                sessionId,
                17L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD,
                "world_a",
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                new BlockPos(512, 64, 256),
                List.of(new BlockPos(128, 64, 64), new BlockPos(256, 64, 128)),
                RoadPlannerMapPreloadRequestPacket.PROTOCOL_VERSION);
        RoadPlannerMapTileSyncPacket tile = new RoadPlannerMapTileSyncPacket(
                sessionId,
                17L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_4,
                0,
                0,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS]);
        boolean[] coverageMask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        coverageMask[0] = true;
        RoadPlannerMapTileSyncPacket maskedTile = new RoadPlannerMapTileSyncPacket(
                sessionId,
                18L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                1,
                -1,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
                coverageMask);
        RoadPlannerMapPreloadProgressPacket progress = new RoadPlannerMapPreloadProgressPacket(
                sessionId,
                17L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE,
                1,
                8,
                RoadPlannerMapPreloadProgressPacket.State.SAMPLING,
                "sampling");
        RoadPlannerMapPreloadCancelPacket cancel = new RoadPlannerMapPreloadCancelPacket(
                sessionId,
                17L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER);

        assertEquals(request, roundTrip(request, RoadPlannerMapPreloadRequestPacket::encode, RoadPlannerMapPreloadRequestPacket::decode));
        assertEquals(tile, roundTrip(tile, RoadPlannerMapTileSyncPacket::encode, RoadPlannerMapTileSyncPacket::decode));
        assertArrayEquals(coverageMask, roundTrip(maskedTile, RoadPlannerMapTileSyncPacket::encode, RoadPlannerMapTileSyncPacket::decode).coverageMask());
        assertEquals(progress, roundTrip(progress, RoadPlannerMapPreloadProgressPacket::encode, RoadPlannerMapPreloadProgressPacket::decode));
        assertEquals(cancel, roundTrip(cancel, RoadPlannerMapPreloadCancelPacket::encode, RoadPlannerMapPreloadCancelPacket::decode));
    }

    @Test
    void mergeCandidateAndOverlayPacketsRoundTrip() {
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        OpenRoadMergeCandidatesPacket candidatePacket = new OpenRoadMergeCandidatesPacket(sessionId, requestId, List.of(
                new OpenRoadMergeCandidatesPacket.Entry("road_a", new BlockPos(8, 64, 0), 3, 5,
                        "Alpha", "Beta", "nation-a", RoadPlannerMergeRelationship.OWN)
        ));
        RoadPlannerMergeCandidateRequestPacket candidateRequest = new RoadPlannerMergeCandidateRequestPacket(
                sessionId, requestId, new BlockPos(8, 64, 1), 12, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD);
        RoadPlannerRoadOverlayRequestPacket overlayRequest = new RoadPlannerRoadOverlayRequestPacket(
                sessionId, "world_a", "minecraft:overworld", new BlockPos(0, 64, 0), 256, RoadPlannerMergeScope.ALLIED_OR_TRADE);
        RoadPlannerRoadOverlaySyncPacket overlaySync = new RoadPlannerRoadOverlaySyncPacket(
                sessionId,
                overlayRequest.regionCenter(),
                overlayRequest.regionSize(),
                overlayRequest.scope(),
                List.of(
                new RoadPlannerRoadOverlaySyncPacket.Entry("road_a", RoadPlannerMergeRelationship.TRADE,
                        List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)))
        ));

        assertEquals(candidatePacket, roundTrip(candidatePacket, OpenRoadMergeCandidatesPacket::encode, OpenRoadMergeCandidatesPacket::decode));
        assertEquals(candidateRequest, roundTrip(candidateRequest, RoadPlannerMergeCandidateRequestPacket::encode, RoadPlannerMergeCandidateRequestPacket::decode));
        assertEquals(requestId, roundTrip(candidatePacket, OpenRoadMergeCandidatesPacket::encode, OpenRoadMergeCandidatesPacket::decode).requestId());
        assertEquals(requestId, roundTrip(candidateRequest, RoadPlannerMergeCandidateRequestPacket::encode, RoadPlannerMergeCandidateRequestPacket::decode).requestId());
        assertEquals(overlayRequest, roundTrip(overlayRequest, RoadPlannerRoadOverlayRequestPacket::encode, RoadPlannerRoadOverlayRequestPacket::decode));
        assertEquals(overlaySync, roundTrip(overlaySync, RoadPlannerRoadOverlaySyncPacket::encode, RoadPlannerRoadOverlaySyncPacket::decode));
    }

    @Test
    void mergeCandidateRequestNormalizesRadiusToBounds() {
        UUID sessionId = UUID.randomUUID();
        RoadPlannerMergeCandidateRequestPacket huge = new RoadPlannerMergeCandidateRequestPacket(
                sessionId, new BlockPos(0, 64, 0), 4096, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD);
        RoadPlannerMergeCandidateRequestPacket zero = new RoadPlannerMergeCandidateRequestPacket(
                sessionId, new BlockPos(0, 64, 0), 0, RoadPlannerMergeScope.OWN_NATION, RoadPlannerSegmentType.ROAD);

        assertEquals(64, huge.radius());
        assertEquals(64, roundTrip(huge, RoadPlannerMergeCandidateRequestPacket::encode, RoadPlannerMergeCandidateRequestPacket::decode).radius());
        assertEquals(1, zero.radius());
    }

    @Test
    void openMergeCandidatesRejectsOversizeAdvertisedCount() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPacketCodec.writeUuid(buffer, UUID.randomUUID());
        RoadPlannerPacketCodec.writeUuid(buffer, UUID.randomUUID());
        buffer.writeVarInt(17);

        assertThrows(IllegalArgumentException.class, () -> OpenRoadMergeCandidatesPacket.decode(buffer));
    }

    @Test
    void roadOverlaySyncRejectsOversizeAdvertisedRoadCount() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPacketCodec.writeUuid(buffer, UUID.randomUUID());
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeVarInt(128);
        buffer.writeEnum(RoadPlannerMergeScope.OWN_NATION);
        buffer.writeVarInt(129);

        assertThrows(IllegalArgumentException.class, () -> RoadPlannerRoadOverlaySyncPacket.decode(buffer));
    }

    @Test
    void roadOverlayRequestPreservesSmallPositiveRegionSize() {
        RoadPlannerRoadOverlayRequestPacket packet = new RoadPlannerRoadOverlayRequestPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                64,
                RoadPlannerMergeScope.OWN_NATION);

        RoadPlannerRoadOverlayRequestPacket decoded = roundTrip(
                packet,
                RoadPlannerRoadOverlayRequestPacket::encode,
                RoadPlannerRoadOverlayRequestPacket::decode);

        assertEquals(64, decoded.regionSize());
    }

    @Test
    void roadOverlayRequestClampsHugeRegionSize() {
        RoadPlannerRoadOverlayRequestPacket packet = new RoadPlannerRoadOverlayRequestPacket(
                UUID.randomUUID(),
                "world_a",
                "minecraft:overworld",
                new BlockPos(0, 64, 0),
                4096,
                RoadPlannerMergeScope.OWN_NATION);

        RoadPlannerRoadOverlayRequestPacket decoded = roundTrip(
                packet,
                RoadPlannerRoadOverlayRequestPacket::encode,
                RoadPlannerRoadOverlayRequestPacket::decode);

        assertEquals(512, decoded.regionSize());
    }

    @Test
    void roadOverlaySyncDecodeCapsPathPointsAndConsumesExtras() {
        UUID sessionId = UUID.randomUUID();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPacketCodec.writeUuid(buffer, sessionId);
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeVarInt(128);
        buffer.writeEnum(RoadPlannerMergeScope.OWN_NATION);
        buffer.writeVarInt(1);
        RoadPlannerPacketCodec.writeString(buffer, "road_a", 128);
        buffer.writeEnum(RoadPlannerMergeRelationship.TRADE);
        buffer.writeVarInt(130);
        for (int index = 0; index < 130; index++) {
            buffer.writeBlockPos(new BlockPos(index, 64, 0));
        }
        buffer.writeVarInt(99);

        RoadPlannerRoadOverlaySyncPacket decoded = RoadPlannerRoadOverlaySyncPacket.decode(buffer);

        assertEquals(1, decoded.roads().size());
        assertEquals(128, decoded.roads().get(0).path().size());
        assertEquals(new BlockPos(127, 64, 0), decoded.roads().get(0).path().get(127));
        assertEquals(99, buffer.readVarInt());
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
