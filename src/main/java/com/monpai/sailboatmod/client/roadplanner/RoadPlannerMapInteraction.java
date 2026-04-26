package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphHitTester;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;

import java.util.Optional;
import java.util.UUID;

public class RoadPlannerMapInteraction {
    private final RoadNetworkGraph graph;

    public RoadPlannerMapInteraction(RoadNetworkGraph graph) {
        this.graph = graph;
    }

    public RoadPlannerMapInteractionResult rightClickRoadLine(RoadPlannerClientState state,
                                                             double worldX,
                                                             double worldZ,
                                                             int mouseX,
                                                             int mouseY,
                                                             double hitThresholdBlocks) {
        Optional<RoadGraphEdge> hit = new RoadGraphHitTester(graph).nearestEdge(worldX, worldZ, hitThresholdBlocks);
        if (hit.isEmpty()) {
            return RoadPlannerMapInteractionResult.unchanged(state);
        }
        RoadGraphEdge edge = hit.get();
        RoadPlannerClientState selected = state.withSelectedRoadEdge(edge.edgeId());
        return new RoadPlannerMapInteractionResult(
                selected,
                RoadWeaverStyleContextMenuModel.forRoadEdge(edge.edgeId(), mouseX, mouseY),
                null,
                null
        );
    }

    public static RoadPlannerMapInteractionResult handleMenuClick(UUID routeId,
                                                                  String currentRoadName,
                                                                  RoadWeaverStyleContextMenuModel menu,
                                                                  double mouseX,
                                                                  double mouseY,
                                                                  int button) {
        RoadPlannerClientState syntheticState = RoadPlannerClientState.open(routeId).withSelectedRoadEdge(menu.roadEdgeId());
        RoadWeaverStyleContextMenuClick click = menu.click(mouseX, mouseY, button);
        Optional<RoadPlannerContextMenuAction> action = click.action();
        RoadPlannerTextInputModel textInput = action.filter(selected -> selected == RoadPlannerContextMenuAction.RENAME_ROAD)
                .map(ignored -> RoadPlannerTextInputModel.renameRoad(routeId, menu.roadEdgeId(), currentRoadName))
                .orElse(null);
        return new RoadPlannerMapInteractionResult(syntheticState, null, action.orElse(null), textInput);
    }
}
