package com.monpai.sailboatmod.market.web.map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.ModConfig;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Squaremap-style render lifecycle manager for the market web map.
 */
public final class MarketWebMapRenderManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String PROGRESS_FILE = "render_progress.json";
    private static final String DIRTY_CHUNKS_FILE = "dirty_chunks.json";
    private static final String DIRTY_REGIONS_FILE = "dirty_regions.json";
    private static final int CHUNKS_PER_REGION_AXIS = MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS;
    private static final int CHUNKS_PER_REGION = CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS;

    // ── 未加载区块的主线程 probe + force-load 采样节流(squaremap 模式,见 marketweb_snapshot_deadlock 修复) ──
    // 崩服根因:后台 renderExecutor 调 chunkMap.read().thenApplyAsync(decode) 并发遍历共享 CompoundTag 的 HashMap,
    // 被 vanilla 在 ForkJoinPool 并发 datafix 改写 → 桶损坏崩。唯一安全=主线程对【已生成】区块 force 加载拿区块再采样。
    // force-load 已生成虽比生成轻,full render 上千区块仍要限量;wall-clock 双闸保底不卡 tick。
    // 离线异步读模式吞吐量:每个 chunk 跨多 tick(发起异步读→等读盘→解码),不像旧版 force 1 tick 完成。
    // 所以这些配额按"同时挂多个在途异步读"放大,否则管线被旧版的小配额(4/8/32)卡死、渲染显著变慢。
    // 解码纯内存快,GENERATED_SAMPLES_PER_TICK 实为"每 tick 解码完成数";wall-clock 闸兜底不卡 tick。
    // spark 实测:fullrender 卡服热点是 vanilla ServerChunkCache$MainThreadExecutor + 海量 chunk
    // CompletableFuture 完成回调淹主线程(beginProbe 的读盘完成全涌回主线程)。下调这些节流量减轻主线程压力:
    // 每 tick 解码量、发起量、在途上限都减半左右,牺牲 fullrender 速度换 tick 不超时。可继续按服务器负载调。
    private static final int GENERATED_SAMPLES_PER_TICK = 24;
    private static final long MAX_GENERATED_SAMPLE_MILLIS_PER_TICK = 15L;
    private static final int PROBE_STARTS_PER_TICK = 16;
    private static final int MAX_PROBE_WAIT_TICKS = 60;
    // 读盘超时不再立刻判"未生成":磁盘上 chunk 实际存在、只是 IO 抖动读得慢(VMware/机械盘)。
    // 旧逻辑超时即 UNGENERATED → acceptMissingSnapshot 永久记 missing → 该 16×16 区写黑且永不重读 → 黑块永久。
    // 改为超时后重新 beginProbe 重试,最多 MAX_PROBE_RETRIES 次,只有真 empty/非 FULL 才算未生成。
    private static final int MAX_PROBE_RETRIES = 3;
    // 在途异步读硬上限(背压):每个 pending probe 的 future 完成后挂一整块区块 NBT(几十 KB~MB)。
    // 无此上限 + 高发起速度 + 读盘慢(VMware IO 抖)会堆积成百上千块 → 内存暴涨 → Full GC → watchdog 卡死。
    // 256→96:在途 probe 越多,它们同时完成时涌回主线程的 CompletableFuture 回调越多(spark 实测主线程热点)。
    // 96 仍能填满 IOWorker 流水线(单线程读盘,96 个排队足够),但完成回调峰值更低 → 主线程不被淹 → tick 不超时。
    private static final int MAX_INFLIGHT_PROBES = 96;
    private static final int MAX_SKIPPED_UNGENERATED = 4096;

    private final MarketWebMapRegionRenderer regionRenderer = new MarketWebMapRegionRenderer();
    private final MarketWebSquareMapImageIOExecutor imageIO = new MarketWebSquareMapImageIOExecutor();
    private final MarketWebMapDirtyChunkQueue dirtyChunks = new MarketWebMapDirtyChunkQueue();
    private final MarketWebMapDirtyRegionQueue dirtyRegions = new MarketWebMapDirtyRegionQueue();
    private final Map<Long, RegionState> regionStates = new LinkedHashMap<>();
    private final Map<Long, List<Integer>> expectedRegionChunks = new LinkedHashMap<>();
    private final LinkedHashMap<Long, int[][]> bottomRowCache = new LinkedHashMap<>();
    // 逐 chunk 底行种子缓存:key=chunk packed 坐标,value=该 chunk 最南行 16 个 surfaceY。
    // partial-flush 渲染时若某 chunk 的北邻这批不在 RegionState 内,但北邻在更早批次已渲过(fullrender 北→南顺序),
    // 就从这里拿北邻底行做种子 → 准确(同次渲染刚算,非磁盘旧值)→ 不偏平、不画错误线。内存小(每 chunk 16 int)。
    private final LinkedHashMap<Long, int[]> chunkBottomRowCache = new LinkedHashMap<>();
    private static final int MAX_CHUNK_BOTTOM_ROW_CACHE = 200_000;
    private final AtomicInteger renderingRegions = new AtomicInteger();
    private final AtomicInteger failedChunks = new AtomicInteger();
    // === 诊断累计计数器(只增,定位 fullrender 卡点用)。imageIO/渲染线程可更新,故用 AtomicLong。 ===
    private final java.util.concurrent.atomic.AtomicLong diagProbesStarted = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagProbesCompleted = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagProbesTimedOut = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagSnapshotsDecoded = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagSnapshotsMissing = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagRegionsScheduled = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagTilesWritten = new java.util.concurrent.atomic.AtomicLong();
    // submitSnapshot 各结果累计
    private final java.util.concurrent.atomic.AtomicLong diagResultConsumed = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagResultDefer = new java.util.concurrent.atomic.AtomicLong();
    private final ExecutorService renderExecutor;
    // 已生成探测:chunkKey(asLong) → 异步 chunkMap.read future + 发起 tick(超时降级未生成)。仅主线程读写。
    private final Map<Long, ChunkProbe> pendingProbe = new HashMap<>();
    // 已判定未生成(empty/超时)的 chunk LRU,避免反复 probe 同一个空区块。仅主线程读写。
    private final LinkedHashSet<Long> skippedUngenerated = new LinkedHashSet<>();
    // drainCompletedProbes 锁内解码后的临时暂存(chunkKey → snapshot),锁外 accept 时取出。仅主线程读写。
    private final Map<Long, MarketWebMapChunkSnapshot> pendingDecoded = new HashMap<>();
    // 本 tick force-load 采样配额计数(beginSnapshotTick 重置)。仅主线程读写。
    private int generatedSamplesThisTick;
    private long generatedSampleMillisThisTick;
    private int probeStartsThisTick;
    private long snapshotTick;
    private RenderJob activeJob;
    private boolean paused;
    private int tickCounter;
    // ── fullrender 专用 region 读取器(完全绕开全局 queue/defer)──
    // 为什么:fullrender 之前把当前 region 的 1024 chunk 塞进全局共享 queue,被黑块修复/玩家区域/历史残留 task 混杂、
    // 被 submitSnapshot 每 tick 只 poll 96 个 + 无限 DEFER 回队尾搅乱 → 当前 region 的 chunk 沉在队底轮不到 →
    // region 永远 complete 不了 → 几乎不写图(实测 tilesWritten=5/regions=47)。本读取器自己掌控当前 region 的
    // chunk:直接 beginProbe(专用 probe map,不和全局混)→ 解码 → 填 RegionState → complete 即渲染+写盘 → 下一个 region。
    // 同一时刻只读一个 region → inflight 96 足够覆盖其 1024 chunk → 必 complete → 一次性满图落盘:
    //   ① 无黑块(complete 才渲染,不写残缺)② 无横线(串行保证北邻已落盘,种子齐全)③ 不死锁(不进 queue/defer)。
    // 全部字段仅 tickBackground 主线程 + imageIO 写盘线程读写,统一 synchronized(this)。
    private int readerRegionIndex = -1;                       // 读取器当前处理的 region 序号(-1=未开始/已 done)
    private RegionState readerState;                          // 当前 region 的累积 state(复用 RegionState)
    private final Map<Long, ChunkProbe> readerProbes = new HashMap<>(); // 当前 region 的在途 probe(独立于全局 pendingProbe)
    private int readerNextLocalChunk;                         // 当前 region localChunks 列表里下一个待发起 probe 的下标
    private boolean readerScheduled;                          // 当前 region 是否已 complete 并 schedule 渲染(等写盘)
    private long readerAwaitWriteKey = -1L;                   // 已 schedule、正在等写盘落地的 region packed key
    private boolean readerRegionWritten;                      // 该 region 是否已真正写盘落地(imageIO 回调置位)
    private int readerRegionWaitTicks;                        // 当前 region 已等待的后台 interval tick 数(整 region 级超时)
    // 整 region 级超时:极端情况下个别 chunk 的 probe 永远 done 不了(sweepStale 重试也救不回),等够这么多个后台
    // interval tick(默认 interval=40,30 次≈1200 tick≈60s)就把未到的 chunk 当 missing 强制 complete,不无限卡死。
    private static final int READER_REGION_TIMEOUT_TICKS = 30;

    public MarketWebMapRenderManager() {
        int threads = configuredRenderThreads();
        this.renderExecutor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "SailboatMarketWebMap-RegionRender");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void tickBackground(ServerLevel level,
                               MarketWebMapRenderQueue queue,
                               long nowMillis) {
        if (level == null || queue == null) {
            return;
        }
        RenderJob job;
        boolean intervalPass;
        synchronized (this) {
            job = activeJob;
            if (paused) {
                return;
            }
            tickCounter++;
            int interval = Math.max(1, configuredInt(ModConfig::marketWebBackgroundIntervalTicks, 200));
            intervalPass = (tickCounter % interval == 0);
            // fullrender 专用读取器【每 tick】都跑(其 probe 发起 / 解码已有 per-tick 双闸节流,不需要外层 interval 再节流;
            // 若也按 interval=40 节流,读取器每 2s 才动一次,极慢)。非 fullrender 路径仍按 interval 节流(沿用旧行为)。
            boolean fullRenderActive = job != null && job.fullRender();
            if (!fullRenderActive && !intervalPass) {
                return;
            }
        }
        int budget = Math.max(1, configuredInt(ModConfig::marketWebBackgroundMaxChunksPerInterval, 512));
        List<RegionState> residualReady = null;
        MarketWebMapTileCache cache = null;
        RegionState readerReady = null; // fullrender 专用读取器本 tick complete 的 region(锁外 schedule)
        synchronized (this) {
            if (job != null && job.fullRender()) {
                // ── fullrender 专用 region 读取器:完全绕开 queue/defer,自己读当前 region 的 chunk ──
                // 自管 per-tick 节流计数(probe 发起 / 解码额度),不依赖 processSnapshotBudget.beginSnapshotTick 的时序。
                snapshotTick++;
                generatedSamplesThisTick = 0;
                generatedSampleMillisThisTick = 0L;
                probeStartsThisTick = 0;
                cache = MarketWebMapTileCache.forServer(level.getServer());
                readerReady = driveRegionReader(level, cache, job, nowMillis);
                if (job.done()) {
                    clearProgress(level);
                    activeJob = null;
                    resetRegionReader();
                    if (!expectedRegionChunks.isEmpty()) {
                        expectedRegionChunks.clear();
                    }
                    paused = false;
                }
            } else if (job != null) {
                // 非 fullrender(radius/area/border):沿用旧 queue 流水线。
                int attempted = 0;
                while (attempted < budget && activeJob == job && !job.done()) {
                    MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk = job.chunk();
                    queue.enqueue(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ(), nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                    job.advance();
                    job.processedChunks = job.completedChunkCount();
                    attempted++;
                }
                if (attempted > 0) {
                    job.processedRegions = job.completedRegionCount();
                    int saveInterval = Math.max(1, configuredInt(ModConfig::marketWebProgressSaveIntervalChunks, 512));
                    if (job.done() || job.processedChunks - job.lastSavedChunkIndex >= saveInterval) {
                        job.lastSavedChunkIndex = job.processedChunks;
                        saveProgress(level, job);
                    }
                }
                if (job.done()) {
                    clearProgress(level);
                    activeJob = null;
                    paused = false;
                }
            } else {
                expandDirtyRegions(level);
                List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> dirty = dirtyChunks.poll(budget);
                for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : dirty) {
                    queue.enqueue(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ(), nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                }
                if (!dirty.isEmpty()) {
                    saveDirtyChunks(level);
                }
            }

            // 排空兜底 flush(仅非 fullrender 收尾):无活跃 job、读盘/渲染已清空时,把仍攒着 chunk 但没到 complete、
            // 从未排程的残留 RegionState schedule 出去(覆盖增量更新尾部"NBT 读了但没 image"的稀疏 region)。
            // fullrender 由专用读取器自管,不走这里。
            if (activeJob == null
                    && pendingProbe.isEmpty()
                    && dirtyChunks.size() == 0
                    && renderingRegions.get() == 0
                    && !regionStates.isEmpty()) {
                residualReady = new ArrayList<>();
                Iterator<Map.Entry<Long, RegionState>> iterator = regionStates.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Long, RegionState> entry = iterator.next();
                    RegionState state = entry.getValue();
                    if (state != null && state.count() > 0 && !state.renderScheduled) {
                        state.renderScheduled = true;
                        residualReady.add(state);
                    }
                    iterator.remove();
                }
                if (!expectedRegionChunks.isEmpty()) {
                    expectedRegionChunks.clear();
                }
                if (!residualReady.isEmpty()) {
                    cache = MarketWebMapTileCache.forServer(level.getServer());
                }
            }
        }
        // 锁外 schedule(scheduleRegionRender 内部会取锁,避免锁内嵌套)。
        if (readerReady != null && cache != null) {
            scheduleRegionRender(cache, readerReady, nowMillis);
        }
        if (residualReady != null && cache != null) {
            for (RegionState ready : residualReady) {
                scheduleRegionRender(cache, ready, nowMillis);
            }
        }
    }

    /**
     * fullrender 专用 region 读取器:每 tick 推进当前 region 的 chunk 读取(完全绕开全局 queue/defer)。
     * 流程:发起 probe(专用 map)→ 解码 done 的 → 填 readerState → complete 即返回该 state 供锁外 schedule →
     * 等其写盘落地 → advance 到下一 region。返回本 tick 需要 schedule 的 RegionState(无则 null)。
     * <b>必须在 synchronized(this) 内调用</b>。
     */
    private RegionState driveRegionReader(ServerLevel level, MarketWebMapTileCache cache, RenderJob job, long nowMillis) {
        // 1) 等写盘:已 schedule 的 region 还没写盘落地 → 本 tick 只等,不动游标。
        if (readerScheduled) {
            if (readerRegionWritten) {
                // 该 region 已落盘:推进 job 游标到下一 region,复位读取器,下 tick 开新 region。
                advanceJobPastReaderRegion(job);
                resetRegionReaderForNextRegion();
            }
            return null;
        }
        // 2) 绑定当前 region:首次或换 region 时,新建 readerState + 准备 localChunks 列表。
        int cursorRegion = job.currentRegionIndex();
        if (job.done()) {
            return null;
        }
        if (readerRegionIndex != cursorRegion || readerState == null) {
            readerRegionIndex = cursorRegion;
            readerState = newRegionState(MarketWebMapConstants.OVERWORLD, job.currentRegionX(), job.currentRegionZ());
            readerProbes.clear();
            readerNextLocalChunk = 0;
            readerRegionWaitTicks = 0;
        }
        List<Integer> localChunks = job.currentRegionLocalChunks();
        int regionX = job.currentRegionX();
        int regionZ = job.currentRegionZ();
        int baseChunkX = regionX * CHUNKS_PER_REGION_AXIS;
        int baseChunkZ = regionZ * CHUNKS_PER_REGION_AXIS;

        // 3) 发起 probe:本 tick 在 inflight 上限 + 发起额度内,把还没发起的 chunk 发出去。
        while (readerNextLocalChunk < localChunks.size()
                && readerProbes.size() < MAX_INFLIGHT_PROBES
                && probeStartsThisTick < PROBE_STARTS_PER_TICK) {
            int local = localChunks.get(readerNextLocalChunk++);
            int chunkX = baseChunkX + Math.floorMod(local, CHUNKS_PER_REGION_AXIS);
            int chunkZ = baseChunkZ + Math.floorDiv(local, CHUNKS_PER_REGION_AXIS);
            long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            try {
                CompletableFuture<Optional<CompoundTag>> future =
                        OfflineChunkNbtReader.beginProbe(level, new ChunkPos(chunkX, chunkZ));
                readerProbes.put(chunkKey, new ChunkProbe(future, snapshotTick));
                probeStartsThisTick++;
                diagProbesStarted.incrementAndGet();
            } catch (RuntimeException ignored) {
                // 发起失败:当 missing,不卡死。
                readerState.missing(chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                diagSnapshotsMissing.incrementAndGet();
            }
        }

        // 4) drain done 的 probe:受双闸(解码数 / wall-clock)节流,解码成 snapshot 或判 missing。
        Iterator<Map.Entry<Long, ChunkProbe>> iterator = readerProbes.entrySet().iterator();
        while (iterator.hasNext()) {
            if (generatedSamplesThisTick >= GENERATED_SAMPLES_PER_TICK
                    || generatedSampleMillisThisTick >= MAX_GENERATED_SAMPLE_MILLIS_PER_TICK) {
                break; // 本 tick 解码额度用尽,剩下的下 tick 继续
            }
            Map.Entry<Long, ChunkProbe> entry = iterator.next();
            ChunkProbe probe = entry.getValue();
            if (probe == null || !probe.future().isDone()) {
                continue;
            }
            long chunkKey = entry.getKey();
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            iterator.remove();
            Optional<CompoundTag> tag;
            try {
                tag = probe.future().getNow(Optional.empty());
            } catch (RuntimeException ex) {
                tag = Optional.empty();
            }
            diagProbesCompleted.incrementAndGet();
            if (tag.isEmpty() || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
                readerState.missing(chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                diagSnapshotsMissing.incrementAndGet();
                continue;
            }
            long startNanos = System.nanoTime();
            Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.capture(
                    MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, tag.get(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight());
            generatedSamplesThisTick++;
            generatedSampleMillisThisTick += (System.nanoTime() - startNanos) / 1_000_000L;
            if (snapshot.isPresent()) {
                diagSnapshotsDecoded.incrementAndGet();
                readerState.put(snapshot.get(), MarketWebMapTileQuality.SERVER_REGION_SCAN);
                cacheBottomRow(snapshot.get());
            } else {
                readerState.missing(chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                diagSnapshotsMissing.incrementAndGet();
            }
        }

        // 5) 整 region 级超时:个别 chunk 的 probe 永远 done 不了 → 等够 READER_REGION_TIMEOUT_TICKS 就把【尚未发起 +
        //    在途未决】的 chunk 全当 missing,强制 complete,不无限卡死。
        boolean allEnqueued = readerNextLocalChunk >= localChunks.size();
        if (allEnqueued && !readerState.complete()) {
            readerRegionWaitTicks++;
            if (readerRegionWaitTicks >= READER_REGION_TIMEOUT_TICKS) {
                for (Integer local : localChunks) {
                    int chunkX = baseChunkX + Math.floorMod(local, CHUNKS_PER_REGION_AXIS);
                    int chunkZ = baseChunkZ + Math.floorDiv(local, CHUNKS_PER_REGION_AXIS);
                    if (!readerState.accounted(
                            chunkX - regionX * CHUNKS_PER_REGION_AXIS,
                            chunkZ - regionZ * CHUNKS_PER_REGION_AXIS)) {
                        readerState.missing(chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                        diagSnapshotsMissing.incrementAndGet();
                    }
                }
                readerProbes.clear();
            }
        }

        // 6) complete → schedule(锁外)。标记 readerScheduled + 待写盘 key,等写盘回调置位 readerRegionWritten。
        if (readerState.complete() && !readerState.renderScheduled) {
            readerState.renderScheduled = true;
            readerScheduled = true;
            readerAwaitWriteKey = packRegion(regionX, regionZ);
            readerRegionWritten = false;
            job.processedChunks = job.completedChunkCount();
            job.processedRegions = job.completedRegionCount();
            return readerState; // 锁外 scheduleRegionRender
        }
        return null;
    }

    /** 读取器:当前 region 写盘落地后,推进 job 游标越过这一整个 region 到下一 region 起始。 */
    private void advanceJobPastReaderRegion(RenderJob job) {
        if (job == null || !job.fullRender()) {
            return;
        }
        int target = readerRegionIndex + 1;
        int guard = 0;
        while (!job.done() && job.currentRegionIndex() < target && guard++ < CHUNKS_PER_REGION + 8) {
            job.advance();
        }
        job.processedChunks = job.completedChunkCount();
        job.processedRegions = job.completedRegionCount();
        int saveInterval = Math.max(1, configuredInt(ModConfig::marketWebProgressSaveIntervalChunks, 512));
        if (job.processedChunks - job.lastSavedChunkIndex >= saveInterval) {
            job.lastSavedChunkIndex = job.processedChunks;
            // saveProgress 不在锁内做磁盘 IO 也可,但此处量小、低频(每 region 一次),可接受。
        }
    }

    /** 读取器:当前 region 落盘后复位,准备下一 region(保留 readerRegionIndex 由 driveRegionReader 重新绑定)。 */
    private void resetRegionReaderForNextRegion() {
        readerState = null;
        readerProbes.clear();
        readerNextLocalChunk = 0;
        readerScheduled = false;
        readerAwaitWriteKey = -1L;
        readerRegionWritten = false;
        readerRegionWaitTicks = 0;
        readerRegionIndex = -1; // 强制下 tick 按游标重新绑定
    }

    /** 完全复位读取器(start/cancel/clearAll)。 */
    private void resetRegionReader() {
        readerState = null;
        readerProbes.clear();
        readerNextLocalChunk = 0;
        readerScheduled = false;
        readerAwaitWriteKey = -1L;
        readerRegionWritten = false;
        readerRegionWaitTicks = 0;
        readerRegionIndex = -1;
    }

    /**
     * 提交结果:{@code CONSUMED} 已处理完(采样/标记缺失/probe 已生成判定);{@code DEFER} 本 tick 处理不了
     * (probe 未决 / force-load 配额用尽),调用方须把该 chunk 重新入队下 tick 重试。
     */
    public enum SubmitResult {
        CONSUMED,
        DEFER
    }

    /**
     * 每 tick 开始(processSnapshotBudget 入口)调用一次:重置本 tick 的 probe 发起 / force-load 采样配额。
     */
    public synchronized void beginSnapshotTick() {
        snapshotTick++;
        generatedSamplesThisTick = 0;
        generatedSampleMillisThisTick = 0L;
        probeStartsThisTick = 0;
        sweepStalePendingProbes();
    }

    /**
     * 每 tick 主动清理超时未决 probe。原本超时判定(resolveSnapshotRead 行内)只在该 chunk 的 task
     * <b>再次被 poll</b> 时触发;但 task 队列很长(数千)时,卡死/读盘极慢的 future 长期轮不到复查,
     * 一直占着 {@link #MAX_INFLIGHT_PROBES} 在途槽位 → 新 chunk 一直发起不了(背压满)→ region 攒不齐 →
     * 不写图(底图全黑)。这里每 tick 扫一遍,超时的直接移除腾槽,下次该 chunk 被 poll 时重新 beginProbe。
     * <p>只丢弃 pendingProbe 引用(不调 future.cancel:beginProbe 走 IOWorker,丢引用后 future 自行
     * 跑完并 GC 其 NBT,不泄漏);只<b>减少</b>在途 future,内存上界更低,不放大发起速度、不动背压上限。</p>
     */
    private void sweepStalePendingProbes() {
        if (pendingProbe.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, ChunkProbe>> iterator = pendingProbe.entrySet().iterator();
        while (iterator.hasNext()) {
            ChunkProbe probe = iterator.next().getValue();
            if (probe != null
                    && !probe.future().isDone()
                    && snapshotTick - probe.startedTick() >= MAX_PROBE_WAIT_TICKS) {
                iterator.remove();
            }
        }
    }

    /**
     * 每 tick 主动排干【已完成】的在途 probe(解码或判未生成),不依赖该 chunk 的 task 再次被 poll。
     * <p><b>死锁根因</b>:旧逻辑里 future done 的 probe 只在其 task 被 {@link #submitSnapshot} 重新处理时才解码;
     * 但 fullrender 队列有数十万 task、每 tick 只 poll ~96 个,DEFER 重入队到队尾后要数千 tick 才轮到 →
     * 已 done 的 256 个 probe 长期占着 {@link #MAX_INFLIGHT_PROBES} 槽位不释放 → inflight 永满 → 新 probe
     * 发不出 → 整个管线冻结(实测:probes/decoded/tilesWritten 全停,只有 defer 疯涨)。
     * <p>这里每 tick 把所有 done 的 probe 直接解码完、accept 出去、腾空槽位,受同一 GENERATED_SAMPLES/wall-clock
     * 双闸节流(不卡主线程)。槽位及时释放后 inflight 不再永满,新 chunk 持续流入,管线恢复流动。</p>
     */
    public void drainCompletedProbes(ServerLevel level, MarketWebMapTileCache cache, long nowMillis) {
        if (level == null || cache == null) {
            return;
        }
        List<long[]> readyToDecode = new ArrayList<>();
        List<long[]> ungenerated = new ArrayList<>();
        synchronized (this) {
            if (pendingProbe.isEmpty()) {
                return;
            }
            Iterator<Map.Entry<Long, ChunkProbe>> iterator = pendingProbe.entrySet().iterator();
            while (iterator.hasNext()) {
                // 解码闸:本 tick 解码额度用尽就停,剩下的 done probe 留到下 tick(不删,槽位下 tick 再腾)。
                if (generatedSamplesThisTick >= GENERATED_SAMPLES_PER_TICK
                        || generatedSampleMillisThisTick >= MAX_GENERATED_SAMPLE_MILLIS_PER_TICK) {
                    break;
                }
                Map.Entry<Long, ChunkProbe> entry = iterator.next();
                ChunkProbe probe = entry.getValue();
                if (probe == null || !probe.future().isDone()) {
                    continue;
                }
                long chunkKey = entry.getKey();
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                Optional<CompoundTag> tag;
                try {
                    tag = probe.future().getNow(Optional.empty());
                } catch (RuntimeException exception) {
                    tag = Optional.empty();
                }
                diagProbesCompleted.incrementAndGet();
                iterator.remove(); // 无论结果都腾槽位
                if (tag.isEmpty() || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
                    skippedUngenerated.remove(chunkKey);
                    skippedUngenerated.add(chunkKey);
                    ungenerated.add(new long[]{chunkX, chunkZ});
                    continue;
                }
                long startNanos = System.nanoTime();
                Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.capture(
                        MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, tag.get(),
                        level.getMinBuildHeight(), level.getMaxBuildHeight());
                generatedSamplesThisTick++;
                generatedSampleMillisThisTick += (System.nanoTime() - startNanos) / 1_000_000L;
                if (snapshot.isPresent()) {
                    diagSnapshotsDecoded.incrementAndGet();
                    readyToDecode.add(new long[]{chunkX, chunkZ});
                    // 暂存 snapshot 以便锁外 accept(避免在锁内调 acceptSnapshot 再取锁)。
                    pendingDecoded.put(chunkKey, snapshot.get());
                } else {
                    ungenerated.add(new long[]{chunkX, chunkZ});
                }
            }
        }
        // 锁外 accept:解码成功的入 region,未生成的记 missing。
        for (long[] xz : readyToDecode) {
            long key = ChunkPos.asLong((int) xz[0], (int) xz[1]);
            MarketWebMapChunkSnapshot snapshot = pendingDecoded.remove(key);
            if (snapshot != null) {
                acceptSnapshot(cache, snapshot, MarketWebMapTileQuality.SERVER_REGION_SCAN, nowMillis);
            }
        }
        for (long[] xz : ungenerated) {
            acceptMissingSnapshot(cache, MarketWebMapConstants.OVERWORLD, (int) xz[0], (int) xz[1],
                    MarketWebMapTileQuality.SERVER_REGION_SCAN, nowMillis);
        }
    }


    /**
     * 主线程提交一个 chunk 的快照请求(squaremap 模式,全程主线程,绝不把共享 CompoundTag 交后台)。
     * <ul>
     *   <li>已加载 → 直接 {@link MarketWebMapChunkSnapshot#capture} 采样 → acceptSnapshot(CONSUMED)。</li>
     *   <li>未加载 → 主线程 probe 判是否已生成存盘(只读 Status 单字段就丢,不解码整块):
     *     <ul>
     *       <li>已生成 → force-load 采样(配额内)→ acceptSnapshot;配额满 → DEFER 下 tick 重试。</li>
     *       <li>未生成(empty/超时)→ acceptMissingSnapshot 留空 + 记入 skippedUngenerated 避免反复 probe(CONSUMED)。</li>
     *       <li>probe 未决 → DEFER 下 tick 再看。</li>
     *     </ul>
     *   </li>
     * </ul>
     * <b>绝不 force 未生成区块</b>(地图 full render 上千区块,force 生成会炸服)。
     */
    public SubmitResult submitSnapshot(ServerLevel level,
                                       MarketWebMapTileCache cache,
                                       MarketWebMapRenderQueue.Task task,
                                       long nowMillis) {
        if (level == null || cache == null || task == null || !MarketWebMapConstants.OVERWORLD.equals(task.dimensionId())) {
            return SubmitResult.CONSUMED;
        }
        // 已加载旁路(保留不动):主线程直接 capture 并消费。
        Optional<MarketWebMapChunkSnapshot> loaded = MarketWebMapChunkSnapshot.capture(level, task.chunkX(), task.chunkZ());
        if (loaded.isPresent()) {
            MarketWebMapChunkSnapshot.capture(level, task.chunkX(), task.chunkZ() - 1).ifPresent(this::cacheBottomRow);
            acceptSnapshot(cache, loaded.get(), task.quality(), nowMillis);
            return SubmitResult.CONSUMED;
        }

        long chunkKey = ChunkPos.asLong(task.chunkX(), task.chunkZ());
        if (isSkippedUngenerated(chunkKey)) {
            acceptMissingSnapshot(cache, task.dimensionId(), task.chunkX(), task.chunkZ(), task.quality(), nowMillis);
            return SubmitResult.CONSUMED;
        }

        // 未加载:发起【离线异步读盘】(OfflineChunkNbtReader.beginProbe → IOWorker 异步读,绝不 force),
        // 后续 tick 看 future done:非 full → 未生成留空;full → 主线程纯内存解码成快照(不碰世界,不 force)。
        // 这是 squaremap/Xaero 范式,根除 forceChunk 同步 worldgen 卡服(spark 实锤 forceChunk 占满主线程)。
        SnapshotReadResult read = resolveSnapshotRead(level, task.chunkX(), task.chunkZ(), chunkKey, task.dimensionId());
        switch (read.state()) {
            case PENDING -> {
                return SubmitResult.DEFER; // 读盘未决:下 tick 再看
            }
            case UNGENERATED -> {
                markSkippedUngenerated(chunkKey);
                acceptMissingSnapshot(cache, task.dimensionId(), task.chunkX(), task.chunkZ(), task.quality(), nowMillis);
                return SubmitResult.CONSUMED;
            }
            case DECODE_THROTTLED -> {
                return SubmitResult.DEFER; // 本 tick 解码配额用尽:tag 已就绪,下 tick 解码
            }
            case READY -> {
                // 北邻底行(供 height shading 接缝):异步拿已解码北邻喂 bottomRow,拿不到就退化 unknownLastY。绝不 force。
                captureGeneratedNorthNeighbor(level, task.chunkX(), task.chunkZ() - 1).ifPresent(this::cacheBottomRow);
                if (read.snapshot().isPresent()) {
                    acceptSnapshot(cache, read.snapshot().get(), task.quality(), nowMillis);
                } else {
                    // tag 是 full 但解码失败(罕见):标记缺失,不无限重试。
                    failedChunks.incrementAndGet();
                    acceptMissingSnapshot(cache, task.dimensionId(), task.chunkX(), task.chunkZ(), task.quality(), nowMillis);
                }
                return SubmitResult.CONSUMED;
            }
            default -> {
                return SubmitResult.CONSUMED;
            }
        }
    }

    /**
     * 北邻底行采样:北邻已加载时直接 {@link MarketWebMapChunkSnapshot#capture} 采;未加载则查异步读盘结果,
     * 若已解码成快照就返回喂 bottomRow,否则返回 empty(接缝退化为 unknownLastY,可接受)。<b>绝不 force</b>。
     * 北邻解码不计入主配额(轻量、最多一次),DECODE_THROTTLED/PENDING 当作拿不到处理。
     */
    private Optional<MarketWebMapChunkSnapshot> captureGeneratedNorthNeighbor(ServerLevel level, int chunkX, int chunkZ) {
        Optional<MarketWebMapChunkSnapshot> loadedNorth = MarketWebMapChunkSnapshot.capture(level, chunkX, chunkZ);
        if (loadedNorth.isPresent()) {
            return loadedNorth;
        }
        long northKey = ChunkPos.asLong(chunkX, chunkZ);
        if (isSkippedUngenerated(northKey)) {
            return Optional.empty();
        }
        SnapshotReadResult read = resolveSnapshotRead(level, chunkX, chunkZ, northKey, MarketWebMapConstants.OVERWORLD);
        if (read.state() == SnapshotReadState.UNGENERATED) {
            markSkippedUngenerated(northKey);
        }
        return read.snapshot();
    }

    private enum SnapshotReadState {
        PENDING,          // 读盘未决,下 tick 再看
        UNGENERATED,      // 磁盘上没有 / 非 full → 当未生成留空
        DECODE_THROTTLED, // tag 已就绪,但本 tick 解码配额用尽,下 tick 解码
        READY             // 已就绪(snapshot present=解码成功;empty=full 但解码失败)
    }

    private record SnapshotReadResult(SnapshotReadState state, Optional<MarketWebMapChunkSnapshot> snapshot) {
        static SnapshotReadResult of(SnapshotReadState state) {
            return new SnapshotReadResult(state, Optional.empty());
        }
        static SnapshotReadResult ready(Optional<MarketWebMapChunkSnapshot> snapshot) {
            return new SnapshotReadResult(SnapshotReadState.READY, snapshot);
        }
    }

    /**
     * 离线异步读一个区块并(就绪时)解码成快照。<b>squaremap/Xaero 范式,绝不 force</b>:
     * 主线程经 {@link OfflineChunkNbtReader#beginProbe} 发起 IOWorker 异步读(不阻塞、不生成),
     * 后续 tick 看 future done。done 后取 tag:empty/非 full → UNGENERATED;full → 受 DECODE_PER_TICK +
     * wall-clock 双闸,主线程纯内存 {@link MarketWebMapNbtChunkSnapshotReader#capture} 解码(不碰世界)。
     * <p><b>为什么安全</b>:beginProbe 返回 IOWorker 独占的 CompoundTag(非 pendingWrites 共享引用),
     * 主线程单线程解码,绝无并发 datafix 改写桶损坏(见 [[marketweb_snapshot_deadlock]]);且永不触发同步 worldgen。</p>
     */
    private synchronized SnapshotReadResult resolveSnapshotRead(ServerLevel level, int chunkX, int chunkZ,
                                                                long chunkKey, String dimensionId) {
        ChunkProbe probe = pendingProbe.get(chunkKey);
        if (probe == null) {
            if (probeStartsThisTick >= PROBE_STARTS_PER_TICK) {
                return SnapshotReadResult.of(SnapshotReadState.PENDING); // 本 tick 发起额度用尽:下 tick 再发起
            }
            // 背压硬上限:每个在途 probe 的 future 完成后挂着一整块区块 NBT(几十 KB~MB)。
            // 读盘慢时(VMware IO 抖动)若无上限,堆积成百上千块 → 内存暴涨 → Full GC → watchdog 卡死。
            // 达上限就不再发起,等已有的完成腾空间。内存占用因此有界 = MAX_INFLIGHT × 单块 NBT。
            if (pendingProbe.size() >= MAX_INFLIGHT_PROBES) {
                return SnapshotReadResult.of(SnapshotReadState.PENDING);
            }
            try {
                CompletableFuture<Optional<CompoundTag>> future =
                        OfflineChunkNbtReader.beginProbe(level, new ChunkPos(chunkX, chunkZ));
                pendingProbe.put(chunkKey, new ChunkProbe(future, snapshotTick));
                probeStartsThisTick++;
                diagProbesStarted.incrementAndGet();
            } catch (RuntimeException exception) {
                return SnapshotReadResult.of(SnapshotReadState.UNGENERATED); // 发起失败:保守当未生成
            }
            return SnapshotReadResult.of(SnapshotReadState.PENDING); // 刚发起,未决
        }
        if (!probe.future().isDone()) {
            if (snapshotTick - probe.startedTick() >= MAX_PROBE_WAIT_TICKS) {
                // 读太久:不再立刻判未生成(chunk 实际存在、只是 IO 抖动)。还有重试额度就重新发起 beginProbe,
                // 耗尽才保守当未生成。避免把"存在但读慢"误杀成永久 missing → 永久黑块。
                pendingProbe.remove(chunkKey);
                if (probe.attempts() < MAX_PROBE_RETRIES && probeStartsThisTick < PROBE_STARTS_PER_TICK
                        && pendingProbe.size() < MAX_INFLIGHT_PROBES) {
                    try {
                        CompletableFuture<Optional<CompoundTag>> retry =
                                OfflineChunkNbtReader.beginProbe(level, new ChunkPos(chunkX, chunkZ));
                        pendingProbe.put(chunkKey, new ChunkProbe(retry, snapshotTick, probe.attempts() + 1));
                        probeStartsThisTick++;
                        diagProbesStarted.incrementAndGet();
                        diagProbesTimedOut.incrementAndGet();
                        return SnapshotReadResult.of(SnapshotReadState.PENDING); // 重试已发起,下 tick 再看
                    } catch (RuntimeException exception) {
                        return SnapshotReadResult.of(SnapshotReadState.UNGENERATED); // 重新发起失败:保守当未生成
                    }
                }
                return SnapshotReadResult.of(SnapshotReadState.UNGENERATED); // 重试耗尽:保守当未生成
            }
            return SnapshotReadResult.of(SnapshotReadState.PENDING); // 仍在读盘,下 tick 再看
        }
        // future done:取独占 tag。非 full → 未生成;full → 配额内主线程解码。
        diagProbesCompleted.incrementAndGet();
        Optional<CompoundTag> tag;
        try {
            tag = probe.future().getNow(Optional.empty());
        } catch (RuntimeException exception) {
            pendingProbe.remove(chunkKey);
            return SnapshotReadResult.of(SnapshotReadState.UNGENERATED); // 读出错:保守当未生成
        }
        if (tag.isEmpty() || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
            pendingProbe.remove(chunkKey);
            return SnapshotReadResult.of(SnapshotReadState.UNGENERATED); // 磁盘没有 / 非 full → 未生成
        }
        // 解码限速(纯内存快,但一 tick 太多 tag 解码仍占主线程):配额满则保留 tag,下 tick 解码。
        if (generatedSamplesThisTick >= GENERATED_SAMPLES_PER_TICK
                || generatedSampleMillisThisTick >= MAX_GENERATED_SAMPLE_MILLIS_PER_TICK) {
            return SnapshotReadResult.of(SnapshotReadState.DECODE_THROTTLED); // tag 留在 pendingProbe,下 tick 解码
        }
        pendingProbe.remove(chunkKey);
        long startNanos = System.nanoTime();
        Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.capture(
                dimensionId, chunkX, chunkZ, tag.get(),
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        generatedSamplesThisTick++;
        generatedSampleMillisThisTick += elapsedMillis;
        if (snapshot.isPresent()) {
            diagSnapshotsDecoded.incrementAndGet();
        }
        return SnapshotReadResult.ready(snapshot);
    }

    private synchronized boolean isSkippedUngenerated(long chunkKey) {
        return skippedUngenerated.contains(chunkKey);
    }

    private synchronized void markSkippedUngenerated(long chunkKey) {
        skippedUngenerated.remove(chunkKey);
        skippedUngenerated.add(chunkKey);
        while (skippedUngenerated.size() > MAX_SKIPPED_UNGENERATED) {
            Iterator<Long> iterator = skippedUngenerated.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private record ChunkProbe(CompletableFuture<Optional<CompoundTag>> future, long startedTick, int attempts) {
        ChunkProbe(CompletableFuture<Optional<CompoundTag>> future, long startedTick) {
            this(future, startedTick, 1);
        }
    }

    public synchronized boolean startFullRender(ServerLevel level) {
        if (level == null || activeJob != null) {
            return false;
        }
        List<MarketWebMapRegionScanService.RegionFile> regions = new MarketWebMapRegionScanService(null)
                .scanRegions(level, Integer.MAX_VALUE);
        if (regions.isEmpty()) {
            return false;
        }
        // 行同步推进:fullrender 按 (regionZ, regionX) 重排为【北→南行,行内西→东】,覆盖 scanRegions 的距中心螺旋序。
        // 配合 tickBackground 的行屏障(整行落盘才推进下一行),保证渲第 N 行时第 N-1 行(北邻)已全部落盘 →
        // 每个 chunk 北邻种子都在 → 无条纹、缩略层有完整源不碎裂。其它渲染(radius/area/repair)不受影响。
        regions = new ArrayList<>(regions);
        regions.sort(java.util.Comparator
                .comparingInt(MarketWebMapRegionScanService.RegionFile::regionZ)
                .thenComparingInt(MarketWebMapRegionScanService.RegionFile::regionX));
        activeJob = RenderJob.full(regions);
        expectedRegionChunks.clear();
        for (MarketWebMapRegionScanService.RegionFile region : regions) {
            expectedRegionChunks.put(packRegion(region.regionX(), region.regionZ()), region.localChunks());
        }
        restoreProgress(level, activeJob);
        paused = false;
        tickCounter = 0;
        resetRegionReader(); // 新 fullrender:复位专用 region 读取器
        activeJob.lastSavedChunkIndex = activeJob.processedChunks;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean startRadiusRender(ServerLevel level, int centerBlockX, int centerBlockZ, int radiusBlocks) {
        if (level == null || activeJob != null || radiusBlocks <= 0) {
            return false;
        }
        int minChunkX = Math.floorDiv(centerBlockX - radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(centerBlockX + radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(centerBlockZ - radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(centerBlockZ + radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int centerChunkX = Math.floorDiv(centerBlockX, MarketWebMapConstants.CHUNK_SIZE);
        int centerChunkZ = Math.floorDiv(centerBlockZ, MarketWebMapConstants.CHUNK_SIZE);
        int radiusChunks = Math.max(
                Math.abs(minChunkX - centerChunkX),
                Math.max(Math.abs(maxChunkX - centerChunkX),
                        Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ))));
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapSpiralChunkIterator.squareSpiral(MarketWebMapConstants.OVERWORLD, centerChunkX, centerChunkZ, radiusChunks);
        if (chunks.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.radius(chunks, radiusBlocks);
        expectedRegionChunks.clear();
        paused = false;
        tickCounter = 0;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean startAreaRender(ServerLevel level, int x1, int z1, int x2, int z2) {
        if (level == null || activeJob != null) {
            return false;
        }
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = areaChunks(x1, z1, x2, z2);
        if (chunks.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.area(chunks);
        prepareExpectedRegionChunks(chunks);
        paused = false;
        tickCounter = 0;
        saveProgress(level, activeJob);
        return true;
    }

    /**
     * 把若干黑块 region 的全部 chunk 标脏入 dirtyChunks 队列(非独占,不抢 activeJob、不互相丢弃)。
     * 黑块修复专用:旧路径每次只能 startAreaRender 一个 region(activeJob 互斥)→ 一轮只修 1 个 → 极慢。
     * 走 dirtyChunks 后可一次塞进成百上千 region 的 chunk,由 tickBackground 空闲时持续消费,大幅提速。
     * 返回实际入队的 region 数(已在队列上限内的部分可能被 markDirty 丢弃)。
     */
    public synchronized int enqueueRegionsForRepair(ServerLevel level, List<int[]> regions) {
        if (level == null || regions == null || regions.isEmpty()) {
            return 0;
        }
        int queuedRegions = 0;
        for (int[] region : regions) {
            int baseChunkX = region[0] * CHUNKS_PER_REGION_AXIS;
            int baseChunkZ = region[1] * CHUNKS_PER_REGION_AXIS;
            boolean any = false;
            for (int cz = 0; cz < CHUNKS_PER_REGION_AXIS; cz++) {
                for (int cx = 0; cx < CHUNKS_PER_REGION_AXIS; cx++) {
                    if (dirtyChunks.markDirty(MarketWebMapConstants.OVERWORLD, baseChunkX + cx, baseChunkZ + cz)) {
                        any = true;
                    }
                }
            }
            if (any) {
                queuedRegions++;
            }
        }
        if (queuedRegions > 0) {
            saveDirtyChunks(level);
        }
        return queuedRegions;
    }

    public synchronized boolean startWorldBorderRender(ServerLevel level) {
        if (level == null || activeJob != null) {
            return false;
        }
        WorldBorder border = level.getWorldBorder();
        if (border == null) {
            return false;
        }
        BlockBounds bounds = blockBoundsFromWorldBorder(border);
        List<MarketWebMapRegionScanService.RegionFile> regions = filterRegionFilesToBlockBounds(
                new MarketWebMapRegionScanService(null).scanRegions(level, Integer.MAX_VALUE),
                bounds.minX(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxZ());
        if (regions.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.border(regions);
        expectedRegionChunks.clear();
        for (MarketWebMapRegionScanService.RegionFile region : regions) {
            expectedRegionChunks.put(packRegion(region.regionX(), region.regionZ()), region.localChunks());
        }
        restoreProgress(level, activeJob);
        paused = false;
        tickCounter = 0;
        activeJob.lastSavedChunkIndex = activeJob.processedChunks;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean pause() {
        if (activeJob == null) {
            return false;
        }
        paused = true;
        return true;
    }

    public synchronized boolean resume() {
        if (activeJob == null) {
            return false;
        }
        paused = false;
        return true;
    }

    public synchronized boolean cancel(ServerLevel level) {
        if (activeJob == null) {
            return false;
        }
        activeJob = null;
        expectedRegionChunks.clear();
        paused = false;
        resetRegionReader();
        if (level != null) {
            clearProgress(level);
        }
        return true;
    }

    /**
     * 清空所有渲染队列与在途状态,让后台彻底停止写盘。clearall 前调用,避免"边清边写"并发:
     * 否则清瓦片的同时后台渲染又往 square 目录写新文件 → 目录删不掉(DirectoryNotEmptyException)、
     * 清完又被立刻重写回半成品。调用后 dirtyChunks/dirtyRegions/queue/regionStates 全空,渲染静止。
     */
    public synchronized void clearAllRenderState(ServerLevel level) {
        activeJob = null;
        paused = false;
        resetRegionReader();
        expectedRegionChunks.clear();
        regionStates.clear();
        bottomRowCache.clear();
        chunkBottomRowCache.clear();
        pendingProbe.clear();
        pendingDecoded.clear();
        skippedUngenerated.clear();
        dirtyChunks.clear();
        dirtyRegions.clear();
        if (level != null) {
            clearProgress(level);
            saveDirtyChunks(level); // 把"已清空"持久化,避免重启又从盘上读回旧 dirty
        }
    }

    public synchronized boolean hasActiveJob() {
        return activeJob != null;
    }

    /** fullrender 是否活跃(专用 region 读取器在跑)。processSnapshotBudget 据此让出主线程预算,不抢 queue。 */
    public synchronized boolean hasActiveFullRender() {
        return activeJob != null && activeJob.fullRender();
    }

    public synchronized RenderStatus status(int queueSize) {
        RenderJob job = activeJob;
        return new RenderStatus(
                job == null ? "idle" : job.type,
                paused,
                queueSize,
                pendingProbe.size(),
                skippedUngenerated.size(),
                0,
                dirtyRegions.size(),
                dirtyChunks.size(),
                regionStates.size(),
                renderingRegions.get(),
                imageIO.pendingTasks(),
                failedChunks.get(),
                job == null ? 0 : job.currentRegionX(),
                job == null ? 0 : job.currentRegionZ(),
                job == null ? 0 : job.currentLocalChunk(),
                job == null ? 0 : job.processedChunks,
                job == null ? 0 : job.totalChunks(),
                job == null ? 0 : job.processedRegions,
                job == null ? 0 : job.totalRegions());
    }

    public int pendingTasks() {
        synchronized (this) {
            return pendingProbe.size()
                    + dirtyRegions.size()
                    + dirtyChunks.size()
                    + renderingRegions.get()
                    + imageIO.pendingTasks();
        }
    }

    public void recordSubmitResult(SubmitResult result) {
        if (result == SubmitResult.DEFER) {
            diagResultDefer.incrementAndGet();
        } else if (result == SubmitResult.CONSUMED) {
            diagResultConsumed.incrementAndGet();
        }
    }

    /** 诊断快照:一行字符串,定位 fullrender 卡点(读盘发起/完成/超时、解码、缺失、调度、写盘、submit 结果)。 */
    public String diagLine() {
        return "probes started=" + diagProbesStarted.get()
                + " done=" + diagProbesCompleted.get()
                + " timedOut=" + diagProbesTimedOut.get()
                + " | decoded=" + diagSnapshotsDecoded.get()
                + " missing=" + diagSnapshotsMissing.get()
                + " | regionsScheduled=" + diagRegionsScheduled.get()
                + " tilesWritten=" + diagTilesWritten.get()
                + " | submit consumed=" + diagResultConsumed.get()
                + " defer=" + diagResultDefer.get()
                + " | inflight=" + pendingProbeSizeSnapshot()
                + " regionStates=" + regionStatesSizeSnapshot()
                + " | reader region=" + readerRegionSnapshot()
                + " resolved=" + readerResolvedSnapshot() + "/" + readerExpectedSnapshot()
                + " probes=" + readerProbesSnapshot()
                + " await=" + readerAwaitSnapshot()
                + " | captureOK=" + MarketWebMapNbtChunkSnapshotReader.DIAG_OK.get()
                + " failStatus=" + MarketWebMapNbtChunkSnapshotReader.DIAG_FAIL_STATUS.get()
                + " failNoSections=" + MarketWebMapNbtChunkSnapshotReader.DIAG_FAIL_NO_SECTIONS.get();
    }

    private synchronized int pendingProbeSizeSnapshot() {
        return pendingProbe.size();
    }

    private synchronized int regionStatesSizeSnapshot() {
        return regionStates.size();
    }

    private synchronized int readerRegionSnapshot() {
        return readerRegionIndex;
    }

    private synchronized int readerResolvedSnapshot() {
        return readerState == null ? 0 : readerState.count();
    }

    private synchronized int readerExpectedSnapshot() {
        return readerState == null ? 0 : readerState.expectedCount;
    }

    private synchronized int readerProbesSnapshot() {
        return readerProbes.size();
    }

    /** 读取器是否正在等当前 region 写盘落地(1=已 schedule 在等、0=在读)。 */
    private synchronized int readerAwaitSnapshot() {
        return (readerScheduled && !readerRegionWritten) ? 1 : 0;
    }

    public synchronized void loadDirtyChunks(ServerLevel level) {
        dirtyChunks.load(dirtyChunksFile(level));
        dirtyRegions.load(dirtyRegionsFile(level));
    }

    public synchronized void saveDirtyChunks(ServerLevel level) {
        dirtyChunks.save(dirtyChunksFile(level));
        dirtyRegions.save(dirtyRegionsFile(level));
    }

    public synchronized int markRegionDirty(int regionX, int regionZ) {
        boolean marked = dirtyRegions.markDirty(MarketWebMapConstants.OVERWORLD, regionX, regionZ);
        dirtyRegions.trimToMaxRegions(configuredInt(ModConfig::marketWebMaxDirtyChunks, 200000) / CHUNKS_PER_REGION);
        return marked ? 1 : 0;
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
        imageIO.shutdown();
        synchronized (this) {
            // 离线异步读不 force、不占票据(beginProbe 走 IOWorker),清掉未决读盘 future 即可,无残留。
            pendingProbe.clear();
            skippedUngenerated.clear();
        }
    }

    private void acceptSnapshot(MarketWebMapTileCache cache,
                                MarketWebMapChunkSnapshot snapshot,
                                MarketWebMapTileQuality quality,
                                long nowMillis) {
        List<RegionState> readyStates = new ArrayList<>();
        synchronized (this) {
            long key = packRegion(regionX(snapshot.chunkX()), regionZ(snapshot.chunkZ()));
            RegionState state = regionStates.computeIfAbsent(key, ignored -> newRegionState(
                    snapshot.dimensionId(),
                    regionX(snapshot.chunkX()),
                    regionZ(snapshot.chunkZ())));
            state.put(snapshot, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            int partialFlushChunks = currentPartialFlushChunks();
            if ((state.complete() || state.count() >= partialFlushChunks) && !state.renderScheduled) {
                state.renderScheduled = true;
                readyStates.add(state);
                regionStates.remove(key);
            }
            collectOverflowRegionStates(readyStates);
        }
        for (RegionState ready : readyStates) {
            scheduleRegionRender(cache, ready, nowMillis);
        }
    }

    private void acceptMissingSnapshot(MarketWebMapTileCache cache,
                                       String dimensionId,
                                       int chunkX,
                                       int chunkZ,
                                       MarketWebMapTileQuality quality,
                                       long nowMillis) {
        List<RegionState> readyStates = new ArrayList<>();
        synchronized (this) {
            long key = packRegion(regionX(chunkX), regionZ(chunkZ));
            RegionState state = regionStates.computeIfAbsent(key, ignored -> newRegionState(
                    dimensionId,
                    regionX(chunkX),
                    regionZ(chunkZ)));
            state.missing(chunkX, chunkZ, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            diagSnapshotsMissing.incrementAndGet();
            int partialFlushChunks = currentPartialFlushChunks();
            if ((state.complete() || state.count() >= partialFlushChunks) && !state.renderScheduled) {
                state.renderScheduled = true;
                readyStates.add(state);
                regionStates.remove(key);
            }
            collectOverflowRegionStates(readyStates);
        }
        for (RegionState ready : readyStates) {
            scheduleRegionRender(cache, ready, nowMillis);
        }
    }

    private void collectOverflowRegionStates(List<RegionState> readyStates) {
        int maxTracked = Math.max(1, configuredInt(ModConfig::marketWebMaxTrackedRegionStates, 512));
        while (regionStates.size() > maxTracked) {
            Iterator<Map.Entry<Long, RegionState>> iterator = regionStates.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<Long, RegionState> eldest = iterator.next();
            iterator.remove();
            RegionState state = eldest.getValue();
            if (state != null && state.complete() && !state.renderScheduled) {
                state.renderScheduled = true;
                readyStates.add(state);
            }
        }
    }

    private void scheduleRegionRender(MarketWebMapTileCache cache, RegionState state, long nowMillis) {
        renderingRegions.incrementAndGet();
        renderExecutor.execute(() -> {
            try {
                MarketWebMapRegionImage image = new MarketWebMapRegionImage(state.dimensionId, state.regionX, state.regionZ);
                int[][] northDiskRows = seedDiskRows(cache, state.regionX, state.regionZ);
                int baseChunkX = state.regionX * CHUNKS_PER_REGION_AXIS;
                int baseChunkZ = state.regionZ * CHUNKS_PER_REGION_AXIS;
                for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                    int[] lastY = seedLastY(state.regionX, state.regionZ, localX, northDiskRows);
                    for (int localZ = 0; localZ < CHUNKS_PER_REGION_AXIS; localZ++) {
                        int chunkX = baseChunkX + localX;
                        int chunkZ = baseChunkZ + localZ;
                        MarketWebMapChunkSnapshot snapshot = state.snapshotOrNull(localX, localZ);
                        if (snapshot == null) {
                            if (state.accounted(localX, localZ) || !state.expected(localX, localZ)) {
                                image.markChunkSkipped(chunkX, chunkZ);
                            }
                            // 该 chunk 这批不在场:若它在更早批次已渲过,用其缓存底行续 lastY,让南边 chunk 有准确种子
                            // (不偏平);拿不到则保持 lastY 不变(可能上行已传下来)。绝不在此读磁盘像素(不准)。
                            int[] cachedRow = getChunkBottomRow(chunkX, chunkZ);
                            if (cachedRow != null) {
                                lastY = java.util.Arrays.copyOf(cachedRow, cachedRow.length);
                            }
                            continue;
                        }
                        image.putChunkPixels(chunkX, chunkZ, regionRenderer.renderChunk(snapshot, lastY));
                        // 渲完缓存本 chunk 底行(lastY 此刻已是本 chunk 最南行高度),供南邻/后续批次做种子。
                        putChunkBottomRow(chunkX, chunkZ, lastY);
                    }
                }
                cacheBottomRows(cache, state);
                diagRegionsScheduled.incrementAndGet();
                final long regionKey = packRegion(state.regionX, state.regionZ);
                imageIO.submit(() -> {
                    boolean wrote = image.writeDirty(cache, state.bestQuality(), nowMillis);
                    if (wrote) {
                        diagTilesWritten.incrementAndGet();
                    }
                    // fullrender 读取器:此 region 真正写盘落地后,若它正是读取器在等的 region,置 readerRegionWritten → 下一个。
                    // 无论 writeDirty 是否真写像素(全 missing 的 region 无像素变化 writeDirty=false,但它已处理完)都置位,
                    // 否则读取器永远等不到 → 卡死。
                    markReaderRegionWritten(regionKey);
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Market web map region render failed for {},{}", state.regionX, state.regionZ, exception);
            } finally {
                renderingRegions.decrementAndGet();
            }
        });
    }

    private synchronized RegionState newRegionState(String dimensionId, int regionX, int regionZ) {
        return new RegionState(
                dimensionId,
                regionX,
                regionZ,
                expectedRegionChunks.get(packRegion(regionX, regionZ)));
    }

    /** 一次性读北邻 region 的持久化底行(磁盘),供本 region 32 列 seed 复用,避免每列各读一次磁盘。无则 null。 */
    private int[][] seedDiskRows(MarketWebMapTileCache cache, int regionX, int regionZ) {
        if (cache == null) {
            return null;
        }
        return cache.readBottomRows(regionX, regionZ - 1);
    }

    private int[] seedLastY(int regionX, int regionZ, int localX, int[][] northDiskRows) {
        synchronized (this) {
            int[][] cachedRows = bottomRowCache.get(packRegion(regionX, regionZ - 1));
            if (cachedRows != null && localX >= 0 && localX < cachedRows.length && cachedRows[localX] != null) {
                return java.util.Arrays.copyOf(cachedRows[localX], cachedRows[localX].length);
            }
            RegionState north = regionStates.get(packRegion(regionX, regionZ - 1));
            if (north != null) {
                MarketWebMapChunkSnapshot snapshot = north.snapshotOrNull(localX, CHUNKS_PER_REGION_AXIS - 1);
                if (snapshot != null) {
                    return regionRenderer.lastYFromBottomRow(snapshot);
                }
            }
        }
        // 内存(LRU 缓存 + 在途 regionStates)都没命中北邻底行 → 从磁盘持久化种子读回,根除"渲染乱序/淘汰致细线"。
        if (northDiskRows != null && localX >= 0 && localX < northDiskRows.length && northDiskRows[localX] != null) {
            return java.util.Arrays.copyOf(northDiskRows[localX], northDiskRows[localX].length);
        }
        return regionRenderer.unknownLastY();
    }

    private synchronized int[] getChunkBottomRow(int chunkX, int chunkZ) {
        return chunkBottomRowCache.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    private synchronized void putChunkBottomRow(int chunkX, int chunkZ, int[] bottomRow) {
        if (bottomRow == null) {
            return;
        }
        chunkBottomRowCache.put(ChunkPos.asLong(chunkX, chunkZ), java.util.Arrays.copyOf(bottomRow, bottomRow.length));
        while (chunkBottomRowCache.size() > MAX_CHUNK_BOTTOM_ROW_CACHE) {
            Iterator<Long> iterator = chunkBottomRowCache.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private void cacheBottomRow(MarketWebMapChunkSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        int localZ = Math.floorMod(snapshot.chunkZ(), CHUNKS_PER_REGION_AXIS);
        if (localZ != CHUNKS_PER_REGION_AXIS - 1) {
            return;
        }
        synchronized (this) {
            int[][] rows = bottomRowCache.computeIfAbsent(
                    packRegion(regionX(snapshot.chunkX()), regionZ(snapshot.chunkZ())),
                    ignored -> new int[CHUNKS_PER_REGION_AXIS][]);
            rows[Math.floorMod(snapshot.chunkX(), CHUNKS_PER_REGION_AXIS)] = regionRenderer.lastYFromBottomRow(snapshot);
            while (bottomRowCache.size() > 512) {
                Iterator<Long> iterator = bottomRowCache.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void cacheBottomRows(MarketWebMapTileCache cache, RegionState state) {
        if (state == null) {
            return;
        }
        int[][] persistRows = null;
        synchronized (this) {
            int[][] rows = bottomRowCache.computeIfAbsent(
                    packRegion(state.regionX, state.regionZ),
                    ignored -> new int[CHUNKS_PER_REGION_AXIS][]);
            for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                MarketWebMapChunkSnapshot snapshot = state.snapshotOrNull(localX, CHUNKS_PER_REGION_AXIS - 1);
                if (snapshot != null) {
                    rows[localX] = regionRenderer.lastYFromBottomRow(snapshot);
                }
            }
            // 落盘快照(脱离锁外写):底行种子持久化,seedLastY 内存未命中时可从磁盘读北邻,不再依赖渲染顺序/LRU。
            persistRows = java.util.Arrays.copyOf(rows, rows.length);
            while (bottomRowCache.size() > 512) {
                Iterator<Long> iterator = bottomRowCache.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
        if (cache != null && persistRows != null) {
            final int[][] toWrite = persistRows;
            final int rx = state.regionX;
            final int rz = state.regionZ;
            imageIO.submit(() -> cache.writeBottomRows(rx, rz, toWrite)); // 异步落盘,不卡渲染线程
        }
    }

    private void restoreProgress(ServerLevel level, RenderJob job) {
        Path file = progressFile(level);
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null || !job.type.equals(json.has("type") ? json.get("type").getAsString() : "")) {
                return;
            }
            int chunkIndex = Math.max(0, json.has("chunkIndex") ? json.get("chunkIndex").getAsInt() : 0);
            if (job.fullRender()) {
                int regionIndex = Math.max(0, json.has("regionIndex") ? json.get("regionIndex").getAsInt() : Math.floorDiv(chunkIndex, CHUNKS_PER_REGION));
                int localChunkIndex = Math.max(0, json.has("localChunkIndex") ? json.get("localChunkIndex").getAsInt() : Math.floorMod(chunkIndex, CHUNKS_PER_REGION));
                job.regionIndex = Math.min(regionIndex, job.regions.size());
                job.localChunkIndex = Math.min(localChunkIndex, job.currentRegionChunkCount());
                if (job.regionIndex >= job.regions.size()) {
                    job.localChunkIndex = 0;
                } else if (job.localChunkIndex >= job.currentRegionChunkCount()) {
                    job.localChunkIndex = 0;
                    job.regionIndex++;
                }
            } else {
                job.chunkIndex = Math.min(chunkIndex, job.totalChunks());
            }
            job.processedChunks = Math.min(job.completedChunkCount(), Math.max(0,
                    json.has("processedChunks") ? json.get("processedChunks").getAsInt() : job.completedChunkCount()));
            job.processedRegions = job.completedRegionCount();
            job.lastSavedChunkIndex = job.processedChunks;
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Failed to read market web map render progress", exception);
        }
    }

    private void saveProgress(ServerLevel level, RenderJob job) {
        Path file = progressFile(level);
        if (file == null || job == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", job.type);
        json.addProperty("chunkIndex", job.completedChunkCount());
        json.addProperty("regionIndex", job.regionIndex);
        json.addProperty("localChunkIndex", job.localChunkIndex);
        json.addProperty("processedChunks", job.processedChunks);
        json.addProperty("processedRegions", job.processedRegions);
        json.addProperty("totalChunks", job.totalChunks());
        json.addProperty("totalRegions", job.totalRegions());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            LOGGER.warn("Failed to save market web map render progress", exception);
        }
    }

    private void clearProgress(ServerLevel level) {
        Path file = progressFile(level);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear market web map render progress", exception);
        }
    }

    private Path progressFile(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(MarketWebMapConstants.CACHE_DATA_DIR)
                .resolve(PROGRESS_FILE)
                .normalize();
    }

    private Path dirtyChunksFile(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(MarketWebMapConstants.CACHE_DATA_DIR)
                .resolve(DIRTY_CHUNKS_FILE)
                .normalize();
    }

    private Path dirtyRegionsFile(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(MarketWebMapConstants.CACHE_DATA_DIR)
                .resolve(DIRTY_REGIONS_FILE)
                .normalize();
    }

    private void expandDirtyRegions(ServerLevel level) {
        int maxDirtyChunks = Math.max(1, configuredInt(ModConfig::marketWebMaxDirtyChunks, 200000));
        int available = maxDirtyChunks - dirtyChunks.size();
        if (available <= 0) {
            return;
        }
        int budget = Math.min(available, Math.max(1, configuredInt(ModConfig::marketWebDirtyRegionChunksPerInterval, 512)));
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = dirtyRegions.expandChunks(budget);
        if (chunks.isEmpty()) {
            return;
        }
        for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
            dirtyChunks.markDirty(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
        }
        saveDirtyChunks(level);
    }

    private static int configuredRenderThreads() {
        int configured = configuredInt(ModConfig::marketWebRenderThreads, 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
    }

    /**
     * 本次渲染该用的 partial-flush 阈值。
     * <p>曾尝试 fullrender 模式下"满 region(1024)才 flush"以求种子完整无线,但实测会卡死:inflight 上限(96)
     * 下总有几个 chunk 还在途,region 永远差几个 complete 不了 → 解码出的 chunk 全憋在 regionStates 出不来
     * (decoded 飞涨但 regionsScheduled/tilesWritten 冻结)。这正是 effectivePartialRegionFlushChunks 注释
     * 警告的"等满 → 永不写图"。故回退:始终用配置的小阈值,让 region 渐进 flush、不憋死。
     * 条纹由 RegionRenderer "北邻种子缺失时不画阴影(delta=0)"兜底解决,不再依赖满-region 攒齐种子。</p>
     */
    private int currentPartialFlushChunks() {
        // fullrender(行同步)模式:【满 region 才 flush】,不提前 partial flush。
        // 为什么:partial flush(攒 32 chunk 就写一版 + 从 regionStates 移除)会让 region 写出只含 32/1024 chunk 的
        // 残缺图(实测=网页行内那些黑色断块),且提前 remove 后 regionStates 提前为空 → 行屏障误判"整行落盘"放行。
        // 行同步下不会死锁:同一时刻只处理一行,inflight(96)足够让一行的 region 逐个 complete;个别永远读不到的
        // chunk 由 tickBackground 的"行内残留主动 flush"(pipeline idle 时)兜底 flush 出去 → region 仍能落盘、行能推进。
        // 返回 CHUNKS_PER_REGION 即"仅 complete() 才 flush"(complete 用的是 region 真实 expectedCount,稀疏 region 也能满)。
        // 非 fullrender(黑块修复/area/radius):保持小阈值,渐进出图、不憋死(那些路径无行屏障兜底)。
        if (activeJobIsFullRender()) {
            return CHUNKS_PER_REGION;
        }
        return effectivePartialRegionFlushChunks(
                MarketWebMapTileQuality.SERVER_REGION_SCAN,
                configuredInt(ModConfig::marketWebPartialRegionFlushChunks, 32));
    }

    private boolean activeJobIsFullRender() {
        RenderJob job = activeJob;
        return job != null && job.fullRender();
    }

    /** fullrender 读取器:某 region 写盘落地后调用(imageIO 线程,故 synchronized)。若它正是读取器在等的 region,置位放行。 */
    private synchronized void markReaderRegionWritten(long regionKey) {
        if (readerAwaitWriteKey >= 0 && regionKey == readerAwaitWriteKey) {
            readerRegionWritten = true;
        }
    }

    static int effectivePartialRegionFlushChunks(MarketWebMapTileQuality quality, int configuredValue) {
        // 部分刷新阈值:攒够这么多 accounted chunk(snapshot 或 missing 都算)就先渲染+增量写盘一版,
        // 不再死等整个 region 的 1024 个 chunk 全到齐。异步离线读模式下,region 里只要有 chunk 长期卡在
        // PENDING(读盘慢/在途上限满/future 不 done),旧的"等满 1024"会让整个 region 永不写图 → 底图全黑。
        // MarketWebMapRegionImage.writeDirty 的 mergeTouchedPixels 只覆盖本批 touched 像素、保留磁盘旧值,
        // 所以多次部分刷新是干净的渐进显示(逐步成片),不会画脏条纹。quality 不再决定阈值。
        int threshold = configuredValue > 0 ? configuredValue : 64;
        return Math.max(1, Math.min(CHUNKS_PER_REGION, threshold));
    }

    private static int configuredInt(IntSupplier supplier, int fallback) {
        try {
            return supplier.getAsInt();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    private static int regionX(int chunkX) {
        return Math.floorDiv(chunkX, CHUNKS_PER_REGION_AXIS);
    }

    private static int regionZ(int chunkZ) {
        return Math.floorDiv(chunkZ, CHUNKS_PER_REGION_AXIS);
    }

    private static long packRegion(int regionX, int regionZ) {
        return ((long) regionX & 0xFFFFFFFFL) | (((long) regionZ & 0xFFFFFFFFL) << 32);
    }

    static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> areaChunksForTest(int x1, int z1, int x2, int z2) {
        return areaChunks(x1, z1, x2, z2);
    }

    private static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> areaChunks(int x1, int z1, int x2, int z2) {
        int minBlockX = Math.min(x1, x2);
        int maxBlockX = Math.max(x1, x2);
        int minBlockZ = Math.min(z1, z2);
        int maxBlockZ = Math.max(z1, z2);
        int minChunkX = Math.floorDiv(minBlockX, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(maxBlockX, MarketWebMapConstants.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(minBlockZ, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(maxBlockZ, MarketWebMapConstants.CHUNK_SIZE);
        return chunkRectangle(MarketWebMapConstants.OVERWORLD, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    private static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunkRectangle(String dimensionId,
                                                                                   int minChunkX,
                                                                                   int minChunkZ,
                                                                                   int maxChunkX,
                                                                                   int maxChunkZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || minChunkX > maxChunkX
                || minChunkZ > maxChunkZ) {
            return List.of();
        }
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = new ArrayList<>();
        int minRegionX = regionX(minChunkX);
        int maxRegionX = regionX(maxChunkX);
        int minRegionZ = regionZ(minChunkZ);
        int maxRegionZ = regionZ(maxChunkZ);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            int regionMinChunkZ = Math.max(minChunkZ, regionZ * CHUNKS_PER_REGION_AXIS);
            int regionMaxChunkZ = Math.min(maxChunkZ, regionZ * CHUNKS_PER_REGION_AXIS + CHUNKS_PER_REGION_AXIS - 1);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                int regionMinChunkX = Math.max(minChunkX, regionX * CHUNKS_PER_REGION_AXIS);
                int regionMaxChunkX = Math.min(maxChunkX, regionX * CHUNKS_PER_REGION_AXIS + CHUNKS_PER_REGION_AXIS - 1);
                for (int chunkZ = regionMinChunkZ; chunkZ <= regionMaxChunkZ; chunkZ++) {
                    for (int chunkX = regionMinChunkX; chunkX <= regionMaxChunkX; chunkX++) {
                        chunks.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, chunkX, chunkZ));
                    }
                }
            }
        }
        return List.copyOf(chunks);
    }

    private void prepareExpectedRegionChunks(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks) {
        expectedRegionChunks.clear();
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Map<Long, List<Integer>> byRegion = new LinkedHashMap<>();
        for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
            if (chunk == null || !MarketWebMapConstants.OVERWORLD.equals(chunk.dimensionId())) {
                continue;
            }
            int regionX = regionX(chunk.chunkX());
            int regionZ = regionZ(chunk.chunkZ());
            int localX = Math.floorMod(chunk.chunkX(), CHUNKS_PER_REGION_AXIS);
            int localZ = Math.floorMod(chunk.chunkZ(), CHUNKS_PER_REGION_AXIS);
            byRegion.computeIfAbsent(packRegion(regionX, regionZ), ignored -> new ArrayList<>())
                    .add(localZ * CHUNKS_PER_REGION_AXIS + localX);
        }
        expectedRegionChunks.putAll(byRegion);
    }

    private static List<MarketWebMapRegionScanService.RegionFile> filterRegionFilesToBlockBounds(
            List<MarketWebMapRegionScanService.RegionFile> regions,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ) {
        if (regions == null || regions.isEmpty()) {
            return List.of();
        }
        int minChunkX = Math.floorDiv(Math.min(minBlockX, maxBlockX), MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(Math.max(minBlockX, maxBlockX), MarketWebMapConstants.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(Math.min(minBlockZ, maxBlockZ), MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(Math.max(minBlockZ, maxBlockZ), MarketWebMapConstants.CHUNK_SIZE);
        List<MarketWebMapRegionScanService.RegionFile> filtered = new ArrayList<>();
        for (MarketWebMapRegionScanService.RegionFile region : regions) {
            if (region == null || region.localChunks().isEmpty()) {
                continue;
            }
            int baseChunkX = region.regionX() * CHUNKS_PER_REGION_AXIS;
            int baseChunkZ = region.regionZ() * CHUNKS_PER_REGION_AXIS;
            List<Integer> localChunks = new ArrayList<>();
            for (Integer localChunk : region.localChunks()) {
                if (localChunk == null) {
                    continue;
                }
                int localX = Math.floorMod(localChunk, CHUNKS_PER_REGION_AXIS);
                int localZ = Math.floorDiv(localChunk, CHUNKS_PER_REGION_AXIS);
                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                if (chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ) {
                    localChunks.add(localChunk);
                }
            }
            if (!localChunks.isEmpty()) {
                filtered.add(new MarketWebMapRegionScanService.RegionFile(region.regionX(), region.regionZ(), localChunks));
            }
        }
        return List.copyOf(filtered);
    }

    private static BlockBounds blockBoundsFromWorldBorder(WorldBorder border) {
        return new BlockBounds(
                floorToIntClamped(border.getMinX()),
                floorToIntClamped(border.getMinZ()),
                ceilMinusOneToIntClamped(border.getMaxX()),
                ceilMinusOneToIntClamped(border.getMaxZ()));
    }

    private static int floorToIntClamped(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(value);
    }

    private static int ceilMinusOneToIntClamped(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        double block = Math.ceil(value) - 1.0D;
        if (block <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (block >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) block;
    }

    private record BlockBounds(int minX, int minZ, int maxX, int maxZ) {
    }

    public record RenderStatus(String activeJob,
                               boolean paused,
                               int queueSize,
                               int activeSnapshotRequests,
                               int pendingSnapshotRequests,
                               int cachedSnapshots,
                               int dirtyRegions,
                               int dirtyChunks,
                               int trackedRegions,
                               int renderingRegions,
                               int pendingImageIo,
                               int failedChunks,
                               int currentRegionX,
                               int currentRegionZ,
                               int currentLocalChunk,
                               int processedChunks,
                               int totalChunks,
                               int processedRegions,
                               int totalRegions) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("activeJob", activeJob);
            json.addProperty("paused", paused);
            json.addProperty("queueSize", queueSize);
            json.addProperty("activeSnapshotRequests", activeSnapshotRequests);
            json.addProperty("pendingSnapshotRequests", pendingSnapshotRequests);
            json.addProperty("cachedSnapshots", cachedSnapshots);
            json.addProperty("dirtyRegions", dirtyRegions);
            json.addProperty("dirtyChunks", dirtyChunks);
            json.addProperty("trackedRegions", trackedRegions);
            json.addProperty("renderingRegions", renderingRegions);
            json.addProperty("pendingImageIo", pendingImageIo);
            json.addProperty("failedChunks", failedChunks);
            json.addProperty("currentRegionX", currentRegionX);
            json.addProperty("currentRegionZ", currentRegionZ);
            json.addProperty("currentLocalChunk", currentLocalChunk);
            json.addProperty("processedChunks", processedChunks);
            json.addProperty("totalChunks", totalChunks);
            json.addProperty("processedRegions", processedRegions);
            json.addProperty("totalRegions", totalRegions);
            return json;
        }
    }

    private static final class RegionState {
        private final String dimensionId;
        private final int regionX;
        private final int regionZ;
        private final MarketWebMapChunkSnapshot[] snapshots = new MarketWebMapChunkSnapshot[CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS];
        private final boolean[] accountedChunks = new boolean[CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS];
        private final boolean[] expectedChunks;
        private final int expectedCount;
        private MarketWebMapTileQuality bestQuality = MarketWebMapTileQuality.UNKNOWN;
        private int count;
        private boolean renderScheduled;

        private RegionState(String dimensionId, int regionX, int regionZ, List<Integer> expectedLocalChunks) {
            this.dimensionId = dimensionId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            if (expectedLocalChunks == null || expectedLocalChunks.isEmpty()) {
                this.expectedChunks = null;
                this.expectedCount = snapshots.length;
            } else {
                this.expectedChunks = new boolean[snapshots.length];
                int expected = 0;
                for (Integer localChunk : expectedLocalChunks) {
                    if (localChunk == null || localChunk < 0 || localChunk >= snapshots.length) {
                        continue;
                    }
                    if (!this.expectedChunks[localChunk]) {
                        this.expectedChunks[localChunk] = true;
                        expected++;
                    }
                }
                this.expectedCount = expected <= 0 ? snapshots.length : expected;
            }
        }

        private void put(MarketWebMapChunkSnapshot snapshot, MarketWebMapTileQuality quality) {
            int localX = snapshot.chunkX() - regionX * CHUNKS_PER_REGION_AXIS;
            int localZ = snapshot.chunkZ() - regionZ * CHUNKS_PER_REGION_AXIS;
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return;
            }
            int index = localZ * CHUNKS_PER_REGION_AXIS + localX;
            if (!accountedChunks[index]) {
                count++;
                accountedChunks[index] = true;
            }
            snapshots[index] = snapshot;
            if (quality != null && quality.priority() > bestQuality.priority()) {
                bestQuality = quality;
            }
        }

        private void missing(int chunkX, int chunkZ, MarketWebMapTileQuality quality) {
            int localX = chunkX - regionX * CHUNKS_PER_REGION_AXIS;
            int localZ = chunkZ - regionZ * CHUNKS_PER_REGION_AXIS;
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return;
            }
            int index = localZ * CHUNKS_PER_REGION_AXIS + localX;
            if (!accountedChunks[index]) {
                accountedChunks[index] = true;
                count++;
            }
            if (quality != null && quality.priority() > bestQuality.priority()) {
                bestQuality = quality;
            }
        }

        private boolean complete() {
            return count >= expectedCount;
        }

        private int count() {
            return count;
        }

        private MarketWebMapChunkSnapshot snapshot(int localX, int localZ) {
            MarketWebMapChunkSnapshot snapshot = snapshotOrNull(localX, localZ);
            if (snapshot == null) {
                throw new IllegalStateException("Region is missing chunk " + localX + "," + localZ);
            }
            return snapshot;
        }

        private MarketWebMapChunkSnapshot snapshotOrNull(int localX, int localZ) {
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return null;
            }
            return snapshots[localZ * CHUNKS_PER_REGION_AXIS + localX];
        }

        private boolean accounted(int localX, int localZ) {
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return false;
            }
            return accountedChunks[localZ * CHUNKS_PER_REGION_AXIS + localX];
        }

        private boolean expected(int localX, int localZ) {
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return false;
            }
            return expectedChunks == null || expectedChunks[localZ * CHUNKS_PER_REGION_AXIS + localX];
        }

        private MarketWebMapTileQuality bestQuality() {
            return bestQuality == MarketWebMapTileQuality.UNKNOWN ? MarketWebMapTileQuality.SERVER_REGION_SCAN : bestQuality;
        }
    }

    private static final class RenderJob {
        private final String type;
        private final List<MarketWebMapRegionScanService.RegionFile> regions;
        private final List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks;
        private final int radiusBlocks;
        private final int totalRegions;
        private int regionIndex;
        private int localChunkIndex;
        private int chunkIndex;
        private int processedChunks;
        private int processedRegions;
        private int lastSavedChunkIndex;

        private RenderJob(String type,
                          List<MarketWebMapRegionScanService.RegionFile> regions,
                          List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks,
                          int radiusBlocks) {
            this.type = type;
            this.regions = regions == null ? List.of() : List.copyOf(regions);
            this.chunks = chunks == null ? List.of() : List.copyOf(chunks);
            this.radiusBlocks = radiusBlocks;
            this.totalRegions = this.regions.isEmpty() ? countRegions(this.chunks) : this.regions.size();
        }

        private static RenderJob full(List<MarketWebMapRegionScanService.RegionFile> regions) {
            return new RenderJob("fullrender", regions, null, 0);
        }

        private static RenderJob radius(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks, int radiusBlocks) {
            return new RenderJob("radiusrender", null, chunks, radiusBlocks);
        }

        private static RenderJob area(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks) {
            return new RenderJob("arearender", null, chunks, 0);
        }

        private static RenderJob border(List<MarketWebMapRegionScanService.RegionFile> regions) {
            return new RenderJob("borderrender", regions, null, 0);
        }

        private MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk() {
            if (fullRender()) {
                MarketWebMapRegionScanService.RegionFile region = regions.get(regionIndex);
                int localChunk = region.localChunks().get(localChunkIndex);
                int localX = Math.floorMod(localChunk, CHUNKS_PER_REGION_AXIS);
                int localZ = Math.floorDiv(localChunk, CHUNKS_PER_REGION_AXIS);
                return new MarketWebMapDirtyChunkQueue.ChunkCoordinate(
                        MarketWebMapConstants.OVERWORLD,
                        region.regionX() * CHUNKS_PER_REGION_AXIS + localX,
                        region.regionZ() * CHUNKS_PER_REGION_AXIS + localZ);
            }
            return chunks.get(chunkIndex);
        }

        private void advance() {
            if (fullRender()) {
                localChunkIndex++;
                if (localChunkIndex >= currentRegionChunkCount()) {
                    localChunkIndex = 0;
                    regionIndex++;
                }
                return;
            }
            chunkIndex++;
        }

        private boolean done() {
            if (fullRender()) {
                return regionIndex >= regions.size();
            }
            return chunkIndex >= chunks.size();
        }

        private int totalChunks() {
            if (fullRender()) {
                int total = 0;
                for (MarketWebMapRegionScanService.RegionFile region : regions) {
                    total += region.localChunks().size();
                }
                return total;
            }
            return chunks.size();
        }

        private int totalRegions() {
            return totalRegions;
        }

        private int completedRegionCount() {
            if (fullRender()) {
                return Math.min(regionIndex, regions.size());
            }
            return countRegions(chunks.subList(0, Math.min(chunkIndex, chunks.size())));
        }

        private int completedChunkCount() {
            if (fullRender()) {
                return Math.min(totalChunks(), completedChunksBeforeRegion(regionIndex) + localChunkIndex);
            }
            return Math.min(chunkIndex, chunks.size());
        }

        private int currentRegionX() {
            if (fullRender()) {
                return done() || regions.isEmpty() ? 0 : regions.get(regionIndex).regionX();
            }
            if (done() || chunks.isEmpty()) {
                return 0;
            }
            return regionX(chunks.get(Math.min(chunkIndex, chunks.size() - 1)).chunkX());
        }

        /** fullrender 专用读取器:当前 region 的 expected localChunks 列表(local = localZ*32 + localX)。done 返回空。 */
        private List<Integer> currentRegionLocalChunks() {
            if (!fullRender() || done() || regionIndex < 0 || regionIndex >= regions.size()) {
                return List.of();
            }
            return regions.get(regionIndex).localChunks();
        }

        private int currentRegionZ() {
            if (fullRender()) {
                return done() || regions.isEmpty() ? 0 : regions.get(regionIndex).regionZ();
            }
            if (done() || chunks.isEmpty()) {
                return 0;
            }
            return regionZ(chunks.get(Math.min(chunkIndex, chunks.size() - 1)).chunkZ());
        }

        private int currentLocalChunk() {
            if (fullRender()) {
                return done() ? 0 : regions.get(regionIndex).localChunks().get(localChunkIndex);
            }
            if (done() || chunks.isEmpty()) {
                return 0;
            }
            MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk = chunks.get(Math.min(chunkIndex, chunks.size() - 1));
            int localX = Math.floorMod(chunk.chunkX(), CHUNKS_PER_REGION_AXIS);
            int localZ = Math.floorMod(chunk.chunkZ(), CHUNKS_PER_REGION_AXIS);
            return localZ * CHUNKS_PER_REGION_AXIS + localX;
        }

        private boolean fullRender() {
            return !regions.isEmpty();
        }

        /** region 串行用:游标当前指向的 region 序号。done 时返回 regions.size()。 */
        private int currentRegionIndex() {
            if (!fullRender()) {
                return 0;
            }
            return Math.min(regionIndex, regions.size());
        }

        private int currentRegionChunkCount() {
            if (!fullRender() || regionIndex < 0 || regionIndex >= regions.size()) {
                return 0;
            }
            return regions.get(regionIndex).localChunks().size();
        }

        private int completedChunksBeforeRegion(int endRegionIndex) {
            int total = 0;
            int limit = Math.min(Math.max(0, endRegionIndex), regions.size());
            for (int i = 0; i < limit; i++) {
                total += regions.get(i).localChunks().size();
            }
            return total;
        }

        @SuppressWarnings("unused")
        private int radiusBlocks() {
            return radiusBlocks;
        }

        private static int countRegions(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks) {
            Set<Long> regions = new HashSet<>();
            for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
                regions.add(packRegion(regionX(chunk.chunkX()), regionZ(chunk.chunkZ())));
            }
            return regions.size();
        }
    }
}
