package com.monpai.sailboatmod.client.roadplanner;

import java.util.Optional;

public record RoadWeaverStyleContextMenuClick(boolean consumed,
                                             boolean closeMenu,
                                             RoadPlannerContextMenuAction selectedAction) {
    public Optional<RoadPlannerContextMenuAction> action() {
        return Optional.ofNullable(selectedAction);
    }
}
