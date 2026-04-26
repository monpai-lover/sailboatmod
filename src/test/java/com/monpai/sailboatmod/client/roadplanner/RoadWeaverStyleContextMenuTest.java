package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadWeaverStyleContextMenuTest {
    @Test
    void selectedRoadContextMenuUsesRoadweaverLayoutRules() {
        UUID edgeId = UUID.randomUUID();
        RoadWeaverStyleContextMenuModel menu = RoadWeaverStyleContextMenuModel.forRoadEdge(edgeId, 120, 80)
                .withMousePosition(128, 93);

        assertEquals(edgeId, menu.roadEdgeId());
        assertEquals(6, menu.padding());
        assertEquals(16, menu.itemHeight());
        assertEquals(8, menu.separatorHeight());
        assertTrue(menu.bounds().contains(120, 80));
        assertEquals(0, menu.hoverIndex());
        assertEquals(RoadPlannerContextMenuAction.RENAME_ROAD, menu.hoveredAction().orElseThrow());
        assertTrue(menu.items().stream().anyMatch(RoadWeaverStyleContextMenuItem::separator));
    }

    @Test
    void disabledItemAndOutsideClickOnlyCloseMenu() {
        UUID edgeId = UUID.randomUUID();
        RoadWeaverStyleContextMenuModel menu = RoadWeaverStyleContextMenuModel.forRoadEdge(edgeId, 10, 10)
                .withActionEnabled(RoadPlannerContextMenuAction.DEMOLISH_BRANCH, false)
                .withMousePosition(18, 77);

        RoadWeaverStyleContextMenuClick disabledClick = menu.click(18, 77, 0);
        RoadWeaverStyleContextMenuClick outsideClick = menu.click(320, 240, 0);

        assertTrue(disabledClick.consumed());
        assertFalse(disabledClick.action().isPresent());
        assertTrue(outsideClick.closeMenu());
    }

    @Test
    void renameInputSanitizesAndBuildsSubmitIntent() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();

        RoadPlannerTextInputModel input = RoadPlannerTextInputModel.renameRoad(routeId, edgeId, "  旧名字  ")
                .withValue("  港口大道  ");

        assertEquals("旧名字", input.initialValue());
        assertEquals("港口大道", input.value());
        assertEquals(new RoadPlannerTextInputModel.SubmitIntent(routeId, edgeId, "港口大道"), input.submit().orElseThrow());
    }
}
