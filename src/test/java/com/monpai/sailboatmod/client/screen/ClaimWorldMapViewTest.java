package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void forceRenderRequestMarkerTracksVisibleChunkBounds() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(0, 0, 2, 160, 160);
        ClaimMapViewport viewport = new ClaimMapViewport(0, 0, 160, 160);

        assertTrue(view.markVisibleForceRenderRequested(viewport));
        assertEquals(false, view.markVisibleForceRenderRequested(viewport));

        view.panByScreenDelta(160, 0);

        assertTrue(view.markVisibleForceRenderRequested(viewport));
    }

    @Test
    void defaultClaimMapUsesSharedRoadPlannerTileManager(@TempDir Path tempDir) {
        RoadPlannerTileManager manager = new NonRefreshingTileManager(tempDir);
        RoadPlannerTileManager.setSharedDefaultForTest(manager);
        ClaimWorldMapView view = new ClaimWorldMapView();

        try {
            assertSame(manager, view.tileManagerForTest());

            view.close();

            assertSame(manager, RoadPlannerTileManager.sharedDefault());
        } finally {
            RoadPlannerTileManager.clearSharedDefaultForTest();
        }
    }

    @Test
    void claimMapAcknowledgesSharedTilePacketFromOtherSession(@TempDir Path tempDir) {
        RoadPlannerTileManager manager = new NonRefreshingTileManager(tempDir);
        RoadPlannerTileManager.setSharedDefaultForTest(manager);
        SharedMapClientState.defaultState().clearAll();
        ClaimWorldMapView view = new ClaimWorldMapView();
        RoadPlannerMapTileSyncPacket packet = new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                77L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                manager.worldId(),
                manager.dimensionId(),
                MapLod.LOD_1,
                0,
                0,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
                null
        );

        try {
            SharedMapClientState.defaultState().applyTileDelta(packet);

            int applied = view.applyTileSync(packet);

            assertEquals(1, applied);
        } finally {
            view.close();
            SharedMapClientState.defaultState().clearAll();
            RoadPlannerTileManager.clearSharedDefaultForTest();
        }
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

    @Test
    void claimMapViewportAppliesScrollOnce() {
        ClaimMapViewport viewport = ClaimMapViewport.scrolled(100, 120, 45, 160, 160);

        assertEquals(100, viewport.x());
        assertEquals(75, viewport.y());
        assertEquals(260, viewport.right());
        assertEquals(235, viewport.bottom());
    }

    @Test
    void claimMapViewportIntersectionKeepsVisibleScreenRect() {
        ClaimMapViewport viewport = ClaimMapViewport.scrolled(100, 120, 45, 160, 160);

        ClaimMapViewport visible = viewport.intersection(90, 100, 240, 300);

        assertEquals(100, visible.x());
        assertEquals(100, visible.y());
        assertEquals(240, visible.right());
        assertEquals(235, visible.bottom());
    }

    @Test
    void claimMapViewportIntersectionReturnsNullWhenEmpty() {
        ClaimMapViewport viewport = ClaimMapViewport.scrolled(100, 120, 45, 160, 160);

        assertNull(viewport.intersection(0, 0, 50, 50));
    }

    @Test
    void scrolledViewportHitTestingUsesScreenRect() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(10, -4, 4, 164, 164);
        ClaimMapViewport viewport = ClaimMapViewport.scrolled(20, 80, 30, 164, 164);

        assertEquals(10, view.screenToChunk(viewport.x() + 82, viewport.y() + 82, viewport).x);
        assertEquals(-4, view.screenToChunk(viewport.x() + 82, viewport.y() + 82, viewport).z);
    }

    @Test
    void forceRenderRequestUsesViewportScreenRect() {
        ClaimWorldMapView view = ClaimWorldMapView.forTest(0, 0, 2, 160, 160);
        ClaimMapViewport viewport = ClaimMapViewport.scrolled(10, 80, 40, 160, 160);

        RoadPlannerMapPreloadRequestPacket viewportPacket = view.createVisibleForceRenderRequest(
                "world_a",
                "minecraft:overworld",
                viewport
        );
        RoadPlannerMapPreloadRequestPacket explicitPacket = view.createVisibleForceRenderRequest(
                "world_a",
                "minecraft:overworld",
                viewport.x(),
                viewport.y(),
                viewport.width(),
                viewport.height()
        );

        assertEquals(explicitPacket.start(), viewportPacket.start());
        assertEquals(explicitPacket.destination(), viewportPacket.destination());
        assertTrue(viewportPacket.start().getX() < viewportPacket.destination().getX());
        assertTrue(viewportPacket.start().getZ() < viewportPacket.destination().getZ());
    }

    private static final class NonRefreshingTileManager extends RoadPlannerTileManager {
        private NonRefreshingTileManager(Path rootDir) {
            super(rootDir.toFile());
        }

        @Override
        public void refreshWorldContext() {
        }
    }
}
