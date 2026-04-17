package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapTaskServiceTest {
    @Test
    void newerClaimMapRequestReplacesOlderResultForSameScreen() {
        ClaimMapTaskService service = new ClaimMapTaskService(Runnable::run, Runnable::run);
        List<ClaimPreviewMapState> applied = new ArrayList<>();

        ClaimMapTaskService.TaskHandle<ClaimPreviewMapState> first = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-claims", "player-a"),
                () -> ClaimPreviewMapState.loading(1L, 8, 100, 200),
                applied::add
        );
        ClaimMapTaskService.TaskHandle<ClaimPreviewMapState> second = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-claims", "player-a"),
                () -> ClaimPreviewMapState.ready(2L, 8, 100, 200, List.of(0xFF112233)),
                applied::add
        );

        first.completeForTest();
        second.completeForTest();

        assertEquals(List.of(ClaimPreviewMapState.ready(2L, 8, 100, 200, List.of(0xFF112233))), applied);
    }

    @Test
    void mapStateFactoriesNormalizeNullColorsAndKeepRevision() {
        ClaimPreviewMapState state = ClaimPreviewMapState.ready(7L, 6, 12, 24, null);

        assertEquals(7L, state.revision());
        assertEquals(0, state.colors().size());
        assertTrue(state.ready());
    }
}
