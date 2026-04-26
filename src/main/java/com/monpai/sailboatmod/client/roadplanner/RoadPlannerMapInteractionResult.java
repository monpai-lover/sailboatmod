package com.monpai.sailboatmod.client.roadplanner;

import java.util.Optional;

public record RoadPlannerMapInteractionResult(RoadPlannerClientState state,
                                             RoadWeaverStyleContextMenuModel openedContextMenu,
                                             RoadPlannerContextMenuAction selectedAction,
                                             RoadPlannerTextInputModel openedTextInput) {
    public RoadPlannerMapInteractionResult {
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
    }

    public static RoadPlannerMapInteractionResult unchanged(RoadPlannerClientState state) {
        return new RoadPlannerMapInteractionResult(state, null, null, null);
    }

    public Optional<RoadWeaverStyleContextMenuModel> contextMenu() {
        return Optional.ofNullable(openedContextMenu);
    }

    public Optional<RoadPlannerContextMenuAction> action() {
        return Optional.ofNullable(selectedAction);
    }

    public Optional<RoadPlannerTextInputModel> textInput() {
        return Optional.ofNullable(openedTextInput);
    }
}
