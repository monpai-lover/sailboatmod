package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteResult;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteService;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactory;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import com.monpai.sailboatmod.map.SharedMapServerState;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.network.NetworkDirection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RoadPlannerMapPreloadService {
    private static final int MAX_FORCE_CHUNKS_PER_TICK = 8;
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
        for (ActiveJob active : List.copyOf(GLOBAL.jobs.values())) {
            GLOBAL.releaseAllForcedChunks(active);
        }
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
        ActiveJob activeJob = new ActiveJob(player.getUUID(), packet.sessionId(), packet.requestId(), purpose, packet.worldId(), packet.dimensionId(), new RoadPlannerMapPreloadJob(
                packet.sessionId(),
                packet.requestId(),
                purpose,
                packet.worldId(),
                packet.dimensionId(),
                plan));
        replaceJob(key, activeJob);
        sendProgress(player, activeJob.job.progress());
    }

    public void enqueueBuiltRoadRefresh(ServerLevel level, Collection<net.minecraft.world.level.ChunkPos> chunks) {
        if (level == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        MinecraftServer activeServer = server == null ? level.getServer() : server;
        if (activeServer == null) {
            return;
        }
        if (server == null) {
            server = activeServer;
        }
        LinkedHashSet<net.minecraft.world.level.ChunkPos> orderedChunks = new LinkedHashSet<>();
        for (net.minecraft.world.level.ChunkPos chunk : chunks) {
            if (chunk != null) {
                orderedChunks.add(chunk);
            }
        }
        if (orderedChunks.isEmpty()) {
            return;
        }
        RoadMapRoutePreloadPlan plan = new RoadMapRoutePreloadPlan(
                RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY,
                List.copyOf(orderedChunks),
                orderedChunks.size(),
                orderedChunks.size());
        String dimensionId = level.dimension().location().toString();
        long requestId = Math.max(1L, level.getGameTime());
        for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
            if (player == null || !(player.level() instanceof ServerLevel playerLevel)
                    || !Objects.equals(playerLevel.dimension(), level.dimension())) {
                continue;
            }
            UUID sessionId = UUID.randomUUID();
            ActiveJob activeJob = new ActiveJob(
                    player.getUUID(),
                    sessionId,
                    requestId,
                    RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                    "",
                    dimensionId,
                    new RoadPlannerMapPreloadJob(
                            sessionId,
                            requestId,
                            RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                            "",
                            dimensionId,
                            plan));
            JobKey key = new JobKey(sessionId, RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH);
            replaceJob(key, activeJob);
            sendProgress(player, activeJob.job.progress());
        }
    }

    private void replaceJob(JobKey key, ActiveJob activeJob) {
        ActiveJob previous = jobs.put(key, activeJob);
        if (previous != null && previous != activeJob) {
            releaseAllForcedChunks(previous);
            previous.job.cancel("replaced by newer request");
        }
    }

    public void cancel(ServerPlayer player, RoadPlannerMapPreloadCancelPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        JobKey key = new JobKey(packet.sessionId(), packet.purpose());
        ActiveJob job = jobs.remove(key);
        if (job != null && job.requestId == packet.requestId()) {
            releaseAllForcedChunks(job);
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
        RoadPlannerMapPreloadBudget tileBudget = new RoadPlannerMapPreloadBudget(
                RoadPlannerMapPreloadBudget.DEFAULT_GLOBAL_TILE_BUDGET_PER_LEVEL_TICK);
        for (Map.Entry<JobKey, ActiveJob> entry : snapshot) {
            ActiveJob active = entry.getValue();
            if (!Objects.equals(active.job.dimensionId(), dimensionId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(active.playerId);
            if (player == null) {
                releaseAllForcedChunks(active);
                jobs.remove(entry.getKey());
                continue;
            }
            int maxTilesForJob = tileBudget.claim(active.job.purpose());
            int processedThisTick = 0;
            while (processedThisTick < maxTilesForJob && !active.job.isFinished()) {
                int processed = active.job.advance(1, key -> buildSnapshot(level, key, active), packet -> sendTile(player, packet));
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
                releaseAllForcedChunks(active);
                jobs.remove(entry.getKey());
            }
            if (tileBudget.remaining() <= 0) {
                break;
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
        RoadPlannerAutoCompleteService service = RoadPlannerPathfinderRunnerFactory.serverService(level);
        if (service == null) {
            service = new RoadPlannerAutoCompleteService();
        }
        RoadPlannerAutoCompleteResult result = service.complete(packet.start(), packet.destination(), List.of(), 8);
        if (result.success() && !result.nodes().isEmpty()) {
            return result.nodes();
        }
        return List.of(packet.start(), packet.destination());
    }

    private RoadMapSnapshot buildSnapshot(ServerLevel level, RoadPlannerTileKey key, ActiveJob active) {
        if (!prepareCoveredChunks(level, key, active)) {
            return null;
        }
        BlockPos center = new BlockPos(
                key.tileX() * RoadMapTileSpec.TILE_BLOCKS + RoadMapTileSpec.TILE_BLOCKS / 2,
                0,
                key.tileZ() * RoadMapTileSpec.TILE_BLOCKS + RoadMapTileSpec.TILE_BLOCKS / 2);
        RoadMapRegion region = RoadMapRegion.centeredOn(center, RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1);
        RoadMapSnapshotService service = RoadMapSnapshotService.directExecutorForTest(new RoadMapColorizer());
        RoadMapServerColumnSampler delegate = new RoadMapServerColumnSampler(level);
        RoadMapColumnSampler sampler = (worldX, worldZ) -> {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            if (active != null && active.job != null && !active.job.coversChunk(chunkX, chunkZ)) {
                return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
            }
            return delegate.sample(worldX, worldZ);
        };
        RoadMapSnapshot snapshot = service.buildSnapshotAsync(level.getGameTime(), region, sampler).join();
        releaseForcedTile(level, active, key);
        return snapshot;
    }

    private boolean prepareCoveredChunks(ServerLevel level, RoadPlannerTileKey key, ActiveJob active) {
        if (level == null || key == null || active == null || active.job == null
                || active.job.purpose() != RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER) {
            return true;
        }
        Set<Long> forcedForTile = active.forcedChunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        int tileStartChunkX = key.tileX() * (RoadMapTileSpec.TILE_BLOCKS / 16);
        int tileStartChunkZ = key.tileZ() * (RoadMapTileSpec.TILE_BLOCKS / 16);
        int startedThisTick = 0;
        boolean allLoaded = true;
        BlockPos owner = forceOwnerPosition(key, level);
        for (int dz = 0; dz < RoadMapTileSpec.TILE_BLOCKS / 16; dz++) {
            for (int dx = 0; dx < RoadMapTileSpec.TILE_BLOCKS / 16; dx++) {
                int chunkX = tileStartChunkX + dx;
                int chunkZ = tileStartChunkZ + dz;
                if (!active.job.coversChunk(chunkX, chunkZ)) {
                    continue;
                }
                if (isChunkLoaded(level, chunkX, chunkZ)) {
                    continue;
                }
                allLoaded = false;
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                if (forcedForTile.contains(chunkKey) || startedThisTick >= MAX_FORCE_CHUNKS_PER_TICK) {
                    continue;
                }
                if (ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, true, false)) {
                    forcedForTile.add(chunkKey);
                    startedThisTick++;
                }
            }
        }
        return allLoaded;
    }

    private void releaseForcedTile(ServerLevel level, ActiveJob active, RoadPlannerTileKey key) {
        if (level == null || active == null || key == null) {
            return;
        }
        Set<Long> forced = active.forcedChunks.remove(key);
        if (forced == null || forced.isEmpty()) {
            return;
        }
        BlockPos owner = forceOwnerPosition(key, level);
        for (Long chunkKey : forced) {
            if (chunkKey == null) {
                continue;
            }
            ForgeChunkManager.forceChunk(
                    level,
                    SailboatMod.MODID,
                    owner,
                    ChunkPos.getX(chunkKey),
                    ChunkPos.getZ(chunkKey),
                    false,
                    false);
        }
    }

    private void releaseAllForcedChunks(ActiveJob active) {
        if (active == null || active.forcedChunks.isEmpty()) {
            return;
        }
        ServerLevel level = resolveLevel(active.dimensionId);
        if (level == null) {
            active.forcedChunks.clear();
            return;
        }
        for (RoadPlannerTileKey key : List.copyOf(active.forcedChunks.keySet())) {
            releaseForcedTile(level, active, key);
        }
        active.forcedChunks.clear();
    }

    private ServerLevel resolveLevel(String dimensionId) {
        if (server == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) {
            return null;
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return server.getLevel(key);
    }

    private static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        try {
            return level.getChunkSource().getChunk(chunkX, chunkZ, false) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static BlockPos forceOwnerPosition(RoadPlannerTileKey key, ServerLevel level) {
        return new BlockPos(
                key.tileX() * RoadMapTileSpec.TILE_BLOCKS,
                level == null ? 0 : level.getMinBuildHeight(),
                key.tileZ() * RoadMapTileSpec.TILE_BLOCKS);
    }

    private void sendTile(ServerPlayer player, RoadPlannerMapTileSyncPacket packet) {
        SharedMapServerState.markRendered(packet);
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
                             RoadPlannerMapPreloadJob job,
                             Map<RoadPlannerTileKey, Set<Long>> forcedChunks) {
        private ActiveJob(UUID playerId,
                          UUID sessionId,
                          long requestId,
                          RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                          String worldId,
                          String dimensionId,
                          RoadPlannerMapPreloadJob job) {
            this(playerId, sessionId, requestId, purpose, worldId, dimensionId, job, new LinkedHashMap<>());
        }

        private ActiveJob {
            playerId = playerId == null ? new UUID(0L, 0L) : playerId;
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            worldId = worldId == null ? "" : worldId;
            dimensionId = dimensionId == null ? "" : dimensionId;
            forcedChunks = forcedChunks == null ? new LinkedHashMap<>() : forcedChunks;
        }
    }
}
