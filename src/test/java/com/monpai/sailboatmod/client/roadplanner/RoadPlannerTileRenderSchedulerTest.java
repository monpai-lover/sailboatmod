package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileRenderSchedulerTest {
    @Test
    void schedulerDoesNotSubmitSameChunkTwice() {
        RoadPlannerTileRenderScheduler scheduler = new RoadPlannerTileRenderScheduler();
        ChunkPos chunk = new ChunkPos(1, 2);

        assertTrue(scheduler.markSubmitted(chunk));
        assertTrue(scheduler.alreadySubmitted(chunk));
        assertFalse(scheduler.markSubmitted(chunk));
    }

    @Test
    void closedSchedulerRejectsAndClearsSubmissions() {
        RoadPlannerTileRenderScheduler scheduler = new RoadPlannerTileRenderScheduler();
        ChunkPos chunk = new ChunkPos(3, 4);

        assertTrue(scheduler.markSubmitted(chunk));
        scheduler.close();

        assertFalse(scheduler.alreadySubmitted(chunk));
        assertFalse(scheduler.markSubmitted(new ChunkPos(5, 6)));
    }
}
