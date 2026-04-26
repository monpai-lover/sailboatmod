package com.monpai.sailboatmod.client.roadplanner;

import java.util.List;
import java.util.UUID;

public record RoadPlannerContextMenuModel(UUID roadEdgeId, List<RoadPlannerContextMenuAction> actions) {
    public RoadPlannerContextMenuModel {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static RoadPlannerContextMenuModel forSelectedRoadEdge(UUID roadEdgeId) {
        return new RoadPlannerContextMenuModel(roadEdgeId, List.of(
                RoadPlannerContextMenuAction.RENAME_ROAD,
                RoadPlannerContextMenuAction.EDIT_NODES,
                RoadPlannerContextMenuAction.DEMOLISH_EDGE,
                RoadPlannerContextMenuAction.DEMOLISH_BRANCH,
                RoadPlannerContextMenuAction.CONNECT_TOWN,
                RoadPlannerContextMenuAction.VIEW_LEDGER
        ));
    }

    public boolean hasAction(RoadPlannerContextMenuAction action) {
        return actions.contains(action);
    }
}
