package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerRetainedScreenTest {
    @Test
    void returningFromAChildDialogKeepsTileSchedulingAvailable() throws Exception {
        var screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.removed();
        screen.init();
        var schedulerField = RoadPlannerScreen.class.getDeclaredField("tileRenderScheduler");
        schedulerField.setAccessible(true);
        var scheduler = (RoadPlannerTileRenderScheduler) schedulerField.get(screen);
        assertTrue(scheduler.markSubmitted(new ChunkPos(100, 100)), "returning from a child dialog must not leave the retained planner's scheduler closed");
        scheduler.close();
    }
}
