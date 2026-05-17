package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteResult;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteService;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactory;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadCancelPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlanner;
import com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshotService;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RoadPlannerMapPreloadService {
    private static final int MAX_TILES_PER_TICK = 4;
    private static final RoadMapRoutePreloadPlanner ROUTE_PLANNER = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
    private static RoadPlannerMapPreloadService GLOBAL = new RoadPlannerMapPreloadService();

    private final Map<JobKey, ActiveJob> jobs = new LinkedHashMap<>();
    private MinecraftServer server;

    public static RoadPlannerMapPreloadService global() {
        return GLOBAL;
    }

    public static void onServerStarted(MinecraftServer server) {
        GLOBAL = new RoadPlannerMapPreloadService();
        GLOBAL.server = server;
    }

    public static void onServerStopped() {
        GLOBAL.jobs.clear();
        GLOBAL.server = null;
    }

    public void enqueue(ServerPlayer player, RoadPlannerMapPreloadRequestPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        RoadPlannerMapPreloadRequestPacket.Purpose purpose = packet.purpose();
        RoadMapRoutePreloadPlan plan = resolvePlan(player.serverLevel(), packet);
        JobKey key = new JobKey(packet.sessionId(), purpose);
        jobs.put(key, new ActiveJob(player.getUUID(), packet.sessionId(), packet.requestId(), purpose, packet.worldId(), packet.dimensionId(), new RoadPlannerMapPreloadJob(
                packet.sessionId(),
                packet.requestId(),
                purpose,
                packet.worldId(),
                packet.dimensionId(),
                plan)));
        sendProgress(player, jobs.get(key).job.progress());
    }

    public void cancel(ServerPlayer player, RoadPlannerMapPreloadCancelPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        JobKey key = new JobKey(packet.sessionId(), packet.purpose());
        ActiveJob job = jobs.remove(key);
        if (job != null && job.requestId == packet.requestId()) {
            job.job.cancel("已取消");
            sendProgress(player, job.job.progress());
        } else if (job != null) {
            jobs.put(key, job);
        }
    }

    public void tick(ServerLevel level) {
        if (server == null || level == null) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        List<Map.Entry<JobKey, ActiveJob>> snapshot = new ArrayList<>(jobs.entrySet());
        for (Map.Entry<JobKey, ActiveJob> entry : snapshot) {
            ActiveJob active = entry.getValue();
            if (!Objects.equals(active.job.dimensionId(), dimensionId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(active.playerId);
            if (player == null) {
                jobs.remove(entry.getKey());
                continue;
            }
            int processedThisTick = 0;
            while (processedThisTick < MAX_TILES_PER_TICK && !active.job.isFinished()) {
                int processed = active.job.advance(1, key -> buildSnapshot(level, key, active.job), packet -> sendTile(player, packet));
                if (processed <= 0) {
                    break;
                }
                processedThisTick += processed;
                sendProgress(player, active.job.progress());
            }
            if (active.job.isFinished() && processedThisTick == 0) {
                sendProgress(player, active.job.progress());
            }
            if (active.job.isFinished()) {
                jobs.remove(entry.getKey());
            }
        }
    }

    private RoadMapRoutePreloadPlan resolvePlan(ServerLevel level, RoadPlannerMapPreloadRequestPacket packet) {
        if (packet.purpose() == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER) {
            return ROUTE_PLANNER.planSelection(packet.start(), packet.destination());
        }
        return ROUTE_PLANNER.plan(resolveRouteNodes(level, packet));
    }

    private List<BlockPos> resolveRouteNodes(ServerLevel level, RoadPlannerMapPreloadRequestPacket packet) {
        List<BlockPos> nodes = packet.routeNodes();
        if (!nodes.isEmpty()) {
            return nodes;
        }
        RoadPlannerAutoCompleteService service = RoadPlannerPathfinderRunnerFactory.serverService(level, com.monpai.sailboatmod.road.config.PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR);
        if (service == null) {
            service = new RoadPlannerAutoCompleteService();
        }
        RoadPlannerAutoCompleteResult result = service.complete(packet.start(), packet.destination(), List.of(), 8);
        if (result.success() && !result.nodes().isEmpty()) {
            return result.nodes();
        }
        return List.of(packet.start(), packet.destination());
    }

    private RoadMapSnapshot buildSnapshot(ServerLevel level, RoadPlannerTileKey key, RoadPlannerMapPreloadJob job) {
        BlockPos center = new BlockPos(
                key.tileX() * RoadMapTileSpec.TILE_BLOCKS + RoadMapTileSpec.TILE_BLOCKS / 2,
                0,
                key.tileZ() * RoadMapTileSpec.TILE_BLOCKS + RoadMapTileSpec.TILE_BLOCKS / 2);
        forceLoadCoveredChunks(level, key, job);
        RoadMapRegion region = RoadMapRegion.centeredOn(center, RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1);
        RoadMapSnapshotService service = RoadMapSnapshotService.directExecutorForTest(new RoadMapColorizer());
        RoadMapServerColumnSampler delegate = new RoadMapServerColumnSampler(level);
        RoadMapColumnSampler sampler = (worldX, worldZ) -> {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            if (job != null && !job.coversChunk(chunkX, chunkZ)) {
                return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
            }
            return delegate.sample(worldX, worldZ);
        };
        return service.buildSnapshotAsync(level.getGameTime(), region, sampler).join();
    }

    private void forceLoadCoveredChunks(ServerLevel level, RoadPlannerTileKey key, RoadPlannerMapPreloadJob job) {
        int startChunkX = key.tileX() * 16;
        int startChunkZ = key.tileZ() * 16;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int chunkX = startChunkX + localX;
                int chunkZ = startChunkZ + localZ;
                if (job == null || job.coversChunk(chunkX, chunkZ)) {
                    level.getChunk(chunkX, chunkZ);
                }
            }
        }
    }

    private void sendTile(ServerPlayer player, RoadPlannerMapTileSyncPacket packet) {
        ModNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private void sendProgress(ServerPlayer player, RoadPlannerMapPreloadProgressPacket packet) {
        ModNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private record JobKey(UUID sessionId, RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        private JobKey {
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            purpose = purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose;
        }
    }

    private record ActiveJob(UUID playerId,
                             UUID sessionId,
                             long requestId,
                             RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                             String worldId,
                             String dimensionId,
                             RoadPlannerMapPreloadJob job) {
        private ActiveJob {
            playerId = playerId == null ? new UUID(0L, 0L) : playerId;
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            worldId = worldId == null ? "" : worldId;
            dimensionId = dimensionId == null ? "" : dimensionId;
        }
    }
}
