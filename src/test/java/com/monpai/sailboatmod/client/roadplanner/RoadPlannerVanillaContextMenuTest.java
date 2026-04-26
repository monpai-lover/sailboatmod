package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerVanillaContextMenuTest {
    @Test
    void menuClampsToScreenAndClicksHoveredAction() {
        RoadPlannerVanillaContextMenu menu = RoadPlannerVanillaContextMenu.forRoadEdge(UUID.randomUUID());
        menu.open(840, 470);
        menu.layout(900, 500, label -> label.length() * 6);

        assertTrue(menu.bounds().right() <= 896);
        assertTrue(menu.bounds().bottom() <= 496);

        menu.updateHover(menu.bounds().x() + 8, menu.bounds().y() + 8);

        assertEquals(RoadPlannerContextMenuAction.RENAME_ROAD, menu.hoveredAction().orElseThrow());
        assertTrue(menu.click(menu.bounds().x() + 8, menu.bounds().y() + 8, 0).action().isPresent());
    }

    @Test
    void disabledItemsDoNotTriggerActions() {
        RoadPlannerVanillaContextMenu menu = RoadPlannerVanillaContextMenu.forRoadEdge(UUID.randomUUID());
        menu.setEnabled(RoadPlannerContextMenuAction.DEMOLISH_BRANCH, false);
        menu.open(100, 100);
        menu.layout(900, 500, label -> label.length() * 6);

        int branchY = menu.bounds().y() + RoadPlannerVanillaContextMenu.PADDING
                + RoadPlannerVanillaContextMenu.ITEM_HEIGHT * 3
                + RoadPlannerVanillaContextMenu.SEPARATOR_HEIGHT
                + 4;
        menu.updateHover(menu.bounds().x() + 8, branchY);

        assertTrue(menu.click(menu.bounds().x() + 8, branchY, 0).action().isEmpty());
    }
}
