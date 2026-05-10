package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimWorldMapViewTest {
    @Test
    void initializesAroundClaimChunkAndMapsMouseToChunk() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(10, -4, 4, 164, 164);

        assertEquals(10, view.centerChunkX());
        assertEquals(-4, view.centerChunkZ());
        assertEquals(10, view.screenToChunk(82, 82, 0, 0, 164, 164).x);
        assertEquals(-4, view.screenToChunk(82, 82, 0, 0, 164, 164).z);
    }

    @Test
    void visibleForceRenderPacketCoversCurrentScreenRectangle() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(0, 0, 2, 160, 160);

        RoadPlannerMapPreloadRequestPacket packet = view.createVisibleForceRenderRequest(
                "world_a",
                "minecraft:overworld",
                10,
                20,
                160,
                160);

        assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER, packet.purpose());
        assertEquals("world_a", packet.worldId());
        assertEquals("minecraft:overworld", packet.dimensionId());
        assertTrue(packet.start().getX() < packet.destination().getX());
        assertTrue(packet.start().getZ() < packet.destination().getZ());
        assertEquals(2, packet.routeNodes().size());
    }

    @Test
    void chunkOverlayRectUsesSameScaleAsMapView() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(0, 0, 2, 160, 160);

        ClaimWorldMapView.ScreenRect rect = view.chunkScreenRect(0, 0, 0, 0, 160, 160);

        assertTrue(rect.width() > 0);
        assertTrue(rect.height() > 0);
        assertTrue(rect.x() <= 80 && rect.right() >= 80);
        assertTrue(rect.y() <= 80 && rect.bottom() >= 80);
    }

    @Test
    void chunkOverlayRectStaysSquareAfterOffCenterZoom() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(0, 0, 4, 164, 164);

        view.zoomAround(0, 0, 1.2D, 0, 0, 164, 164);
        ClaimWorldMapView.ScreenRect rect = view.chunkScreenRect(-5, -1, 0, 0, 164, 164);

        assertEquals(rect.width(), rect.height());
    }

    @Test
    void requestedLodIsAlwaysLod1() {
        assertEquals(MapLod.LOD_1, ClaimWorldMapView.RENDER_LOD);
    }
}
