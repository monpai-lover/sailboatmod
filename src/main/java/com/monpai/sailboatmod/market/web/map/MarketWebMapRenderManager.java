package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
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

    // All loaded captures, north-neighbor captures and offline decodes use the same
    // GENERATED_SAMPLES_PER_TICK quota and nanosecond deadline from addon settings.
    private static final int MAX_PROBE_WAIT_TICKS = 60;
    private static final int MAX_SKIPPED_UNGENERATED = 4096;
    private static final int MAX_RENDER_BACKLOG = 8;

    private final MarketWebMapRegionRenderer regionRenderer = new MarketWebMapRegionRenderer();
    private final MarketWebSquareMapImageIOExecutor imageIO = new MarketWebSquareMapImageIOExecutor();
    private final MarketWebMapDirtyChunkQueue dirtyChunks = new MarketWebMapDirtyChunkQueue();
    private final MarketWebMapDirtyRegionQueue dirtyRegions = new MarketWebMapDirtyRegionQueue();
    private final Map<Long, RegionState> regionStates = new LinkedHashMap<>();
    private final Map<Long, List<Integer>> expectedRegionChunks = new LinkedHashMap<>();
    private final LinkedHashMap<Long, int[][]> bottomRowCache = new LinkedHashMap<>();
    // 逐 chunk 实际底行缓存:只记录 snapshot 的最南行,不记录跨缺失格传下来的高度。
    private final LinkedHashMap<Long, int[]> chunkBottomRowCache = new LinkedHashMap<>();
    private static final int MAX_CHUNK_BOTTOM_ROW_CACHE = 200_000;
    private final AtomicInteger renderingRegions = new AtomicInteger();
    private final Map<Long, Integer> scheduledRegionReferences = new HashMap<>();
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
    private final Set<Long> completedProbeTasks = new HashSet<>();
    private final MarketWebMapTickBudget tickBudget = new MarketWebMapTickBudget(System::nanoTime);
    private final MarketWebMapRefreshCooldown loadedCooldown = new MarketWebMapRefreshCooldown(32768);
    private final MarketWebMapStateWriter stateWriter = new MarketWebMapStateWriter();
    private final java.util.concurrent.atomic.AtomicLong diagCooldownSkipped = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagDirtyRegionOverflow = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagStateCheckpointRequests = new java.util.concurrent.atomic.AtomicLong();
    private long snapshotTick;
    private RenderJob activeJob;
    private MarketWebMapRenderQueue transientQueue;
    private boolean progressInvalidated;
    private boolean paused;
    private int tickCounter;
    private long lastBackgroundSnapshotTick = Long.MIN_VALUE;
    private final MarketWebMapBackgroundCadence backgroundCadence = new MarketWebMapBackgroundCadence();
    private final LinkedHashMap<Long, Long> lastRegionFlushTick = new LinkedHashMap<>();
    private boolean progressCheckpointPending;
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
    private boolean readerAwaitWriteValid;                    // readerAwaitWriteKey 是否有效(用独立 bool,不靠 -1 哨兵 ——
                                                             //   packRegion 对负坐标(如 regionZ<0)会产生负 long,甚至恰好 -1L)
    private long readerAwaitWriteKey;                         // 已 schedule、正在等写盘落地的 region packed key
    private boolean readerRegionWritten;                      // 该 region 是否已真正写盘落地(imageIO 回调置位)
    // 诊断:写盘回调命中/总数(定位"region 写了但读取器没放行"类卡死)。
    private final java.util.concurrent.atomic.AtomicLong diagWriteCallbacks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong diagWriteHits = new java.util.concurrent.atomic.AtomicLong();
    public MarketWebMapRenderManager() {
        int threads = MarketWebMapSettings.renderThreads();
        this.renderExecutor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_RENDER_BACKLOG), runnable -> {
            Thread thread = new Thread(runnable, "SailboatMarketWebMap-RegionRender");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void tickBackground(ServerLevel level,
                               MarketWebMapRenderQueue queue,
                               long nowMillis) {
        if (level == null || queue == null || !MarketWebMapSettings.updatesEnabled()) { return; }
        bindRenderQueue(queue);
        beginSnapshotTick(level.getServer().getTickCount());
        MarketWebMapTileCache cache = null;
        RegionState readerReady = null;
        List<RegionState> partialReady;
        synchronized (this) {
            if (paused || lastBackgroundSnapshotTick == snapshotTick) { return; }
            lastBackgroundSnapshotTick = snapshotTick;
            tickCounter++;
            int checkpointInterval = Math.max(1, configuredInt(ModConfig::marketWebBackgroundIntervalTicks, 200));
            checkpointBackgroundState(level, checkpointInterval);
            RenderJob job = activeJob;
            boolean admissionPass = backgroundCadence.admissionDue(snapshotTick);
            int admissionBudget = Math.min(32, Math.max(1, configuredInt(ModConfig::marketWebBackgroundMaxChunksPerInterval, 512)));
            if (job != null && job.fullRender()) {
                cache = MarketWebMapTileCache.forServer(level.getServer());
                readerReady = driveRegionReader(level, cache, job, nowMillis);
                if (job.done()) {
                    clearProgress(level);
                    activeJob = null;
                    resetRegionReader();
                    expectedRegionChunks.clear();
                    paused = false;
                }
            } else if (job != null && admissionPass) {
                int attempted = 0;
                while (attempted < admissionBudget && activeJob == job && !job.done() && hasSamplingTime()) {
                    var chunk = job.chunk();
                    if (!enqueueAccepted(queue, chunk, nowMillis)) { break; }
                    job.advance();
                    job.processedChunks = job.completedChunkCount();
                    attempted++;
                }
                if (attempted > 0) {
                    backgroundCadence.markDirty();
                    job.processedRegions = job.completedRegionCount();
                    int saveInterval = Math.max(1, configuredInt(ModConfig::marketWebProgressSaveIntervalChunks, 512));
                    if (job.processedChunks - job.lastSavedChunkIndex >= saveInterval) {
                        job.lastSavedChunkIndex = job.processedChunks;
                        progressCheckpointPending = true;
                    }
                }
                if (job.done()) {
                    clearProgress(level);
                    activeJob = null;
                    progressCheckpointPending = false;
                    paused = false;
                }
            } else if (job == null && admissionPass) {
                expandDirtyRegions(level);
                var dirty = dirtyChunks.poll(Math.min(admissionBudget, queue.remainingCapacity()));
                for (var chunk : dirty) {
                    if (!hasSamplingTime() || !enqueueAccepted(queue, chunk, nowMillis)) {
                        dirtyChunks.markDirty(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
                    }
                }
                if (!dirty.isEmpty()) { backgroundCadence.markDirty(); }
            }
            // A small region has its own deadline; unrelated slow probes and dirty work cannot starve it.
            partialReady = collectTimedPartialRegions();
            if (!partialReady.isEmpty() && cache == null) { cache = MarketWebMapTileCache.forServer(level.getServer()); }
            if (activeJob == null && pendingProbe.isEmpty() && dirtyChunks.size() == 0 && queue.size() == 0
                    && regionStates.isEmpty()) {
                expectedRegionChunks.clear();
            }
        }
        if (readerReady != null && cache != null) { scheduleRegionRender(cache, readerReady, nowMillis); }
        if (cache != null) {
            for (RegionState state : partialReady) { scheduleRegionRender(cache, state, nowMillis); }
        }
    }

    private synchronized void checkpointBackgroundState(ServerLevel level, int interval) {
        if (backgroundCadence.checkpointDue(tickCounter, interval)) { saveDirtyChunks(level); }
        if (tickCounter % interval == 0 && progressCheckpointPending && activeJob != null) {
            saveProgress(level, activeJob);
            progressCheckpointPending = false;
        }
    }

    private synchronized List<RegionState> collectTimedPartialRegions() {
        List<RegionState> ready = new ArrayList<>();
        int capacity = Math.max(0, MAX_RENDER_BACKLOG - renderingRegions.get() - imageIO.pendingTasks());
        for (var entry : regionStates.entrySet()) {
            if (ready.size() >= capacity || !hasSamplingTime()) { break; }
            RegionState state = entry.getValue();
            if (!state.renderScheduled && state.count() > 0
                    && MarketWebMapBackgroundCadence.partialFlushDue(snapshotTick, state.firstPendingTick,
                    lastRegionFlushTick.get(entry.getKey()))) {
                state.renderScheduled = true;
                ready.add(state);
            }
        }
        return ready;
    }

    private boolean regionFlushAllowed(long key) {
        Long last = lastRegionFlushTick.get(key);
        return last == null || snapshotTick - last >= MarketWebMapBackgroundCadence.PIPELINE_INTERVAL_TICKS;
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
                saveProgress(level, job);
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
            backgroundCadence.markDirty();
            readerProbes.clear();
            readerNextLocalChunk = 0;
        }
        List<Integer> localChunks = job.currentRegionLocalChunks();
        int regionX = job.currentRegionX();
        int regionZ = job.currentRegionZ();
        int baseChunkX = regionX * CHUNKS_PER_REGION_AXIS;
        int baseChunkZ = regionZ * CHUNKS_PER_REGION_AXIS;

        // 3) 发起 probe:本 tick 在 inflight 上限 + 发起额度内,把还没发起的 chunk 发出去。
        while (readerNextLocalChunk < localChunks.size() && hasRenderCapacity()
                && tickBudget.tryStartProbe(pendingProbe.size() + readerProbes.size(), MarketWebMapSettings.maxInflightProbes())) {
            int local = localChunks.get(readerNextLocalChunk);
            int chunkX = baseChunkX + Math.floorMod(local, CHUNKS_PER_REGION_AXIS);
            int chunkZ = baseChunkZ + Math.floorDiv(local, CHUNKS_PER_REGION_AXIS);
            try {
                var future = OfflineChunkNbtReader.beginProbe(level, new ChunkPos(chunkX, chunkZ));
                readerProbes.put(ChunkPos.asLong(chunkX, chunkZ), new ChunkProbe(future, snapshotTick));
                readerNextLocalChunk++;
                diagProbesStarted.incrementAndGet();
            } catch (RuntimeException exception) {
                // Read failures are retryable and are not evidence that terrain is missing.
                break;
            }
        }

        Iterator<Map.Entry<Long, ChunkProbe>> iterator = readerProbes.entrySet().iterator();
        while (iterator.hasNext() && hasSamplingTime()) {
            Map.Entry<Long, ChunkProbe> entry = iterator.next();
            ChunkProbe probe = entry.getValue();
            long chunkKey = entry.getKey();
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            if (!probe.future().isDone() || probe.future().isCompletedExceptionally()) {
                if (snapshotTick - probe.startedTick() == MAX_PROBE_WAIT_TICKS) {
                    diagProbesTimedOut.incrementAndGet();
                }
                if (probe.future().isCompletedExceptionally()
                        && tickBudget.tryStartProbe(pendingProbe.size() + readerProbes.size() - 1, MarketWebMapSettings.maxInflightProbes())) {
                    try {
                        var retry = OfflineChunkNbtReader.beginProbe(level, new ChunkPos(chunkX, chunkZ));
                        entry.setValue(new ChunkProbe(retry, snapshotTick, probe.attempts() + 1));
                        diagProbesStarted.incrementAndGet();
                        diagProbesTimedOut.incrementAndGet();
                    } catch (RuntimeException ignored) { }
                }
                continue;
            }
            Optional<CompoundTag> tag = probe.future().getNow(Optional.empty());
            if (tag.isEmpty() || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
                iterator.remove();
                diagProbesCompleted.incrementAndGet();
                markSnapshotMissing(readerState, chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                diagSnapshotsMissing.incrementAndGet();
                continue;
            }
            if (!tickBudget.tryCapture()) {
                break; // Keep the completed future and real terrain for the next actual tick.
            }
            iterator.remove();
            diagProbesCompleted.incrementAndGet();
            Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.capture(
                    MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, tag.get(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight());
            if (snapshot.isPresent()) {
                diagSnapshotsDecoded.incrementAndGet();
                readerState.put(snapshot.get(), MarketWebMapTileQuality.SERVER_REGION_SCAN);
                cacheBottomRow(snapshot.get());
                loadedCooldown.record(chunkKey, nowMillis);
            } else {
                markSnapshotMissing(readerState, chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                diagSnapshotsMissing.incrementAndGet();
            }
        }

        // 6) complete → schedule(锁外)。标记 readerScheduled + 待写盘 key,等写盘回调置位 readerRegionWritten。
        if (readerState.complete() && !readerState.renderScheduled) {
            readerState.renderScheduled = true;
            readerScheduled = true;
            readerAwaitWriteKey = packRegion(regionX, regionZ);
            readerAwaitWriteValid = true;
            readerRegionWritten = false;
            job.processedChunks = job.completedChunkCount();
            job.processedRegions = job.completedRegionCount();
            return readerState; // 锁外 scheduleRegionRender
        }
        return null;
    }

    /** 读取器:当前 region 写盘落地后,游标直接跳到下一 region 起始。 */
    private void advanceJobPastReaderRegion(RenderJob job) {
        if (job == null || !job.fullRender()) {
            return;
        }
        job.advanceToNextRegion();
        job.processedChunks = job.completedChunkCount();
        job.processedRegions = job.completedRegionCount();
    }

    /** 读取器:当前 region 落盘后复位,准备下一 region(保留 readerRegionIndex 由 driveRegionReader 重新绑定)。 */
    private void resetRegionReaderForNextRegion() {
        readerState = null;
        readerProbes.clear();
        readerNextLocalChunk = 0;
        readerScheduled = false;
        readerAwaitWriteValid = false;
        readerAwaitWriteKey = 0L;
        readerRegionWritten = false;
        readerRegionIndex = -1; // 强制下 tick 按游标重新绑定
    }

    /** 完全复位读取器(start/cancel/clearAll)。 */
    private void resetRegionReader() {
        readerState = null;
        readerProbes.clear();
        readerNextLocalChunk = 0;
        readerScheduled = false;
        readerAwaitWriteValid = false;
        readerAwaitWriteKey = 0L;
        readerRegionWritten = false;
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

    /** Idempotent: every sampling path receives the actual server tick id. */
    public synchronized void beginSnapshotTick(long serverTickId) {
        if (tickBudget.beginTick(serverTickId, MarketWebMapSettings.mainThreadBudgetNanos(),
                MarketWebMapSettings.maxSnapshotsPerTick(), MarketWebMapSettings.maxProbeStartsPerTick())) {
            snapshotTick++;
        }
    }

    public synchronized boolean isPaused() {
        return paused || !MarketWebMapSettings.updatesEnabled();
    }

    public synchronized boolean hasSamplingTime() {
        return !isPaused() && tickBudget.hasTime() && hasRenderCapacity();
    }

    private synchronized boolean hasRenderCapacity() {
        return renderingRegions.get() + imageIO.pendingTasks() < MAX_RENDER_BACKLOG;
    }

    private static boolean enqueueAccepted(MarketWebMapRenderQueue queue,
                                            MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk, long nowMillis) {
        return queue.enqueue(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ(), nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN)
                || queue.contains(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
    }

    /**
     * 每 tick 主动排干【已完成】的在途 probe(解码或判未生成),不依赖该 chunk 的 task 再次被 poll。
     * <p><b>死锁根因</b>:旧逻辑里 future done 的 probe 只在其 task 被 {@link #submitSnapshot} 重新处理时才解码;
     * 但 fullrender 队列有数十万 task、每 tick 只 poll ~96 个,DEFER 重入队到队尾后要数千 tick 才轮到 →
     * 已 done 的 256 个 probe 长期占着 the shared in-flight cap 槽位不释放 → inflight 永满 → 新 probe
     * 发不出 → 整个管线冻结(实测:probes/decoded/tilesWritten 全停,只有 defer 疯涨)。
     * <p>这里每 tick 把所有 done 的 probe 直接解码完、accept 出去、腾空槽位,受同一 GENERATED_SAMPLES/wall-clock
     * 双闸节流(不卡主线程)。槽位及时释放后 inflight 不再永满,新 chunk 持续流入,管线恢复流动。</p>
     */
    public void drainCompletedProbes(ServerLevel level, MarketWebMapTileCache cache, long nowMillis) {
        if (level == null || cache == null || isPaused()) {
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
                if (!hasSamplingTime()) {
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
                    // A fullrender temporarily stops polling the ordinary queue. Restart failed
                    // completed reads here too, or they can occupy every shared probe slot forever.
                    resolveSnapshotRead(level, chunkX, chunkZ, chunkKey, MarketWebMapConstants.OVERWORLD);
                    continue;
                }
                if (tag.isEmpty() || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
                    iterator.remove();
                    diagProbesCompleted.incrementAndGet();
                    completedProbeTasks.add(chunkKey);
                    markSkippedUngenerated(chunkKey);
                    ungenerated.add(new long[]{chunkX, chunkZ});
                    continue;
                }
                if (!tickBudget.tryCapture()) {
                    break;
                }
                iterator.remove();
                diagProbesCompleted.incrementAndGet();
                completedProbeTasks.add(chunkKey);
                Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.capture(
                        MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, tag.get(),
                        level.getMinBuildHeight(), level.getMaxBuildHeight());
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
            MarketWebMapChunkSnapshot snapshot = takePendingDecoded(key);
            if (snapshot != null) {
                loadedCooldown.record(key, nowMillis);
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
        if (!hasSamplingTime()) {
            return SubmitResult.DEFER;
        }
        long chunkKey = ChunkPos.asLong(task.chunkX(), task.chunkZ());
        if (consumeCompletedProbeTask(chunkKey)) {
            return SubmitResult.CONSUMED;
        }
        if (!loadedCooldown.ready(chunkKey, nowMillis, MarketWebMapSettings.loadedRefreshCooldownMillis(),
                task.quality() == MarketWebMapTileQuality.SERVER_REGION_SCAN)) {
            diagCooldownSkipped.incrementAndGet();
            return SubmitResult.CONSUMED;
        }
        if (level.getChunkSource().getChunkNow(task.chunkX(), task.chunkZ()) != null) {
            if (!tickBudget.tryCapture()) {
                return SubmitResult.DEFER;
            }
            Optional<MarketWebMapChunkSnapshot> loaded = MarketWebMapChunkSnapshot.capture(level, task.chunkX(), task.chunkZ());
            if (loaded.isPresent()) {
                captureGeneratedNorthNeighbor(level, task.chunkX(), task.chunkZ() - 1).ifPresent(this::cacheBottomRow);
                acceptSnapshot(cache, loaded.get(), task.quality(), nowMillis);
                loadedCooldown.record(chunkKey, nowMillis);
                return SubmitResult.CONSUMED;
            }
        }

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
                    loadedCooldown.record(chunkKey, nowMillis);
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
     * 北邻采样与主区块共用捕获配额和截止时间;不足时保留已有种子,不触发额外读盘。
     */
    private Optional<MarketWebMapChunkSnapshot> captureGeneratedNorthNeighbor(ServerLevel level, int chunkX, int chunkZ) {
        if (!hasSamplingTime() || getChunkBottomRow(chunkX, chunkZ) != null) {
            return Optional.empty();
        }
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
            if (!tickBudget.tryCapture()) {
                return Optional.empty();
            }
            return MarketWebMapChunkSnapshot.capture(level, chunkX, chunkZ);
        }
        // Optional seam hints never launch extra disk probes. Existing decoded rows remain usable.
        return Optional.empty();
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
        if (!hasSamplingTime()) {
            return SnapshotReadResult.of(SnapshotReadState.DECODE_THROTTLED);
        }
        ChunkProbe probe = pendingProbe.get(chunkKey);
        if (probe == null || probe.future().isCompletedExceptionally()) {
            int inflight = pendingProbe.size() + readerProbes.size() - (probe == null ? 0 : 1);
            if (!tickBudget.tryStartProbe(inflight, MarketWebMapSettings.maxInflightProbes())) {
                return SnapshotReadResult.of(SnapshotReadState.PENDING);
            }
            try {
                var future = OfflineChunkNbtReader.beginProbe(level, new ChunkPos(chunkX, chunkZ));
                pendingProbe.put(chunkKey, new ChunkProbe(future, snapshotTick, probe == null ? 1 : probe.attempts() + 1));
                backgroundCadence.markDirty();
                diagProbesStarted.incrementAndGet();
                if (probe != null) {
                    diagProbesTimedOut.incrementAndGet();
                }
            } catch (RuntimeException exception) {
                // Preserve this task for retry; failed/slow IO is not proof of a missing chunk.
            }
            return SnapshotReadResult.of(SnapshotReadState.PENDING);
        }
        if (!probe.future().isDone()) {
            if (snapshotTick - probe.startedTick() == MAX_PROBE_WAIT_TICKS) {
                diagProbesTimedOut.incrementAndGet();
            }
            return SnapshotReadResult.of(SnapshotReadState.PENDING);
        }
        Optional<CompoundTag> tag = probe.future().getNow(Optional.empty());
        if (tag.isEmpty() || OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
            pendingProbe.remove(chunkKey);
            diagProbesCompleted.incrementAndGet();
            return SnapshotReadResult.of(SnapshotReadState.UNGENERATED);
        }
        if (!tickBudget.tryCapture()) {
            return SnapshotReadResult.of(SnapshotReadState.DECODE_THROTTLED);
        }
        pendingProbe.remove(chunkKey);
        diagProbesCompleted.incrementAndGet();
        Optional<MarketWebMapChunkSnapshot> snapshot = MarketWebMapNbtChunkSnapshotReader.capture(
                dimensionId, chunkX, chunkZ, tag.get(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (snapshot.isPresent()) {
            diagSnapshotsDecoded.incrementAndGet();
        }
        return SnapshotReadResult.ready(snapshot);
    }

    private synchronized MarketWebMapChunkSnapshot takePendingDecoded(long key) {
        return pendingDecoded.remove(key);
    }

    private synchronized boolean consumeCompletedProbeTask(long key) {
        return completedProbeTasks.remove(key);
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

    /** Queue cheap region coordinates; chunk expansion stays inside subsequent tick budgets. */
    public synchronized int enqueueRegionsForRepair(ServerLevel level, List<int[]> regions) {
        if (level == null || regions == null || regions.isEmpty() || !MarketWebMapSettings.updatesEnabled()) {
            return 0;
        }
        int queuedRegions = 0;
        for (int[] region : regions) {
            if (region != null && region.length >= 2) {
                queuedRegions += markRegionDirty(region[0], region[1]);
            }
        }
        if (queuedRegions > 0) { backgroundCadence.markDirty(); }
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
        if (paused) {
            return false;
        }
        paused = true;
        return true;
    }

    public synchronized boolean resume() {
        if (!paused) {
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
        clearProgress(level);
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
        completedProbeTasks.clear();
        skippedUngenerated.clear();
        loadedCooldown.clear();
        lastRegionFlushTick.clear();
        progressCheckpointPending = false;
        dirtyChunks.clear();
        dirtyRegions.clear();
        clearProgress(level);
        if (level != null) {
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

    /** A foreground image must not race the older snapshots still accumulating in this region. */
    public synchronized boolean isCurrentReaderRegion(int chunkX, int chunkZ) {
        RenderJob job = activeJob;
        return job != null && job.fullRender() && !job.done()
                && regionX(chunkX) == job.currentRegionX() && regionZ(chunkZ) == job.currentRegionZ();
    }

    public synchronized RenderStatus status(int queueSize) {
        RenderJob job = activeJob;
        return new RenderStatus(
                job == null ? "idle" : job.type,
                isPaused(),
                queueSize,
                // fullrender 走专用读取器(probe 在 readerProbes,不在 pendingProbe;state 是 readerState,不在 regionStates),
                // 这里把读取器的在途 probe / 当前 state 也计入,否则 status 显示全 0、误以为 idle。
                pendingProbe.size() + readerProbes.size(),
                skippedUngenerated.size(),
                0,
                dirtyRegions.size(),
                dirtyChunks.size(),
                regionStates.size() + (readerState != null ? 1 : 0),
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
                + " writeCb=" + diagWriteHits.get() + "/" + diagWriteCallbacks.get()
                + " | tick captures=" + tickBudget.captures() + " starts=" + tickBudget.probeStarts()
                + " elapsedNanos=" + tickBudget.elapsedNanos() + " cooldownSkipped=" + diagCooldownSkipped.get()
                + " dirtyRegionOverflow=" + diagDirtyRegionOverflow.get()
                + " stateSaves=" + diagStateCheckpointRequests.get()
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

    public synchronized void savePendingWork(ServerLevel level, MarketWebMapRenderQueue queue) {
        if (queue != null) {
            for (var task : queue.snapshot()) {
                dirtyChunks.markDirty(task.dimensionId(), task.chunkX(), task.chunkZ());
            }
        }
        for (long key : pendingProbe.keySet()) {
            dirtyChunks.markDirty(MarketWebMapConstants.OVERWORLD, ChunkPos.getX(key), ChunkPos.getZ(key));
        }
        for (RegionState state : regionStates.values()) {
            dirtyRegions.markDirtyFromStart(state.dimensionId, state.regionX, state.regionZ);
        }
        for (long key : scheduledRegionReferences.keySet()) {
            dirtyRegions.markDirtyFromStart(MarketWebMapConstants.OVERWORLD,
                    MarketWebMapRegionWatcher.unpackRegionX(key), MarketWebMapRegionWatcher.unpackRegionZ(key));
        }
        if (readerState != null) {
            dirtyRegions.markDirtyFromStart(readerState.dimensionId, readerState.regionX, readerState.regionZ);
        }
        if (activeJob != null) { saveProgress(level, activeJob); }
        saveDirtyChunks(level);
    }

    public synchronized void saveDirtyChunks(ServerLevel level) {
        diagStateCheckpointRequests.incrementAndGet();
        if (stateWriter.requestDirtyQueues(this, dirtyChunks, dirtyChunksFile(level), dirtyRegions, dirtyRegionsFile(level),
                this::recoveryRegionsForSnapshot)) {
            backgroundCadence.checkpointRequested();
        }
    }

    public synchronized void bindRenderQueue(MarketWebMapRenderQueue queue) {
        transientQueue = queue;
    }

    /** Called by the state writer under this mutation lock; no IO or live queue mutation. */
    private synchronized List<MarketWebMapDirtyRegionQueue.RegionCoordinate> recoveryRegionsForSnapshot() {
        Set<MarketWebMapDirtyRegionQueue.RegionCoordinate> regions = new LinkedHashSet<>();
        if (transientQueue != null) {
            for (var task : transientQueue.snapshot()) {
                regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(task.dimensionId(), regionX(task.chunkX()), regionZ(task.chunkZ())));
            }
        }
        for (long key : pendingProbe.keySet()) {
            regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(MarketWebMapConstants.OVERWORLD,
                    regionX(ChunkPos.getX(key)), regionZ(ChunkPos.getZ(key))));
        }
        Set<Long> decodedOrAwaitingAcknowledgement = new HashSet<>(pendingDecoded.keySet());
        decodedOrAwaitingAcknowledgement.addAll(completedProbeTasks);
        for (long key : decodedOrAwaitingAcknowledgement) {
            regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(MarketWebMapConstants.OVERWORLD,
                    regionX(ChunkPos.getX(key)), regionZ(ChunkPos.getZ(key))));
        }
        for (RegionState state : regionStates.values()) {
            regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(state.dimensionId, state.regionX, state.regionZ));
        }
        for (long key : scheduledRegionReferences.keySet()) {
            regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(MarketWebMapConstants.OVERWORLD,
                    MarketWebMapRegionWatcher.unpackRegionX(key), MarketWebMapRegionWatcher.unpackRegionZ(key)));
        }
        if (readerState != null) {
            regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(readerState.dimensionId, readerState.regionX, readerState.regionZ));
        }
        for (long key : readerProbes.keySet()) {
            regions.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(MarketWebMapConstants.OVERWORLD,
                    regionX(ChunkPos.getX(key)), regionZ(ChunkPos.getZ(key))));
        }
        return List.copyOf(regions);
    }

    /** Transfer ownership before a reserved foreground claim is released; never grow past backlog capacity. */
    public synchronized boolean retainUnloadedForeground(MarketWebMapRenderQueue.Task task) {
        if (task == null || !MarketWebMapConstants.OVERWORLD.equals(task.dimensionId())) { return false; }
        boolean existing = dirtyChunks.contains(task.dimensionId(), task.chunkX(), task.chunkZ());
        int limit = Math.max(1, configuredInt(ModConfig::marketWebMaxDirtyChunks, 200000));
        if (!existing && dirtyChunks.size() >= limit) { return false; }
        dirtyChunks.markDirty(task.dimensionId(), task.chunkX(), task.chunkZ());
        backgroundCadence.markDirty();
        return true;
    }

    public synchronized int remainingDirtyRegionCapacity() {
        int limit = Math.max(MarketWebMapRegionWatcher.MAX_DIRTY_REGIONS,
                configuredInt(ModConfig::marketWebMaxDirtyChunks, 200000) / CHUNKS_PER_REGION);
        return Math.max(0, limit - dirtyRegions.size());
    }

    public synchronized int markRegionDirty(int regionX, int regionZ) {
        if (remainingDirtyRegionCapacity() <= 0) {
            diagDirtyRegionOverflow.incrementAndGet();
            return 0;
        }
        skippedUngenerated.removeIf(key -> regionX(ChunkPos.getX(key)) == regionX && regionZ(ChunkPos.getZ(key)) == regionZ);
        boolean changed = dirtyRegions.markDirty(MarketWebMapConstants.OVERWORLD, regionX, regionZ);
        if (changed) { backgroundCadence.markDirty(); }
        return changed ? 1 : 0;
    }

    /** Recovery replays accepted work; ordinary admission limits must never truncate it. */
    public synchronized int retainRegionForRecovery(int regionX, int regionZ) {
        boolean changed = dirtyRegions.markDirtyFromStart(MarketWebMapConstants.OVERWORLD, regionX, regionZ);
        if (changed) { backgroundCadence.markDirty(); }
        return changed ? 1 : 0;
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
        imageIO.shutdown();
        stateWriter.shutdown(5000L);
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
            cacheBottomRow(snapshot);
            long key = packRegion(regionX(snapshot.chunkX()), regionZ(snapshot.chunkZ()));
            RegionState state = regionStates.computeIfAbsent(key, ignored -> newRegionState(
                    snapshot.dimensionId(),
                    regionX(snapshot.chunkX()),
                    regionZ(snapshot.chunkZ())));
            state.put(snapshot, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            int partialFlushChunks = currentPartialFlushChunks();
            backgroundCadence.markDirty();
            if ((state.complete() || state.count() >= partialFlushChunks) && !state.renderScheduled && regionFlushAllowed(key)) {
                state.renderScheduled = true;
                readyStates.add(state);
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
            markSnapshotMissing(state, chunkX, chunkZ, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            diagSnapshotsMissing.incrementAndGet();
            int partialFlushChunks = currentPartialFlushChunks();
            backgroundCadence.markDirty();
            if ((state.complete() || state.count() >= partialFlushChunks) && !state.renderScheduled && regionFlushAllowed(key)) {
                state.renderScheduled = true;
                readyStates.add(state);
            }
            collectOverflowRegionStates(readyStates);
        }
        for (RegionState ready : readyStates) {
            scheduleRegionRender(cache, ready, nowMillis);
        }
    }

    private void collectOverflowRegionStates(List<RegionState> readyStates) {
        int maxTracked = Math.max(1, configuredInt(ModConfig::marketWebMaxTrackedRegionStates, 512));
        int excess = regionStates.size() - maxTracked;
        for (RegionState state : regionStates.values()) {
            if (excess <= 0) { return; }
            if (state != null && !state.renderScheduled && regionFlushAllowed(packRegion(state.regionX, state.regionZ))) {
                state.renderScheduled = true;
                readyStates.add(state);
                excess--;
            }
        }
    }

    private synchronized void retainRenderState(RegionState state) {
        state.renderScheduled = false;
        if (state == readerState) {
            readerScheduled = false;
            readerAwaitWriteValid = false;
        } else {
            long key = packRegion(state.regionX, state.regionZ);
            RegionState existing = regionStates.get(key);
            if (existing != null && existing != state) {
                for (int localZ = 0; localZ < CHUNKS_PER_REGION_AXIS; localZ++) {
                    for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                        var snapshot = existing.snapshotOrNull(localX, localZ);
                        if (snapshot != null) {
                            state.put(snapshot, existing.bestQuality());
                        } else if (existing.accounted(localX, localZ)) {
                            state.missing(state.regionX * CHUNKS_PER_REGION_AXIS + localX,
                                    state.regionZ * CHUNKS_PER_REGION_AXIS + localZ, existing.bestQuality());
                        }
                    }
                }
            }
            regionStates.put(key, state);
        }
    }

    private void scheduleRegionRender(MarketWebMapTileCache cache, RegionState state, long nowMillis) {
        long regionKey = packRegion(state.regionX, state.regionZ);
        synchronized (this) {
            if (!hasRenderCapacity() || renderExecutor.isShutdown()) {
                retainRenderState(state);
                return;
            }
            renderingRegions.incrementAndGet();
            scheduledRegionReferences.merge(regionKey, 1, Integer::sum);
            lastRegionFlushTick.remove(regionKey);
            lastRegionFlushTick.put(regionKey, snapshotTick);
            while (lastRegionFlushTick.size() > 4096) {
                var oldest = lastRegionFlushTick.keySet().iterator(); oldest.next(); oldest.remove();
            }
            // Transfer ownership under one lock so persistence never observes a detached state.
            regionStates.remove(regionKey, state);
        }
        try {
            renderExecutor.execute(() -> {
                try {
                    MarketWebMapRegionImage image = new MarketWebMapRegionImage(state.dimensionId, state.regionX, state.regionZ);
                    int[][] northDiskRows = seedDiskRows(cache, state.regionX, state.regionZ);
                    int baseChunkX = state.regionX * CHUNKS_PER_REGION_AXIS;
                    int baseChunkZ = state.regionZ * CHUNKS_PER_REGION_AXIS;
                    int[][] regionBottomSeed = new int[CHUNKS_PER_REGION_AXIS][];
                    for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                        for (int localZ = 0; localZ < CHUNKS_PER_REGION_AXIS; localZ++) {
                            int chunkX = baseChunkX + localX;
                            int chunkZ = baseChunkZ + localZ;
                            MarketWebMapChunkSnapshot snapshot = state.snapshotOrNull(localX, localZ);
                            if (snapshot == null) {
                                if (state.accounted(localX, localZ) || !state.expected(localX, localZ)) {
                                    image.markChunkSkipped(chunkX, chunkZ);
                                }
                                if (localZ == CHUNKS_PER_REGION_AXIS - 1 && state.accounted(localX, localZ)) {
                                    regionBottomSeed[localX] = regionRenderer.unknownLastY();
                                }
                                continue;
                            }
                            int[] lastY = seedChunkLastY(state, localX, localZ, northDiskRows);
                            image.putChunkPixels(chunkX, chunkZ, regionRenderer.renderChunk(snapshot, lastY));
                            if (localZ == CHUNKS_PER_REGION_AXIS - 1) {
                                regionBottomSeed[localX] = regionRenderer.lastYFromBottomRow(snapshot);
                            }
                        }
                    }
                    cacheBottomRows(cache, state, regionBottomSeed);
                    diagRegionsScheduled.incrementAndGet();
                    imageIO.submit(() -> {
                        try {
                            boolean wrote = image.writeDirty(cache, state.bestQuality(), nowMillis);
                            if (wrote) { diagTilesWritten.incrementAndGet(); }
                            // Complete all-missing regions also finish without changed pixels.
                            markReaderRegionWritten(regionKey, state);
                        } catch (RuntimeException exception) {
                            retainRenderState(state);
                            throw exception;
                        } finally {
                            releaseScheduledRegion(regionKey);
                        }
                    });
                } catch (RuntimeException exception) {
                    retainRenderState(state);
                    releaseScheduledRegion(regionKey);
                    LOGGER.warn("Market web map region render failed for {},{}", state.regionX, state.regionZ, exception);
                } finally {
                    renderingRegions.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException exception) {
            renderingRegions.decrementAndGet();
            retainRenderState(state);
            releaseScheduledRegion(regionKey);
        }
    }

    private synchronized void releaseScheduledRegion(long key) {
        backgroundCadence.markDirty();
        Integer count = scheduledRegionReferences.get(key);
        if (count == null || count <= 1) {
            scheduledRegionReferences.remove(key);
        } else {
            scheduledRegionReferences.put(key, count - 1);
        }
    }

    private synchronized RegionState newRegionState(String dimensionId, int regionX, int regionZ) {
        RegionState state = new RegionState(dimensionId, regionX, regionZ,
                expectedRegionChunks.get(packRegion(regionX, regionZ)));
        state.firstPendingTick = snapshotTick;
        return state;
    }

    /** 一次性读北邻 region 的持久化底行(磁盘),供本 region 32 列 seed 复用,避免每列各读一次磁盘。无则 null。 */
    private int[][] seedDiskRows(MarketWebMapTileCache cache, int regionX, int regionZ) {
        if (cache == null) {
            return null;
        }
        return cache.readBottomRows(regionX, regionZ - 1);
    }

    private int[] seedChunkLastY(RegionState state, int localX, int localZ, int[][] northDiskRows) {
        // 本批的正北快照优先于任何旧缓存;其它情况只使用坐标相邻的 chunk 底行。
        MarketWebMapChunkSnapshot north = state.snapshotOrNull(localX, localZ - 1);
        if (north != null) {
            return regionRenderer.lastYFromBottomRow(north);
        }
        if (state.accounted(localX, localZ - 1)) {
            return regionRenderer.unknownLastY();
        }
        int chunkX = state.regionX * CHUNKS_PER_REGION_AXIS + localX;
        int chunkZ = state.regionZ * CHUNKS_PER_REGION_AXIS + localZ;
        int[] cachedRow = getChunkBottomRow(chunkX, chunkZ - 1);
        if (cachedRow != null) {
            return java.util.Arrays.copyOf(cachedRow, cachedRow.length);
        }
        return localZ == 0
                ? seedLastY(state.regionX, state.regionZ, localX, northDiskRows)
                : regionRenderer.unknownLastY();
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
        cacheBottomRow(snapshot.chunkX(), snapshot.chunkZ(), regionRenderer.lastYFromBottomRow(snapshot));
    }

    private synchronized void markSnapshotMissing(RegionState state,
                                                  int chunkX,
                                                  int chunkZ,
                                                  MarketWebMapTileQuality quality) {
        // 明确缺失优先于旧缓存;同时阻止下一批/南邻 region 重新借用这个过期的高度。
        cacheBottomRow(chunkX, chunkZ, regionRenderer.unknownLastY());
        state.missing(chunkX, chunkZ, quality);
    }

    private void cacheBottomRow(int chunkX, int chunkZ, int[] actualBottomRow) {
        putChunkBottomRow(chunkX, chunkZ, actualBottomRow);
        int localZ = Math.floorMod(chunkZ, CHUNKS_PER_REGION_AXIS);
        if (localZ != CHUNKS_PER_REGION_AXIS - 1) {
            return;
        }
        synchronized (this) {
            int[][] rows = bottomRowCache.computeIfAbsent(
                    packRegion(regionX(chunkX), regionZ(chunkZ)),
                    ignored -> new int[CHUNKS_PER_REGION_AXIS][]);
            rows[Math.floorMod(chunkX, CHUNKS_PER_REGION_AXIS)] = actualBottomRow;
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

    /**
     * 持久化本 region 的 seam 底行种子(供南邻 region 顶行做高度阴影北邻参考)。
     * {@code regionBottomSeed[localX]} 只含本批实际采到的最南行;未更新的列保留已有真实接缝。
     */
    private void cacheBottomRows(MarketWebMapTileCache cache, RegionState state, int[][] regionBottomSeed) {
        if (state == null || regionBottomSeed == null) {
            return;
        }
        int[][] diskRows = cache == null ? null : cache.readBottomRows(state.regionX, state.regionZ);
        int[][] mergedRows;
        synchronized (this) {
            int[][] rows = bottomRowCache.computeIfAbsent(
                    packRegion(state.regionX, state.regionZ),
                    ignored -> new int[CHUNKS_PER_REGION_AXIS][]);
            for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                // 已接收的真实快照比延迟完成的旧渲染更近;旧任务只能补空列。
                if (rows[localX] == null && regionBottomSeed[localX] != null) {
                    rows[localX] = regionBottomSeed[localX];
                } else if (rows[localX] == null && diskRows != null) {
                    rows[localX] = diskRows[localX];
                }
            }
            mergedRows = rows;
            while (bottomRowCache.size() > 512) {
                Iterator<Long> iterator = bottomRowCache.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
        if (cache != null) {
            final int rx = state.regionX;
            final int rz = state.regionZ;
            imageIO.submit(() -> {
                int[][] toWrite;
                synchronized (this) {
                    // 两批 enqueue 可能乱序;在唯一写盘线程执行时再取最新合并结果,不写回较旧的副本。
                    int[][] latest = bottomRowCache.get(packRegion(rx, rz));
                    int[][] rows = latest == null ? mergedRows : latest;
                    toWrite = java.util.Arrays.copyOf(rows, rows.length);
                }
                cache.writeBottomRows(rx, rz, toWrite);
            }); // submit 可能背压等待,必须位于 manager 锁外。
        }
    }

    private void restoreProgress(ServerLevel level, RenderJob job) {
        restoreProgress(progressFile(level), job);
    }

    private void restoreProgress(Path file, RenderJob job) {
        if (progressInvalidated || file == null || !Files.isRegularFile(file)) {
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
        ProgressSnapshot progress = new ProgressSnapshot(job.type, job.completedChunkCount(), job.regionIndex,
                job.localChunkIndex, job.processedChunks, job.processedRegions, job.totalChunks(), job.totalRegions());
        stateWriter.request(file, () -> {
            JsonObject json = new JsonObject();
            json.addProperty("type", progress.type());
            json.addProperty("chunkIndex", progress.chunkIndex());
            json.addProperty("regionIndex", progress.regionIndex());
            json.addProperty("localChunkIndex", progress.localChunkIndex());
            json.addProperty("processedChunks", progress.processedChunks());
            json.addProperty("processedRegions", progress.processedRegions());
            json.addProperty("totalChunks", progress.totalChunks());
            json.addProperty("totalRegions", progress.totalRegions());
            MarketWebMapStateWriter.writeJson(file, json);
        });
    }

    private record ProgressSnapshot(String type, int chunkIndex, int regionIndex, int localChunkIndex,
                                    int processedChunks, int processedRegions, int totalChunks, int totalRegions) { }

    private void clearProgress(ServerLevel level) {
        // Invalidate synchronously: deletion is queued and a new job may start before it runs.
        progressInvalidated = true;
        progressCheckpointPending = false;
        Path file = progressFile(level);
        if (file != null) {
            stateWriter.delete(file);
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
        int budget = Math.min(Math.min(available, 32), Math.max(1, configuredInt(ModConfig::marketWebDirtyRegionChunksPerInterval, 512)));
        if (!hasSamplingTime()) {
            return;
        }
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = dirtyRegions.expandChunks(budget);
        if (chunks.isEmpty()) {
            return;
        }
        for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
            dirtyChunks.markDirty(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
        }
        backgroundCadence.markDirty();
    }


    /** The fullreader gates its own state; ordinary visible work keeps a short partial threshold. */
    private int currentPartialFlushChunks() {
        // The fullreader has its own complete-only state. Ordinary visible work can flush independently.
        return effectivePartialRegionFlushChunks(MarketWebMapTileQuality.SERVER_REGION_SCAN,
                configuredInt(ModConfig::marketWebPartialRegionFlushChunks, 32));
    }

    /** fullrender 读取器:某 region 写盘落地后调用(imageIO 线程,故 synchronized)。若它正是读取器在等的 region,置位放行。 */
    private synchronized void markReaderRegionWritten(long regionKey, RegionState completedState) {
        diagWriteCallbacks.incrementAndGet();
        if (readerAwaitWriteValid && regionKey == readerAwaitWriteKey && readerState == completedState) {
            readerRegionWritten = true;
            diagWriteHits.incrementAndGet();
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
        private long firstPendingTick;
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
            snapshots[index] = null;
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
        private final int totalChunkCount;
        private final int[] completedChunkPrefix;
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
            this.completedChunkPrefix = new int[this.regions.size() + 1];
            for (int i = 0; i < this.regions.size(); i++) {
                this.completedChunkPrefix[i + 1] = this.completedChunkPrefix[i] + this.regions.get(i).localChunks().size();
            }
            this.totalChunkCount = this.regions.isEmpty() ? this.chunks.size() : this.completedChunkPrefix[this.regions.size()];
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

        /** fullrender 专用读取器:游标直接跳到下一个 region 起始(regionIndex++,localChunkIndex=0)。 */
        private void advanceToNextRegion() {
            if (fullRender()) {
                regionIndex++;
                localChunkIndex = 0;
            }
        }

        private boolean done() {
            if (fullRender()) {
                return regionIndex >= regions.size();
            }
            return chunkIndex >= chunks.size();
        }

        private int totalChunks() {
            return totalChunkCount;
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
            return completedChunkPrefix[Math.min(Math.max(0, endRegionIndex), regions.size())];
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
