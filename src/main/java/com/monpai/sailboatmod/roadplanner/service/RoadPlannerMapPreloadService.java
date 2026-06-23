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
import com.monpai.sailboatmod.roadplanner.map.OfflineChunkSnapshotReader;
import com.monpai.sailboatmod.roadplanner.map.OfflineRoadMapColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
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
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoadPlannerMapPreloadService {
    private static final int MAX_FORCE_CHUNKS_PER_TICK = 8;
    // 离线 NBT 读超时(后台 worker 单 chunk 等磁盘 IO 上限);读不到=从没生成,走 force 兜底。
    private static final long NBT_READ_TIMEOUT_MS = 2000L;
    private static final RoadMapRoutePreloadPlanner ROUTE_PLANNER = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
    private static RoadPlannerMapPreloadService GLOBAL = new RoadPlannerMapPreloadService();

    private final Map<JobKey, ActiveJob> jobs = new LinkedHashMap<>();
    // 离线 NBT 后台读 worker:chunkMap.read().get(timeout) 只能在非主线程跑(主线程跑会卡服,见 route.water 教训)。
    // 4 线程(原 2):后台读盘 IO 密集,多并发加快铺图。
    private final ExecutorService nbtExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "SailboatRoadMapNbt");
        thread.setDaemon(true);
        return thread;
    });
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
        GLOBAL.nbtExecutor.shutdownNow(); // 关后台 NBT worker,避免 onServerStarted 重建 GLOBAL 时旧线程池泄漏。
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
        // 2026-06 改造:优先离线读 NBT 渲染,只对从没生成过的 chunk 走 force 兜底(见 plan / route.water 范例)。
        // prepareTileData 投递后台 NBT 读 + 检查就绪;没全就绪返回 null,本 tick 不出图,下 tick 再来(沿用原等待机制)。
        if (!prepareTileData(level, key, active)) {
            return null;
        }
        BlockPos center = new BlockPos(
                key.tileX() * RoadMapTileSpec.TILE_BLOCKS + RoadMapTileSpec.TILE_BLOCKS / 2,
                0,
                key.tileZ() * RoadMapTileSpec.TILE_BLOCKS + RoadMapTileSpec.TILE_BLOCKS / 2);
        RoadMapRegion region = RoadMapRegion.centeredOn(center, RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1);
        RoadMapSnapshotService service = RoadMapSnapshotService.directExecutorForTest(new RoadMapColorizer());

        TileNbtState state = active == null ? null : active.nbtStates.get(key);
        // 离线 sampler:命中 NBT 缓存的 chunk 走离线列样本(主线程零读盘)。
        OfflineRoadMapColumnSampler offline = new OfflineRoadMapColumnSampler(state == null ? Map.of() : state.cache);
        // 实时 sampler:仅用于 missing(NBT 没有→已 force 加载)的 chunk 兜底。
        RoadMapServerColumnSampler live = new RoadMapServerColumnSampler(level);
        Set<Long> missing = state == null ? Set.of() : state.missing;
        RoadMapColumnSampler sampler = (worldX, worldZ) -> {
            int chunkX = Math.floorDiv(worldX, 16);
            int chunkZ = Math.floorDiv(worldZ, 16);
            if (active != null && active.job != null && !active.job.coversChunk(chunkX, chunkZ)) {
                return RoadMapServerColumnSampler.unavailableSampleForTest(worldX, worldZ);
            }
            // missing 的 chunk(磁盘从没生成)已被 force 加载,用实时采样;其余走离线 NBT 缓存。
            if (missing.contains(ChunkPos.asLong(chunkX, chunkZ))) {
                return live.sample(worldX, worldZ);
            }
            return offline.sample(worldX, worldZ);
        };
        RoadMapSnapshot snapshot = service.buildSnapshotAsync(level.getGameTime(), region, sampler).join();
        releaseForcedTile(level, active, key);
        if (active != null) {
            active.nbtStates.remove(key); // 本瓦片出图完成,丢弃其 NBT 状态/缓存。
        }
        return snapshot;
    }

    /**
     * 两段式瓦片数据准备(取代原 prepareCoveredChunks 的纯 force 逻辑):
     * ① 首次进入:对瓦片覆盖的所有 chunk 投递<b>后台</b> NBT 读(chunkMap.read().get 只能在 worker 跑)。
     * ② 每 tick:已加载的 chunk 直接算就绪(无需 NBT);后台读回来的填进 cache;读不到的(missing=从没生成)走 force 兜底。
     * ③ 当所有覆盖 chunk 都"就绪"(NBT 缓存命中 / 已加载 / missing 已 force 加载完)→ 返回 true 可出图;否则 false 等下个 tick。
     * 主线程零阻塞:只投递/检查,不在主线程 get(timeout)。
     */
    private boolean prepareTileData(ServerLevel level, RoadPlannerTileKey key, ActiveJob active) {
        if (level == null || key == null || active == null || active.job == null
                || !forcesMissingChunks(active.job.purpose())) {
            return true;
        }
        TileNbtState state = active.nbtStates.computeIfAbsent(key, ignored -> new TileNbtState());
        int tileStartChunkX = key.tileX() * (RoadMapTileSpec.TILE_BLOCKS / 16);
        int tileStartChunkZ = key.tileZ() * (RoadMapTileSpec.TILE_BLOCKS / 16);
        int axis = RoadMapTileSpec.TILE_BLOCKS / 16;

        // ① 首次:投递所有覆盖 chunk 的后台 NBT 读(只投一次)。
        if (!state.dispatched) {
            state.dispatched = true;
            for (int dz = 0; dz < axis; dz++) {
                for (int dx = 0; dx < axis; dx++) {
                    int chunkX = tileStartChunkX + dx;
                    int chunkZ = tileStartChunkZ + dz;
                    if (!active.job.coversChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                    if (state.cache.containsKey(chunkKey) || state.pending.contains(chunkKey)) {
                        continue;
                    }
                    state.pending.add(chunkKey);
                    dispatchNbtRead(level, state, chunkX, chunkZ, chunkKey);
                }
            }
        }

        // ② + ③:检查就绪,对 missing 的 chunk force 兜底。
        Set<Long> forcedForTile = active.forcedChunks.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        int startedThisTick = 0;
        boolean allReady = true;
        BlockPos owner = forceOwnerPosition(key, level);
        for (int dz = 0; dz < axis; dz++) {
            for (int dx = 0; dx < axis; dx++) {
                int chunkX = tileStartChunkX + dx;
                int chunkZ = tileStartChunkZ + dz;
                if (!active.job.coversChunk(chunkX, chunkZ)) {
                    continue;
                }
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                // NBT 缓存命中 → 就绪。
                if (state.cache.containsKey(chunkKey)) {
                    continue;
                }
                // 后台读尚未返回 → 等。
                if (state.pending.contains(chunkKey)) {
                    allReady = false;
                    continue;
                }
                // 后台读已返回但不在 cache → 是 missing(磁盘从没生成),走 force 兜底。
                if (state.missing.contains(chunkKey)) {
                    if (isChunkLoaded(level, chunkX, chunkZ)) {
                        continue; // 已 force 加载好 → 就绪(出图时走实时 sampler)。
                    }
                    allReady = false;
                    if (forcedForTile.contains(chunkKey) || startedThisTick >= MAX_FORCE_CHUNKS_PER_TICK) {
                        continue;
                    }
                    if (ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, true, false)) {
                        forcedForTile.add(chunkKey);
                        startedThisTick++;
                    }
                    continue;
                }
                // 既不在 cache/pending/missing(理论上不该发生)→ 视为未就绪等待。
                allReady = false;
            }
        }
        return allReady;
    }

    /** 把单个 chunk 的离线 NBT 读投到后台 worker。读到→填 cache;读不到(empty/异常)→记 missing。最后移出 pending。 */
    private void dispatchNbtRead(ServerLevel level, TileNbtState state, int chunkX, int chunkZ, long chunkKey) {
        nbtExecutor.execute(() -> {
            try {
                Optional<RoadMapColumnSample[]> samples =
                        OfflineChunkSnapshotReader.readOffThread(level, chunkX, chunkZ, NBT_READ_TIMEOUT_MS);
                if (samples.isPresent()) {
                    state.cache.put(chunkKey, samples.get());
                } else {
                    state.missing.add(chunkKey);
                }
            } catch (Throwable t) {
                state.missing.add(chunkKey); // 异常也当作 missing,走 force 兜底,绝不让 worker 异常逃逸。
            } finally {
                state.pending.remove(chunkKey);
            }
        });
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

    private static boolean forcesMissingChunks(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        return purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER
                || purpose == RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD;
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
                             Map<RoadPlannerTileKey, Set<Long>> forcedChunks,
                             Map<RoadPlannerTileKey, TileNbtState> nbtStates) {
        private ActiveJob(UUID playerId,
                          UUID sessionId,
                          long requestId,
                          RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                          String worldId,
                          String dimensionId,
                          RoadPlannerMapPreloadJob job) {
            this(playerId, sessionId, requestId, purpose, worldId, dimensionId, job, new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        private ActiveJob {
            playerId = playerId == null ? new UUID(0L, 0L) : playerId;
            sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
            worldId = worldId == null ? "" : worldId;
            dimensionId = dimensionId == null ? "" : dimensionId;
            forcedChunks = forcedChunks == null ? new LinkedHashMap<>() : forcedChunks;
            nbtStates = nbtStates == null ? new LinkedHashMap<>() : nbtStates;
        }
    }

    /**
     * 单个瓦片的离线 NBT 后台读状态。主线程只读这里的标志/缓存,后台 worker 只写 cache/pending(并发安全结构)。
     * 生命周期:瓦片首次进入 buildSnapshot 时建,出图或释放时随 nbtStates.remove 丢弃。
     */
    private static final class TileNbtState {
        // chunkKey(ChunkPos.asLong) → 该区块离线解码出的 256 列样本。后台 worker 读到后填入。
        private final Map<Long, RoadMapColumnSample[]> cache = new ConcurrentHashMap<>();
        // 已投递后台读、尚未返回的 chunk(防重复投递)。
        private final Set<Long> pending = ConcurrentHashMap.newKeySet();
        // 后台读返回 empty(磁盘没有=从没生成)的 chunk → 需走 force 兜底。
        private final Set<Long> missing = ConcurrentHashMap.newKeySet();
        // 是否已对本瓦片投递过后台读(只投一次,后续 tick 只等结果)。
        private volatile boolean dispatched;
    }
}
