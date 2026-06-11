package com.monpai.sailboatmod.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidePanelScreenLayoutTest {
    @Test
    void dockScreenImageWidthIncludesRightPanelForOverlayMods() {
        assertEquals(
                DockScreen.mainPanelWidthForTest() + DockScreen.rightPanelGapForTest() + DockScreen.rightPanelWidthForTest(),
                DockScreen.combinedImageWidthForTest()
        );
        assertTrue(DockScreen.rightPanelOffsetForTest() < DockScreen.combinedImageWidthForTest());
    }

    @Test
    void postStationScreenImageWidthIncludesRightPanelForOverlayMods() {
        assertEquals(
                PostStationScreen.mainPanelWidthForTest() + PostStationScreen.rightPanelGapForTest() + PostStationScreen.rightPanelWidthForTest(),
                PostStationScreen.combinedImageWidthForTest()
        );
        assertTrue(PostStationScreen.rightPanelOffsetForTest() < PostStationScreen.combinedImageWidthForTest());
    }
}
