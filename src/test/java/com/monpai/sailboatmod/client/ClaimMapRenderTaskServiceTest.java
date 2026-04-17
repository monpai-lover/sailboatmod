package com.monpai.sailboatmod.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimMapRenderTaskServiceTest {
    @Test
    void newerRenderRequestReplacesOlderResultForSameScreen() {
        ClaimMapRenderTaskService service = new ClaimMapRenderTaskService(Runnable::run, Runnable::run);
        List<String> applied = new ArrayList<>();

        ClaimMapRenderTaskService.TaskHandle<String> first = service.submitForTest(
                new ClaimMapRenderTaskService.TaskKey("town-claims", "town-a"),
                () -> "old",
                applied::add
        );
        ClaimMapRenderTaskService.TaskHandle<String> second = service.submitForTest(
                new ClaimMapRenderTaskService.TaskKey("town-claims", "town-a"),
                () -> "new",
                applied::add
        );

        first.completeForTest();
        second.completeForTest();

        assertEquals(List.of("new"), applied);
    }
}
