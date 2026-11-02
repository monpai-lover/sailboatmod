package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import com.monpai.sailboatmod.map.SharedMapServerState;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadCancelPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlanner;
import com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
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
import com.monpai.sailboatmod.util.ModChunkLoading;

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
    private static final int MAX_FORCE_CHUNKS_PER_TICK = 12;
    static final int MAX_JOBS_PER_PLAYER = 2;
    static final int MAX_JOBS = 32;
    private static final long JOB_TTL_TICKS = 12000;
    private static final int COLUMNS_PER_TICK = 512;
    private static final long SAMPLE_SLICE_NANOS = 2_000_000L;
    private static final RoadMapRoutePreloadPlanner ROUTE_PLANNER = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
    private static RoadPlannerMapPreloadService GLOBAL = new RoadPlannerMapPreloadService();

    private final Map<JobKey, ActiveJob> jobs = new LinkedHashMap<>();
    private MinecraftServer server;
    private final Map<JobKey, Long> created = new LinkedHashMap<>();
    private final Map<JobKey, RoadPlannerMapRaster> sampling = new LinkedHashMap<>();
    private long budgetTick = Long.MIN_VALUE;
    private int columnsRemaining;
    private int forceStarts;
    private long sampleDeadline;
    private long sampleNanosRemaining;

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
        GLOBAL.created.clear();
        GLOBAL.sampling.clear();
        MapRequestLimiter.PRELOAD.clear();
        GLOBAL.server = null;
    }

    public void enqueue(ServerPlayer player, RoadPlannerMapPreloadRequestPacket packet) {
        if (player == null || packet == null || !packet.validClientRequest()
                || !player.serverLevel().dimension().location().toString().equals(packet.dimensionId())) return;
        RoadPlannerMapPreloadRequestPacket.Purpose purpose = packet.purpose();
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER && !player.hasPermissions(2)) {
            finishWithoutTerrainWork(player, packet, RoadPlannerMapPreloadProgressPacket.State.FAILED,
                    "强制渲染仅限管理员使用");
            return;
        }
        JobKey key = new JobKey(player.getUUID(), packet.sessionId(), purpose);
        ActiveJob existing = jobs.get(key);
        if (existing != null && packet.requestId() <= existing.requestId) return;
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER
                && !jobs.containsKey(key) && jobs.size() >= MAX_JOBS
                && jobs.values().stream().noneMatch(job -> job.playerId.equals(player.getUUID()))) {
            finishWithoutTerrainWork(player, packet, RoadPlannerMapPreloadProgressPacket.State.FAILED, "地图任务繁忙，请稍后重试");
            return;
        }
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.VIEWPORT) {
            int minX = Math.floorDiv(Math.min(packet.start().getX(), packet.destination().getX()), 256);
            int maxX = Math.floorDiv(Math.max(packet.start().getX(), packet.destination().getX()), 256);
            int minZ = Math.floorDiv(Math.min(packet.start().getZ(), packet.destination().getZ()), 256);
            int maxZ = Math.floorDiv(Math.max(packet.start().getZ(), packet.destination().getZ()), 256);
            int requested = 0;
            for (int z = minZ; z <= maxZ && requested < 64; z++) {
                for (int x = minX; x <= maxX && requested < 64; x++, requested++) {
                    SharedMapTerrainService.global().request(player,
                            new SharedMapTerrainRequestPacket(packet.worldId(), packet.dimensionId(), x, z));
                }
            }
            return;
        }
        if (automaticMapPurpose(purpose)) {
            finishWithoutTerrainWork(player, packet, RoadPlannerMapPreloadProgressPacket.State.COMPLETE,
                    "地图将随当前视图分段更新");
            return;
        }
        RoadMapRoutePreloadPlan plan = resolvePlan(player.serverLevel(), packet);
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER
                && plan.rectangleChunkCount() > ROUTE_PLANNER.chunkBudget()) {
            finishWithoutTerrainWork(player, packet, RoadPlannerMapPreloadProgressPacket.State.FAILED,
                    "强制渲染选区过大，请缩小选区后重试。");
            return;
        }
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

    private void finishWithoutTerrainWork(ServerPlayer player, RoadPlannerMapPreloadRequestPacket packet,
                                          RoadPlannerMapPreloadProgressPacket.State state, String message) {
        JobKey key = new JobKey(player.getUUID(), packet.sessionId(), packet.purpose());
        ActiveJob previous = jobs.remove(key);
        created.remove(key);
        sampling.remove(key);
        if (previous != null) releaseAllForcedChunks(previous);
        sendProgress(player, new RoadPlannerMapPreloadProgressPacket(packet.sessionId(), packet.requestId(), packet.purpose(),
                packet.worldId(), packet.dimensionId(), RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, 0, 0, state, message));
    }

    private static boolean automaticMapPurpose(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        return purpose == RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD
                || purpose == RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD;
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
                if (orderedChunks.size() >= RoadPlannerMapPreloadRequestPacket.MAX_SELECTION_CHUNKS) break;
            }
        }
        if (orderedChunks.isEmpty()) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        long requestId = Math.max(1L, level.getGameTime());
        for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
            if (player == null || !(player.level() instanceof ServerLevel playerLevel)
                    || !Objects.equals(playerLevel.dimension(), level.dimension())) {
                continue;
            }
            UUID sessionId = UUID.nameUUIDFromBytes(("built-map:" + dimensionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JobKey key = new JobKey(player.getUUID(), sessionId, RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH);
            if (!jobs.containsKey(key) && jobs.size() >= MAX_JOBS) continue;
            LinkedHashSet<ChunkPos> merged = new LinkedHashSet<>(orderedChunks);
            ActiveJob previous = jobs.get(key);
            if (previous != null) for (long chunk : previous.job.coveredChunks()) {
                if (merged.size() >= RoadPlannerMapPreloadRequestPacket.MAX_SELECTION_CHUNKS) break;
                merged.add(new ChunkPos(chunk));
            }
            RoadMapRoutePreloadPlan plan = new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY,
                    List.copyOf(merged), merged.size(), merged.size());
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
            replaceJob(key, activeJob);
            sendProgress(player, activeJob.job.progress());
        }
    }

    private void replaceJob(JobKey key, ActiveJob activeJob) {
        if (!jobs.containsKey(key) && jobs.size() >= MAX_JOBS) {
            JobKey owned = jobs.entrySet().stream().filter(entry -> entry.getValue().playerId.equals(activeJob.playerId))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (owned == null) { activeJob.job.cancel("map queue full"); return; }
            removeJob(owned, "superseded by newer view");
        }
        // Rotating client session ids cannot accumulate jobs; latest views replace the owner's oldest job.
        while (!jobs.containsKey(key) && jobs.values().stream().filter(job -> job.playerId.equals(activeJob.playerId)).count() >= MAX_JOBS_PER_PLAYER) {
            JobKey oldest = jobs.entrySet().stream().filter(entry -> entry.getValue().playerId.equals(activeJob.playerId))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            removeJob(oldest, "superseded by newer view");
        }
        ActiveJob previous = jobs.put(key, activeJob);
        created.put(key, server == null ? 0L : (long) server.getTickCount());
        sampling.remove(key);
        if (previous != null && previous != activeJob) {
            releaseAllForcedChunks(previous);
            previous.job.cancel("replaced by newer request");
        }
    }

    private void removeJob(JobKey key, String reason) {
        ActiveJob active = jobs.remove(key);
        created.remove(key);
        sampling.remove(key);
        if (active != null) {
            releaseAllForcedChunks(active);
            active.job.cancel(reason);
        }
    }

    public void cancel(ServerPlayer player, RoadPlannerMapPreloadCancelPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        JobKey key = new JobKey(player.getUUID(), packet.sessionId(), packet.purpose());
        ActiveJob job = jobs.remove(key);
        if (job != null && job.requestId == packet.requestId()) {
            created.remove(key);
            sampling.remove(key);
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
        long now = server.getTickCount();
        if (budgetTick != now) {
            budgetTick = now;
            columnsRemaining = COLUMNS_PER_TICK;
            forceStarts = 0;
            sampleNanosRemaining = SAMPLE_SLICE_NANOS;
        }
        long sliceStarted = System.nanoTime();
        sampleDeadline = sliceStarted + sampleNanosRemaining;
        String dimensionId = level.dimension().location().toString();
        List<Map.Entry<JobKey, ActiveJob>> snapshot = new ArrayList<>(jobs.entrySet());
        int completedThisTick = 0;
        for (Map.Entry<JobKey, ActiveJob> entry : snapshot) {
            ActiveJob active = entry.getValue();
            if (!Objects.equals(active.job.dimensionId(), dimensionId)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(active.playerId);
            if (player == null || player.serverLevel() != level || now - created.getOrDefault(entry.getKey(), now) >= JOB_TTL_TICKS) {
                removeJob(entry.getKey(), "expired, disconnected, or changed dimension");
                continue;
            }
            if (columnsRemaining <= 0 || System.nanoTime() >= sampleDeadline) continue;
            int maxTilesForJob = completedThisTick < 1 ? 1 : 0;
            int processedThisTick = 0;
            while (processedThisTick < maxTilesForJob && !active.job.isFinished()) {
                int processed = active.job.advance(1, key -> buildSnapshot(level, key, active), packet -> sendTile(player, packet));
                if (processed <= 0) {
                    break;
                }
                processedThisTick += processed;
                completedThisTick += processed;
                sendProgress(player, active.job.progress());
            }
            if (active.job.isFinished() && processedThisTick == 0) {
                sendProgress(player, active.job.progress());
            }
            if (active.job.isFinished()) {
                releaseAllForcedChunks(active);
                jobs.remove(entry.getKey());
                created.remove(entry.getKey());
                sampling.remove(entry.getKey());
            } else {
                // Round-robin the bounded queue instead of starving newer jobs behind a long render.
                jobs.remove(entry.getKey());
                jobs.put(entry.getKey(), active);
            }
        }
        // Only this service's own time is charged; unrelated dimension ticks must not starve this dimension.
        sampleNanosRemaining = Math.max(0L, sampleNanosRemaining - (System.nanoTime() - sliceStarted));
    }

    private RoadMapRoutePreloadPlan resolvePlan(ServerLevel level, RoadPlannerMapPreloadRequestPacket packet) {
        if (automaticMapPurpose(packet.purpose())) {
            return new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, List.of(), 0, 0);
        }
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
        // 这里在服务器主线程:不再同步跑完整道路寻路(超远两城会卡服)。没有节点时预载起终点直线走廊;
        // 自动补全(后台线程)返回真实节点后,客户端会按节点重新请求预载。
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
        JobKey jobKey = new JobKey(active.playerId, active.sessionId, active.purpose);
        RoadPlannerMapRaster raster = sampling.computeIfAbsent(jobKey, ignored -> new RoadPlannerMapRaster(region));
        RoadMapServerColumnSampler delegate = new RoadMapServerColumnSampler(level);
        RoadMapColumnSampler sampler = (worldX, worldZ) -> {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            if (active != null && active.job != null && !active.job.coversChunk(chunkX, chunkZ)) {
                return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
            }
            return delegate.sample(worldX, worldZ);
        };
        columnsRemaining -= raster.advance(sampler, Math.min(256, columnsRemaining), sampleDeadline, System::nanoTime);
        if (!raster.complete()) return null;
        sampling.remove(jobKey);
        RoadMapSnapshot snapshot = raster.snapshot(level.getGameTime());
        releaseForcedTile(level, active, key);
        return snapshot;
    }

    private boolean prepareCoveredChunks(ServerLevel level, RoadPlannerTileKey key, ActiveJob active) {
        if (level == null || key == null || active == null || active.job == null
                || !forcesMissingChunks(active.job.purpose())) {
            return true;
        }
        Set<Long> forcedForTile = active.forcedChunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        int tileStartChunkX = key.tileX() * (RoadMapTileSpec.TILE_BLOCKS / 16);
        int tileStartChunkZ = key.tileZ() * (RoadMapTileSpec.TILE_BLOCKS / 16);
        boolean allLoaded = true;
        UUID owner = active.playerId;
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
                if (forcedForTile.contains(chunkKey) || forceStarts >= MAX_FORCE_CHUNKS_PER_TICK) {
                    continue;
                }
                if (ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, true, false)) {
                    forcedForTile.add(chunkKey);
                    forceStarts++;
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
        UUID owner = active.playerId;
        for (Long chunkKey : forced) {
            if (chunkKey == null) {
                continue;
            }
            ModChunkLoading.forceChunk(
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
            return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean forcesMissingChunks(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        return purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER;
    }

    static boolean forcesMissingChunksForTest(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        return forcesMissingChunks(purpose);
    }

    private static BlockPos forceOwnerPosition(RoadPlannerTileKey key, ServerLevel level) {
        return new BlockPos(
                key.tileX() * RoadMapTileSpec.TILE_BLOCKS,
                level == null ? 0 : level.getMinBuildHeight(),
                key.tileZ() * RoadMapTileSpec.TILE_BLOCKS);
    }

    private void sendTile(ServerPlayer player, RoadPlannerMapTileSyncPacket packet) {
        SharedMapServerState.markRendered(packet);
        ModNetwork.CHANNEL.send(player, packet);
    }

    private void sendProgress(ServerPlayer player, RoadPlannerMapPreloadProgressPacket packet) {
        ModNetwork.CHANNEL.send(player, packet);
    }

    private record JobKey(UUID playerId, UUID sessionId, RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        private JobKey {
            playerId = playerId == null ? new UUID(0L, 0L) : playerId;
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
