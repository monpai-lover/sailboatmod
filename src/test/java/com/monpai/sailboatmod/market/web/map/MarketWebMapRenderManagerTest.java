package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

class MarketWebMapRenderManagerTest {
    @Test
    void freshWatchEventsCannotTruncateAnAlreadyAcceptedRecoveryBacklog() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            Field dirtyField = MarketWebMapRenderManager.class.getDeclaredField("dirtyRegions");
            dirtyField.setAccessible(true);
            MarketWebMapDirtyRegionQueue regions = (MarketWebMapDirtyRegionQueue) dirtyField.get(manager);
            for (int i = 0; i < 4097; i++) { regions.markDirty("minecraft:overworld", i, 0); }
            assertEquals(0, manager.markRegionDirty(9999, 0), "normal discovery must wait for recovery backlog capacity");
            assertEquals(4097, regions.size(), "accepted recovery work must survive the first event after restart");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void stoppingRewindsPartiallyExpandedRegionWithUnwrittenSnapshotWork() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            Field dirtyField = MarketWebMapRenderManager.class.getDeclaredField("dirtyRegions");
            dirtyField.setAccessible(true);
            MarketWebMapDirtyRegionQueue regions = (MarketWebMapDirtyRegionQueue) dirtyField.get(manager);
            regions.markDirty("minecraft:overworld", 0, 0);
            regions.expandChunks(32);
            Method newState = MarketWebMapRenderManager.class.getDeclaredMethod("newRegionState", String.class, int.class, int.class);
            newState.setAccessible(true);
            Field statesField = MarketWebMapRenderManager.class.getDeclaredField("regionStates");
            statesField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Long, Object> states = (Map<Long, Object>) statesField.get(manager);
            states.put(0L, newState.invoke(manager, "minecraft:overworld", 0, 0));
            manager.savePendingWork(null, null);
            assertEquals(0, regions.inProgressLocalChunk(), "unwritten early chunks must be retried after interrupted shutdown");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void stoppingPreservesUnfinishedRegionRepairCursor() throws Exception {
        MarketWebMapRenderService service = new MarketWebMapRenderService(new MarketWebMapRenderQueue(8));
        Field managerField = MarketWebMapRenderService.class.getDeclaredField("renderManager");
        managerField.setAccessible(true);
        MarketWebMapRenderManager manager = (MarketWebMapRenderManager) managerField.get(service);
        try {
            Method add = MarketWebMapRenderService.class.getDeclaredMethod("addRegionCursor", int.class, int.class);
            add.setAccessible(true);
            assertEquals(true, add.invoke(service, 2, 3));
            service.stopRegionWatcher(null);
            assertEquals(1, manager.status(0).dirtyRegions(), "a repair cursor must become recoverable dirty work before clearing");
        } finally {
            manager.shutdown();
            ((MarketWebMapRenderManager) managerField.get(service)).shutdown();
        }
    }

    @Test
    void stoppingPreservesPausedWatcherBacklogAsRegionsBeforePersistence() throws Exception {
        MarketWebMapRenderService service = new MarketWebMapRenderService(new MarketWebMapRenderQueue(8));
        Field watcherField = MarketWebMapRenderService.class.getDeclaredField("regionWatcher");
        watcherField.setAccessible(true);
        Field managerField = MarketWebMapRenderService.class.getDeclaredField("renderManager");
        managerField.setAccessible(true);
        MarketWebMapRegionWatcher watcher = (MarketWebMapRegionWatcher) watcherField.get(service);
        MarketWebMapRenderManager manager = (MarketWebMapRenderManager) managerField.get(service);
        try {
            assertTrue(manager.pause());
            for (int i = 0; i < 512; i++) {
                watcher.addDirtyRegion(MarketWebMapRegionWatcher.packRegion(i, 0));
            }
            service.stopRegionWatcher(null);
            assertEquals(512, manager.status(0).dirtyRegions(), "paused regions must not be truncated to a chunk-based limit before saving");
        } finally {
            manager.shutdown();
            ((MarketWebMapRenderManager) managerField.get(service)).shutdown();
        }
    }

