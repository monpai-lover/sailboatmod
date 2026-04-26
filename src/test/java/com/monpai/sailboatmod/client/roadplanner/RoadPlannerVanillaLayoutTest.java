package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerVanillaLayoutTest {
    @Test
    void layoutFitsStandardScreen() {
        RoadPlannerVanillaLayout layout = RoadPlannerVanillaLayout.compute(1280, 720);

        assertTrue(layout.toolbar().width() >= 120);
        assertTrue(layout.map().width() >= 420);
        assertTrue(layout.sidebar().width() >= 220);
        assertTrue(layout.toolbar().x() >= 8);
        assertTrue(layout.map().x() > layout.toolbar().right());
        assertTrue(layout.sidebar().x() > layout.map().right());
        assertEquals(7, layout.bottomButtons().size());
        assertTrue(layout.map().bottom() <= 704);
    }

    @Test
    void layoutFitsSmallGuiScaleScreen() {
        RoadPlannerVanillaLayout layout = RoadPlannerVanillaLayout.compute(854, 480);

        assertTrue(layout.map().width() >= 360);
        assertTrue(layout.map().height() >= 260);
        assertTrue(layout.sidebar().right() <= 846);
        assertTrue(layout.bottomButtons().stream().allMatch(rect -> rect.bottom() <= 472));
    }
}
