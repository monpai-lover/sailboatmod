package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerClientStateTest {
    @Test
    void stateTracksSessionToolRegionAndWidth() {
        UUID sessionId = UUID.randomUUID();

        RoadPlannerClientState state = RoadPlannerClientState.open(sessionId)
                .withActiveTool(RoadToolType.BRIDGE)
                .withActiveRegionIndex(2)
                .withSelectedWidth(7);

        assertEquals(sessionId, state.sessionId());
        assertEquals(RoadToolType.BRIDGE, state.activeTool());
        assertEquals(2, state.activeRegionIndex());
        assertEquals(7, state.selectedWidth());
        assertTrue(state.isOpen());
        assertThrows(IllegalArgumentException.class, () -> state.withSelectedWidth(4));
    }

    @Test
    void selectToolEnablesRoadEditingActions() {
        RoadPlannerToolbarModel toolbar = RoadPlannerToolbarModel.defaults().select(RoadToolType.SELECT);
        RoadPlannerContextMenuModel menu = RoadPlannerContextMenuModel.forSelectedRoadEdge(UUID.randomUUID());

        assertEquals(RoadToolType.SELECT, toolbar.activeTool());
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.RENAME_ROAD));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.SET_ROAD_TYPE));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.SET_BRIDGE_TYPE));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.SET_TUNNEL_TYPE));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.DEMOLISH_EDGE));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.DEMOLISH_BRANCH));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.CONNECT_TOWN));
        assertTrue(menu.hasAction(RoadPlannerContextMenuAction.VIEW_LEDGER));
        assertFalse(menu.actions().isEmpty());
    }
}
