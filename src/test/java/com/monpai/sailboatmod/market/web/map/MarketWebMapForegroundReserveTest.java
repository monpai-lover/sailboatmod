package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.ModConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapForegroundReserveTest {
    private static final String WORLD = "minecraft:overworld";

    @Test
    void qualityUpgradeKeepsAll64ReservedCoordinatesEligible() {
        MarketWebMapRenderQueue queue = saturated();
        for (int x = 1; x <= 64; x++) assertTrue(queue.enqueue(WORLD, -x, 0, 2L, MarketWebMapTileQuality.SERVER_REGION_SCAN));
        var foreground = queue.pollTrackedLoaded(64, 3L);
        assertEquals(64, foreground.size(), "reserve ownership must survive a quality upgrade");
        assertEquals(4160, queue.snapshot().size());
        assertFalse(queue.enqueue(WORLD, -65, 0, 3L), "active claims still own all reserved capacity");
        foreground.forEach(queue::acknowledge);
        assertEquals(4096, queue.snapshot().size());
        assertTrue(queue.enqueue(WORLD, -65, 0, 4L));
    }

    @Test
    void unloadedReserveTransfersAcceptedWorkBeforeFreeingSlotsAndStopPersistsIt(@TempDir Path directory) throws Exception {
        MarketWebMapRenderQueue queue = saturated();
        MarketWebMapRenderService service = new MarketWebMapRenderService(queue);
        MarketWebMapRenderManager manager = (MarketWebMapRenderManager) field(service, "renderManager");
        try {
            for (int tick = 1; tick <= 2; tick++) assertNull(selectUnloaded(service, manager, tick));
            MarketWebMapDirtyChunkQueue dirty = (MarketWebMapDirtyChunkQueue) field(manager, "dirtyChunks");
            assertEquals(64, dirty.size(), "unloaded accepted work must enter recoverable backlog before reserve release");
            assertEquals(4096, queue.snapshot().size());
            assertTrue(queue.enqueue(WORLD, -65, 0, 5L), "new visible work can enter the freed bounded reserve");
            manager.savePendingWork(null, queue);
            MarketWebMapStateWriter writer = (MarketWebMapStateWriter) field(manager, "stateWriter");
            assertTrue(writer.requestDirtyQueues(manager, dirty, directory.resolve("chunks.json"),
                    (MarketWebMapDirtyRegionQueue) field(manager, "dirtyRegions"), directory.resolve("regions.json")));
            assertTrue(writer.flush(3000));
            MarketWebMapDirtyChunkQueue restored = new MarketWebMapDirtyChunkQueue();
            assertEquals(4161, restored.load(directory.resolve("chunks.json")));
            assertTrue(restored.copy().contains(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(WORLD, -1, 0)));
            assertTrue(restored.copy().contains(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(WORLD, -65, 0)));
        } finally { close(service, manager); }
    }

    @Test
    void fullDirtyBacklogLeavesUnloadedReservationAcceptedForBackpressure() throws Exception {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(1, 1);
        queue.enqueue(WORLD, 0, 0, 0L, MarketWebMapTileQuality.SERVER_REGION_SCAN);
        queue.enqueue(WORLD, -1, 0, 0L);
        MarketWebMapRenderService service = new MarketWebMapRenderService(queue);
        MarketWebMapRenderManager manager = (MarketWebMapRenderManager) field(service, "renderManager");
        try {
            int limit;
            try { limit = ModConfig.marketWebMaxDirtyChunks(); }
            catch (IllegalStateException exception) { limit = 200000; }
            MarketWebMapDirtyChunkQueue dirty = (MarketWebMapDirtyChunkQueue) field(manager, "dirtyChunks");
            for (int i = 0; i < limit; i++) dirty.markDirty(WORLD, i, 1000);
            assertNull(selectUnloaded(service, manager, 1L));
            assertEquals(limit, dirty.size());
            assertEquals(2, queue.snapshot().size(), "capacity failure cannot discard an accepted task");
            assertFalse(queue.enqueue(WORLD, -2, 0, 2L));
        } finally { close(service, manager); }
    }

    private static MarketWebMapRenderQueue saturated() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096, 64);
        for (int x = 0; x < 4096; x++) assertTrue(queue.enqueue(WORLD, x, 0, 0L, MarketWebMapTileQuality.SERVER_REGION_SCAN));
        for (int x = 1; x <= 64; x++) assertTrue(queue.enqueue(WORLD, -x, 0, 1L));
        return queue;
    }

    private static Object selectUnloaded(MarketWebMapRenderService service, MarketWebMapRenderManager manager, long tick) throws Exception {
        ((MarketWebMapTickBudget) field(manager, "tickBudget")).beginTick(tick, 30_000_000_000L, 8, 4);
        Method poll = service.getClass().getDeclaredMethod("pollForegroundTask", long.class, MarketWebMapRenderService.ChunkLoadedPredicate.class, int[].class);
        poll.setAccessible(true);
        return poll.invoke(service, tick, (MarketWebMapRenderService.ChunkLoadedPredicate) (x, z) -> false, new int[]{0});
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static void close(MarketWebMapRenderService service, MarketWebMapRenderManager manager) throws Exception {
        service.stopRegionWatcher(null);
        manager.shutdown();
        ((MarketWebMapRenderManager) field(service, "renderManager")).shutdown();
    }
}
