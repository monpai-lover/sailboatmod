package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapCanvas;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapComponent;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapLayout;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerVanillaLayout;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.UUID;

public final class ClaimWorldMapView implements AutoCloseable {
    public static final MapLod RENDER_LOD = MapLod.LOD_1;

    private final UUID sessionId = UUID.randomUUID();
    private final boolean createDefaultTileManager;
    private RoadPlannerTileManager tileManager;
    private RoadPlannerMapView view;
    private long nextRequestId = 1L;
    private boolean initialForceRenderRequested;

    public ClaimWorldMapView() {
        this(null, true);
    }

    private ClaimWorldMapView(RoadPlannerTileManager tileManager, boolean createDefaultTileManager) {
        this.tileManager = tileManager;
        this.createDefaultTileManager = createDefaultTileManager;
    }

    public static ClaimWorldMapView forTest(int centerChunkX, int centerChunkZ, int radius, int width, int height) {
        ClaimWorldMapView mapView = new ClaimWorldMapView(null, false);
        mapView.centerOnChunk(centerChunkX, centerChunkZ, radius, width, height, false);
        return mapView;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public int centerChunkX() {
        ensureView(0, 0, 0, 1, 1);
        return Math.floorDiv((int) Math.floor(view.centerX()), 16);
    }

    public int centerChunkZ() {
        ensureView(0, 0, 0, 1, 1);
        return Math.floorDiv((int) Math.floor(view.centerZ()), 16);
    }

    public double scale() {
        ensureView(0, 0, 0, 1, 1);
        return view.scale();
    }

    public void centerOnChunk(int centerChunkX, int centerChunkZ, int radius, int width, int height, boolean preserveScale) {
        double nextScale = preserveScale && view != null ? view.scale() : fitScale(radius, width, height);
        this.view = RoadPlannerMapView.centered(chunkCenterBlock(centerChunkX), chunkCenterBlock(centerChunkZ), nextScale);
        this.initialForceRenderRequested = false;
    }

    public void ensureView(int centerChunkX, int centerChunkZ, int radius, int width, int height) {
        if (view == null) {
            centerOnChunk(centerChunkX, centerChunkZ, radius, width, height, false);
        }
    }

    public void renderBase(GuiGraphics graphics,
                           Font font,
                           int mapX,
                           int mapY,
                           int width,
                           int height,
                           int fallbackCenterChunkX,
                           int fallbackCenterChunkZ,
                           int radius) {
        ensureView(fallbackCenterChunkX, fallbackCenterChunkZ, radius, width, height);
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(mapX, mapY, width, height);
        RoadPlannerMapComponent component = new RoadPlannerMapComponent(
                RoadMapRegion.centeredOn(BlockPos.ZERO, 128, RENDER_LOD),
                new RoadMapViewport(mapX, mapY, width, height)
        );
        new RoadPlannerMapCanvas(rect, component, view, tileManager()).render(graphics, font);
    }

    public void panByScreenDelta(double deltaX, double deltaY) {
        ensureView(0, 0, 0, 1, 1);
        view.panByScreenDelta(deltaX, deltaY);
    }

    public void zoomAround(double screenX, double screenY, double factor, int mapX, int mapY, int width, int height) {
        ensureView(0, 0, 0, width, height);
        view.zoomAround(screenX, screenY, factor, mapRect(mapX, mapY, width, height));
    }

    public ChunkPos screenToChunk(double screenX, double screenY, int mapX, int mapY, int width, int height) {
        ensureView(0, 0, 0, width, height);
        RoadPlannerMapLayout.Rect rect = mapRect(mapX, mapY, width, height);
        int worldX = view.screenToWorldX(screenX, rect);
        int worldZ = view.screenToWorldZ(screenY, rect);
        return new ChunkPos(Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16));
    }

    public ScreenRect chunkScreenRect(int chunkX, int chunkZ, int mapX, int mapY, int width, int height) {
        ensureView(0, 0, 0, width, height);
        RoadPlannerMapLayout.Rect rect = mapRect(mapX, mapY, width, height);
        int pixelSize = Math.max(1, (int) Math.ceil(16.0D * view.scale()));
        int centerX = view.worldToScreenX(chunkCenterBlock(chunkX), rect);
        int centerY = view.worldToScreenZ(chunkCenterBlock(chunkZ), rect);
        return new ScreenRect(centerX - pixelSize / 2, centerY - pixelSize / 2, pixelSize, pixelSize);
    }

