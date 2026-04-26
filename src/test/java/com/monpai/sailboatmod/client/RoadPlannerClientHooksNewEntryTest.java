package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreen;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerClientHooksNewEntryTest {
    @Test
    void openPlannerEntryCreatesNewRoadPlannerScreen() {
        UUID sessionId = UUID.randomUUID();

        assertEquals(RoadPlannerScreen.class, RoadPlannerClientHooks.screenClassForNewPlannerEntry(sessionId));
    }
}
