package com.monpai.sailboatmod.client.roadplanner;

import gg.essential.elementa.WindowScreen;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadPlannerScreenBehaviorTest {
    @Test
    void screenIsVanillaAndHandlesEscapeLayers() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);

        assertFalse(WindowScreen.class.isAssignableFrom(screen.getClass()));
        assertEquals(RoadPlannerScreen.EscapeResult.CLOSE_CONTEXT_MENU, screen.handleEscapeForTest(true, false));
        assertEquals(RoadPlannerScreen.EscapeResult.CLOSE_SCREEN, screen.handleEscapeForTest(false, false));
        assertEquals(7, screen.layoutForTest().bottomButtons().size());
    }
}