    public ChunkBounds visibleChunkBounds(int mapX, int mapY, int width, int height) {
        ensureView(0, 0, 0, width, height);
        RoadPlannerMapLayout.Rect rect = mapRect(mapX, mapY, width, height);
        int minWorldX = Math.min(view.screenToWorldX(mapX, rect), view.screenToWorldX(mapX + width, rect));
        int maxWorldX = Math.max(view.screenToWorldX(mapX, rect), view.screenToWorldX(mapX + width, rect));
        int minWorldZ = Math.min(view.screenToWorldZ(mapY, rect), view.screenToWorldZ(mapY + height, rect));
        int maxWorldZ = Math.max(view.screenToWorldZ(mapY, rect), view.screenToWorldZ(mapY + height, rect));
        return new ChunkBounds(
                Math.floorDiv(minWorldX, 16),
                Math.floorDiv(maxWorldX, 16),
                Math.floorDiv(minWorldZ, 16),
                Math.floorDiv(maxWorldZ, 16)
        );
    }

    public boolean markInitialForceRenderRequested() {
        if (initialForceRenderRequested) {
            return false;
        }
        initialForceRenderRequested = true;
        return true;
    }

    public RoadPlannerMapPreloadRequestPacket createVisibleForceRenderRequest(String worldId,
                                                                             String dimensionId,
                                                                             int mapX,
                                                                             int mapY,
                                                                             int width,
                                                                             int height) {
        ensureView(0, 0, 0, width, height);
        RoadPlannerMapLayout.Rect rect = mapRect(mapX, mapY, width, height);
        int minWorldX = Math.min(view.screenToWorldX(mapX, rect), view.screenToWorldX(mapX + width, rect));
        int maxWorldX = Math.max(view.screenToWorldX(mapX, rect), view.screenToWorldX(mapX + width, rect));
        int minWorldZ = Math.min(view.screenToWorldZ(mapY, rect), view.screenToWorldZ(mapY + height, rect));
        int maxWorldZ = Math.max(view.screenToWorldZ(mapY, rect), view.screenToWorldZ(mapY + height, rect));
        BlockPos start = new BlockPos(minWorldX, 64, minWorldZ);
        BlockPos destination = new BlockPos(maxWorldX, 64, maxWorldZ);
        return new RoadPlannerMapPreloadRequestPacket(
                sessionId,
                nextRequestId++,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                worldId == null ? "" : worldId,
                dimensionId == null ? "" : dimensionId,
                start,
                destination,
                List.of(start, destination),
                RoadPlannerMapPreloadRequestPacket.PROTOCOL_VERSION
        );
    }

    public int applyTileSync(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null || !sessionId.equals(packet.sessionId())) {
            return 0;
        }
        RoadPlannerTileManager manager = tileManager();
        return manager == null ? 0 : manager.applyTileSync(packet);
    }

    public String worldId() {
        RoadPlannerTileManager manager = tileManager();
        return manager == null ? "" : manager.worldId();
    }

    public String dimensionId() {
        RoadPlannerTileManager manager = tileManager();
        return manager == null ? "" : manager.dimensionId();
    }

    private RoadPlannerTileManager tileManager() {
        if (tileManager == null && createDefaultTileManager) {
            tileManager = RoadPlannerTileManager.createDefault();
        }
        if (tileManager != null) {
            tileManager.refreshWorldContext();
        }
        return tileManager;
    }

    private static double fitScale(int radius, int width, int height) {
        int diameterChunks = Math.max(1, radius * 2 + 1);
        double blocks = diameterChunks * 16.0D;
        double pixels = Math.max(1, Math.min(width, height));
        return pixels / blocks;
    }

    private static double chunkCenterBlock(int chunk) {
        return chunk * 16.0D + 8.0D;
    }

    private static RoadPlannerMapLayout.Rect mapRect(int mapX, int mapY, int width, int height) {
        return new RoadPlannerMapLayout.Rect(mapX, mapY, width, height);
    }

    @Override
    public void close() {
        if (tileManager != null) {
            tileManager.close();
            tileManager = null;
        }
    }

    public record ChunkBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
    }

    public record ScreenRect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }
}
