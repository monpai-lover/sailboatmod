package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTextInputScreenTest {
    @Test
    void submitSanitizesRenameValue() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();

        RoadPlannerTextInputScreen.SubmitResult result = RoadPlannerTextInputScreen.submitForTest(routeId, edgeId, " 港口大道 ");

        assertEquals(routeId, result.routeId());
        assertEquals(edgeId, result.edgeId());
        assertEquals("港口大道", result.value());
    }

    @Test
    void blankNamesAreRejected() {
        assertTrue(RoadPlannerTextInputScreen.submitForTest(UUID.randomUUID(), UUID.randomUUID(), "   ").rejected());
    }
}
