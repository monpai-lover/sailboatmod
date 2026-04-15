package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlanningTaskServiceTest {
    @Test
    void newerRequestReplacesOlderResultForSameOwnerKey() {
        RoadPlanningTaskService service = new RoadPlanningTaskService(Runnable::run, Runnable::run);
        List<String> applied = new ArrayList<>();

        RoadPlanningTaskService.TaskHandle<String> first = service.submitForTest(
                new RoadPlanningTaskService.TaskKey("manual-preview", "player-a"),
                () -> "old",
                applied::add
        );
        RoadPlanningTaskService.TaskHandle<String> second = service.submitForTest(
                new RoadPlanningTaskService.TaskKey("manual-preview", "player-a"),
                () -> "new",
                applied::add
        );

        first.completeForTest();
        second.completeForTest();

        assertEquals(List.of("new"), applied);
    }

    @Test
    void staleEpochResultIsIgnored() {
        RoadPlanningTaskService service = new RoadPlanningTaskService(Runnable::run, Runnable::run);
        List<String> applied = new ArrayList<>();

        RoadPlanningTaskService.TaskHandle<String> handle = service.submitForTest(
                new RoadPlanningTaskService.TaskKey("manual-preview", "player-b"),
                () -> "value",
                applied::add
        );

        service.invalidateAllForTest();
        handle.completeForTest();

        assertTrue(applied.isEmpty());
    }
}
