package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void cancelledSupplierCompletesQuietlyWithoutApplyingResult() {
        RoadPlanningTaskService service = new RoadPlanningTaskService(Runnable::run, Runnable::run);
        List<String> applied = new ArrayList<>();

        CompletableFuture<String> future = service.submitLatest(
                new RoadPlanningTaskService.TaskKey("manual-preview", "player-c"),
                () -> {
                    throw new RoadPlanningTaskService.PlanningCancelledException();
                },
                applied::add
        );

        String result = assertDoesNotThrow(future::join);
        assertNull(result);
        assertTrue(applied.isEmpty());
    }
}
