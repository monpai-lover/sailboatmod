package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class RoadPlannerMinimapRequestScheduler {
    public static final int MIN_REGION_SIZE = 128;
    public static final int MAX_REGION_SIZE = 512;

    private final long viewportThrottleMs;
    private long nextRequestId = 1L;
    private long lastViewportRequestAtMs = Long.MIN_VALUE;
    private Request latestRequest;

    public RoadPlannerMinimapRequestScheduler(long viewportThrottleMs) {
        this.viewportThrottleMs = Math.max(0L, viewportThrottleMs);
    }

    public Optional<Request> initialRequest(long nowMs,
                                            UUID sessionId,
                                            String worldId,
                                            String dimensionId,
                                            RoadPlannerMapView view,
                                            RoadPlannerMapLayout.Rect map) {
        return Optional.of(recordRequest(nowMs, sessionId, worldId, dimensionId,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT, regionForView(view, map)));
    }

    public Optional<Request> viewportRequest(long nowMs,
                                             UUID sessionId,
                                             String worldId,
                                             String dimensionId,
                                             RoadPlannerMapView view,
                                             RoadPlannerMapLayout.Rect map) {
        Region region = regionForView(view, map);
        if (latestRequest != null
                && latestRequest.regionCenter().equals(region.center())
                && latestRequest.regionSize() == region.size()) {
            return Optional.empty();
        }
        if (lastViewportRequestAtMs != Long.MIN_VALUE && nowMs - lastViewportRequestAtMs < viewportThrottleMs) {
            return Optional.empty();
        }
        return Optional.of(recordRequest(nowMs, sessionId, worldId, dimensionId,
                RoadMapSnapshotRequestPacket.Purpose.VIEWPORT, region));
    }

    public Optional<Request> forceRenderRequest(long nowMs,
                                                UUID sessionId,
                                                String worldId,
                                                String dimensionId,
                                                BlockPos a,
                                                BlockPos b) {
        return Optional.of(recordRequest(nowMs, sessionId, worldId, dimensionId,
                RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER, regionForSelection(a, b)));
    }

    public boolean acceptsResponse(long requestId,
                                   RoadMapSnapshotRequestPacket.Purpose purpose,
                                   BlockPos regionCenter,
                                   int regionSize) {
        return latestRequest != null
                && latestRequest.requestId() == requestId
                && latestRequest.purpose() == purpose
                && latestRequest.regionCenter().equals(regionCenter == null ? BlockPos.ZERO : regionCenter.immutable())
                && latestRequest.regionSize() == regionSize;
    }

    public Request latestRequest() {
        return latestRequest;
    }

    public void markCompleted(long requestId) {
        if (latestRequest != null && latestRequest.requestId() == requestId) {
            latestRequest = latestRequest.withCompleted(true);
        }
    }

    private Request recordRequest(long nowMs,
                                  UUID sessionId,
                                  String worldId,
                                  String dimensionId,
                                  RoadMapSnapshotRequestPacket.Purpose purpose,
                                  Region region) {
        if (purpose != RoadMapSnapshotRequestPacket.Purpose.FORCE_RENDER) {
            lastViewportRequestAtMs = nowMs;
        }
        Request request = new Request(
                nextRequestId++,
                sessionId == null ? new UUID(0L, 0L) : sessionId,
                worldId == null ? "" : worldId,
                dimensionId == null ? "" : dimensionId,
                purpose == null ? RoadMapSnapshotRequestPacket.Purpose.VIEWPORT : purpose,
                region.center(),
                region.size(),
                MapLod.LOD_4,
                false);
        latestRequest = request;
        return request;
    }

    private static Region regionForView(RoadPlannerMapView view, RoadPlannerMapLayout.Rect map) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(map, "map");
        int minWorldX = view.screenToWorldX(map.x(), map);
        int maxWorldX = view.screenToWorldX(map.right(), map);
        int minWorldZ = view.screenToWorldZ(map.y(), map);
        int maxWorldZ = view.screenToWorldZ(map.bottom(), map);
        int spanX = Math.abs(maxWorldX - minWorldX);
        int spanZ = Math.abs(maxWorldZ - minWorldZ);
        int size = clampRegionSize(nextPowerOfTwo(Math.max(MIN_REGION_SIZE, Math.max(spanX, spanZ))));
        int centerX = (minWorldX + maxWorldX) / 2;
        int centerZ = (minWorldZ + maxWorldZ) / 2;
        return new Region(new BlockPos(centerX, 0, centerZ), size);
    }

    private static Region regionForSelection(BlockPos a, BlockPos b) {
        BlockPos safeA = a == null ? BlockPos.ZERO : a;
        BlockPos safeB = b == null ? safeA : b;
        int minX = Math.min(safeA.getX(), safeB.getX());
        int maxX = Math.max(safeA.getX(), safeB.getX());
        int minZ = Math.min(safeA.getZ(), safeB.getZ());
        int maxZ = Math.max(safeA.getZ(), safeB.getZ());
        int size = clampRegionSize(nextPowerOfTwo(Math.max(MIN_REGION_SIZE, Math.max(maxX - minX + 1, maxZ - minZ + 1))));
        return new Region(new BlockPos((minX + maxX) / 2, 0, (minZ + maxZ) / 2), size);
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static int clampRegionSize(int value) {
        return Math.max(MIN_REGION_SIZE, Math.min(MAX_REGION_SIZE, value));
    }

    private record Region(BlockPos center, int size) {
        private Region {
            center = center == null ? BlockPos.ZERO : center.immutable();
            size = clampRegionSize(size);
        }
    }

    public record Request(long requestId,
                          UUID sessionId,
                          String worldId,
                          String dimensionId,
                          RoadMapSnapshotRequestPacket.Purpose purpose,
                          BlockPos regionCenter,
                          int regionSize,
                          MapLod lod,
                          boolean completed) {
        public Request {
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            worldId = worldId == null ? "" : worldId;
            dimensionId = dimensionId == null ? "" : dimensionId;
            purpose = purpose == null ? RoadMapSnapshotRequestPacket.Purpose.VIEWPORT : purpose;
            regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
            regionSize = clampRegionSize(regionSize);
            lod = lod == null ? MapLod.LOD_4 : lod;
        }

        public Request withCompleted(boolean nextCompleted) {
            return new Request(requestId, sessionId, worldId, dimensionId, purpose, regionCenter, regionSize, lod, nextCompleted);
        }

        public RoadMapSnapshotRequestPacket toPacket() {
            return new RoadMapSnapshotRequestPacket(sessionId, worldId, dimensionId, requestId, purpose, regionCenter, regionSize, lod);
        }
    }
}
