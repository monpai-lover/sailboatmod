package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerForceRenderQueueTest {
    @Test
    void corridorTaskReportsProgressPercentage() {
        RoadPlannerForceRenderQueue queue = new RoadPlannerForceRenderQueue();
        queue.enqueueCorridor(BlockPos.ZERO, new BlockPos(160, 64, 0), 64, "\u5730\u56fe\u9884\u6e32\u67d3");

        RoadPlannerForceRenderProgress start = queue.progress();
        queue.processChunks(2);
        RoadPlannerForceRenderProgress progress = queue.progress();

        assertEquals("\u5730\u56fe\u9884\u6e32\u67d3", progress.label());
        assertTrue(start.totalChunks() > 0);
        assertTrue(progress.completedChunks() > start.completedChunks());
        assertTrue(progress.percent() > 0);
    }

    @Test
    void selectionTaskQueuesSelectedChunks() {
        RoadPlannerForceRenderQueue queue = new RoadPlannerForceRenderQueue();
        queue.enqueueSelection(BlockPos.ZERO, new BlockPos(48, 64, 48), "\u9009\u533a\u6e32\u67d3");

        assertEquals("\u9009\u533a\u6e32\u67d3", queue.progress().label());
        assertEquals(16, queue.progress().totalChunks());
    }

    @Test
    void selectionTaskAppendsNewRegionsInsteadOfReplacingPendingWork() {
        RoadPlannerForceRenderQueue queue = new RoadPlannerForceRenderQueue();
        queue.enqueueSelection(BlockPos.ZERO, new BlockPos(48, 64, 48), "\u9009\u533a\u6e32\u67d3");
        queue.enqueueSelection(new BlockPos(64, 64, 0), new BlockPos(112, 64, 48), "\u9009\u533a\u6e32\u67d3");

        assertEquals(32, queue.progress().totalChunks());
    }

    @Test
    void routeTaskQueuesChunksAlongEveryRouteSegment() {
        RoadPlannerForceRenderQueue queue = new RoadPlannerForceRenderQueue();
        queue.enqueueRoute(
                List.of(BlockPos.ZERO, new BlockPos(0, 64, 160), new BlockPos(160, 64, 160)),
                0,
                "\u81ea\u52a8\u8def\u7ebf\u7f13\u5b58");
        List<ChunkPos> chunks = new ArrayList<>();

        queue.processChunks(64, chunks::add);

        assertTrue(chunks.contains(new ChunkPos(0, 5)), "north/south leg chunk should be queued");
        assertTrue(chunks.contains(new ChunkPos(5, 10)), "east/west leg chunk should be queued");
        assertTrue(chunks.contains(new ChunkPos(10, 10)), "destination chunk should be queued");
    }
}