    @Test
    void cancelThenImmediateRestartIgnoresCheckpointAwaitingAsynchronousDeletion(@TempDir Path directory) throws Exception {
        Path progress = directory.resolve("render_progress.json");
        Files.writeString(progress, "{\"type\":\"fullrender\",\"regionIndex\":1,\"localChunkIndex\":0}");
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            Class<?> jobType = Class.forName(MarketWebMapRenderManager.class.getName() + "$RenderJob");
            Method full = jobType.getDeclaredMethod("full", List.class);
            full.setAccessible(true);
            List<MarketWebMapRegionScanService.RegionFile> regions = List.of(
                    new MarketWebMapRegionScanService.RegionFile(0, 0, List.of(0)),
                    new MarketWebMapRegionScanService.RegionFile(1, 0, List.of(0)));
            Method restore;
            try {
                restore = MarketWebMapRenderManager.class.getDeclaredMethod("restoreProgress", Path.class, jobType);
            } catch (NoSuchMethodException exception) {
                fail("Checkpoint restoration must support testing cancellation while a disk delete is pending");
                return;
            }
            restore.setAccessible(true);
            Field regionIndex = jobType.getDeclaredField("regionIndex");
            regionIndex.setAccessible(true);
            Object oldJob = full.invoke(null, regions);
            restore.invoke(manager, progress, oldJob);
            assertEquals(1, regionIndex.getInt(oldJob), "initial launch can resume a persisted checkpoint");
            Field activeJob = MarketWebMapRenderManager.class.getDeclaredField("activeJob");
            activeJob.setAccessible(true);
            activeJob.set(manager, oldJob);
            assertTrue(manager.cancel(null));
            Object nextJob = full.invoke(null, regions);
            restore.invoke(manager, progress, nextJob);
            assertEquals(0, regionIndex.getInt(nextJob), "a canceled checkpoint must not skip regions in a fresh job");
            assertTrue(Files.isRegularFile(progress), "the regression does not depend on an asynchronous delete having finished");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void pausingIdleManagerRetainsPendingQueueAndReportsPaused() {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(8);
        try {
            queue.enqueue("minecraft:overworld", 1, 2, 1L);
            assertTrue(manager.pause(), "idle automatic updates must be pausable too");
            manager.tickBackground(null, queue, 999_999L);
            assertTrue(manager.status(queue.size()).paused());
            assertEquals(1, queue.size());
            assertTrue(manager.resume());
            assertFalse(manager.status(queue.size()).paused());
        } finally {
            manager.shutdown();
        }
    }


    // 部分刷新阈值现按 config 值生效(异步离线读模式下,死等满 1024 会让含 PENDING chunk 的 region
    // 永不写图 → 底图全黑)。两类 SERVER_* quality 用同一阈值;writeDirty 的 mergeTouchedPixels 增量合并
    // 保证渐进显示不画脏条纹,故不再需要"等满整个 region"。
    @Test
    void partialRegionFlushUsesConfiguredThresholdForRegionScan() {
        assertEquals(
                32,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_REGION_SCAN,
                        32));
    }

    @Test
    void partialRegionFlushUsesConfiguredThresholdForLoadedChunks() {
        assertEquals(
                32,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                        32));
    }

    @Test
    void partialRegionFlushFallsBackWhenConfiguredValueNonPositive() {
        assertEquals(
                64,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_REGION_SCAN,
                        0));
    }

    @Test
    void partialRegionFlushClampsToFullRegion() {
        assertEquals(
                MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS * MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_REGION_SCAN,
                        99999));
    }

    @Test
    void areaRenderConvertsBlockBoundsToInclusiveChunkBounds() {
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapRenderManager.areaChunksForTest(15, -17, 32, 0);

        assertIterableEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, -2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, -2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, 0)
        ), chunks);
    }

    @Test
    void areaRenderNormalizesReversedBlockBounds() {
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapRenderManager.areaChunksForTest(32, 0, 15, -17);

        assertEquals(9, chunks.size());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -2), chunks.get(0));
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, 0), chunks.get(chunks.size() - 1));
    }
}
