package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapLodPyramid;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class RoadPlannerMapPreloadJob {
    private final UUID sessionId;
    private final long requestId;
    private final RoadPlannerMapPreloadRequestPacket.Purpose purpose;
    private final String worldId;
    private final String dimensionId;
    private final RoadMapRoutePreloadPlan.CoverageMode coverageMode;
    private final Set<Long> coveredChunks;
    private final Deque<RoadPlannerTileKey> pendingKeys;
    private final int totalTiles;
    private int completedTiles;
    private RoadPlannerMapPreloadProgressPacket.State state;
    private String message;

    public RoadPlannerMapPreloadJob(UUID sessionId,
                                    long requestId,
                                    RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                    String worldId,
                                    String dimensionId,
                                    RoadMapRoutePreloadPlan plan) {
        this.sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        this.requestId = requestId;
        this.purpose = purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose;
        this.worldId = worldId == null ? "" : worldId;
        this.dimensionId = dimensionId == null ? "" : dimensionId;
        this.coverageMode = plan == null ? RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY : plan.coverageMode();
        this.coveredChunks = plan == null
                ? Set.of()
                : plan.chunks().stream().map(ChunkPos::toLong).collect(Collectors.toUnmodifiableSet());
        List<RoadPlannerTileKey> keys = plan == null ? List.of() : plan.tileKeys(this.worldId, this.dimensionId, MapLod.LOD_1);
        this.pendingKeys = new ArrayDeque<>(keys);
        this.totalTiles = keys.size();
        this.completedTiles = 0;
        if (keys.isEmpty()) {
            this.state = RoadPlannerMapPreloadProgressPacket.State.COMPLETE;
            this.message = "没有需要预加载的区块";
        } else if (this.coverageMode == RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY) {
            this.state = RoadPlannerMapPreloadProgressPacket.State.DEGRADED_PATH_ONLY;
            this.message = "已降级为路径区块预加载";
        } else {
            this.state = RoadPlannerMapPreloadProgressPacket.State.QUEUED;
            this.message = "等待预加载";
        }
    }

    public int advance(int maxTiles,
                       TileSource source,
                       TileSink sink) {
        if (isTerminal() || maxTiles <= 0) {
            return 0;
        }
        state = RoadPlannerMapPreloadProgressPacket.State.SAMPLING;
        int processed = 0;
        while (processed < maxTiles && !pendingKeys.isEmpty() && !isTerminal()) {
            RoadPlannerTileKey key = pendingKeys.pollFirst();
            if (key == null) {
                break;
            }
            RoadMapSnapshot snapshot = source == null ? null : source.load(key);
            if (snapshot == null || snapshot.region() == null) {
                state = RoadPlannerMapPreloadProgressPacket.State.FAILED;
                message = "缺少 LOD_1 快照";
                break;
            }
            int[] sourcePixels = snapshot.argbPixels();
            int width = snapshot.region().pixelWidth();
            int height = snapshot.region().pixelHeight();
            if (sourcePixels.length < width * height) {
                state = RoadPlannerMapPreloadProgressPacket.State.FAILED;
                message = "快照像素不足";
                break;
            }
            emitTile(key, snapshot, MapLod.LOD_1, sourcePixels, width, height, sink);
            state = RoadPlannerMapPreloadProgressPacket.State.DERIVING;
            for (MapLod lod : List.of(MapLod.LOD_2, MapLod.LOD_4, MapLod.LOD_8)) {
                emitTile(key, snapshot, lod, sourcePixels, width, height, sink);
            }
            completedTiles++;
            processed++;
            message = "已处理 " + completedTiles + "/" + totalTiles;
            if (pendingKeys.isEmpty()) {
                state = RoadPlannerMapPreloadProgressPacket.State.COMPLETE;
                message = "预加载完成";
            } else if (coverageMode == RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY) {
                state = RoadPlannerMapPreloadProgressPacket.State.DEGRADED_PATH_ONLY;
            } else {
                state = RoadPlannerMapPreloadProgressPacket.State.SAMPLING;
            }
        }
        if (pendingKeys.isEmpty() && !isTerminal()) {
            state = RoadPlannerMapPreloadProgressPacket.State.COMPLETE;
            message = "预加载完成";
        }
        return processed;
    }

    public void cancel(String reason) {
        state = RoadPlannerMapPreloadProgressPacket.State.CANCELLED;
        message = reason == null || reason.isBlank() ? "已取消" : reason;
        pendingKeys.clear();
    }

    public boolean isFinished() {
        return isTerminal() || pendingKeys.isEmpty();
    }

    public RoadPlannerMapPreloadProgressPacket progress() {
        return new RoadPlannerMapPreloadProgressPacket(
                sessionId,
                requestId,
                purpose,
                worldId,
                dimensionId,
                coverageMode,
                completedTiles,
                totalTiles,
                state,
                message);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public long requestId() {
        return requestId;
    }

    public RoadPlannerMapPreloadRequestPacket.Purpose purpose() {
        return purpose;
    }

    public String worldId() {
        return worldId;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public boolean coversChunk(int chunkX, int chunkZ) {
        return coveredChunks.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    private void emitTile(RoadPlannerTileKey key,
                          RoadMapSnapshot snapshot,
                          MapLod lod,
                          int[] sourcePixels,
                          int width,
                          int height,
                          Consumer<RoadPlannerMapTileSyncPacket> sink) {
        if (sink == null) {
            return;
        }
        int[] pixels = lod == MapLod.LOD_1
                ? sourcePixels
                : RoadMapLodPyramid.deriveDisplayPixels(sourcePixels, width, height, lod);
        sink.accept(new RoadPlannerMapTileSyncPacket(
                sessionId,
                requestId,
                purpose,
                worldId,
                dimensionId,
                lod,
                key.tileX(),
                key.tileZ(),
                width,
                height,
                pixels));
    }

    private boolean isTerminal() {
        return state == RoadPlannerMapPreloadProgressPacket.State.COMPLETE
                || state == RoadPlannerMapPreloadProgressPacket.State.FAILED
                || state == RoadPlannerMapPreloadProgressPacket.State.CANCELLED;
    }

    @FunctionalInterface
    public interface TileSource {
        RoadMapSnapshot load(RoadPlannerTileKey key);
    }

    @FunctionalInterface
    public interface TileSink extends Consumer<RoadPlannerMapTileSyncPacket> {
        @Override
        void accept(RoadPlannerMapTileSyncPacket packet);
    }
}
