package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

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
}
