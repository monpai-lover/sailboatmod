package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;

public record RoadPlannerMergeSelection(String roadId,
                                        int pathIndex,
                                        BlockPos anchorPos,
                                        RoadPlannerMergeScope scope) {
    public RoadPlannerMergeSelection {
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(java.util.Locale.ROOT);
        pathIndex = Math.max(-1, pathIndex);
        anchorPos = anchorPos == null ? BlockPos.ZERO : anchorPos.immutable();
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
    }

    public static RoadPlannerMergeSelection none() {
        return new RoadPlannerMergeSelection("", -1, BlockPos.ZERO, RoadPlannerMergeScope.DISABLED);
    }

    public boolean present() {
        return scope.enabled() && !roadId.isBlank() && pathIndex >= 0;
    }
}
