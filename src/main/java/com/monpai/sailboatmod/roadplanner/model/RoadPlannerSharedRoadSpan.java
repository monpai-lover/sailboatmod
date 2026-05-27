package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;

import java.util.Locale;

public record RoadPlannerSharedRoadSpan(String roadId,
                                        int fromPathIndex,
                                        int toPathIndex,
                                        BlockPos fromPos,
                                        BlockPos toPos,
                                        RoadPlannerMergeScope scope,
                                        Role role) {
    public RoadPlannerSharedRoadSpan {
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        fromPathIndex = Math.max(-1, fromPathIndex);
        toPathIndex = Math.max(-1, toPathIndex);
        fromPos = fromPos == null ? BlockPos.ZERO : fromPos.immutable();
        toPos = toPos == null ? BlockPos.ZERO : toPos.immutable();
        scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
        role = role == null ? Role.END_MERGE : role;
    }

    public static RoadPlannerSharedRoadSpan none() {
        return new RoadPlannerSharedRoadSpan(
                "",
                -1,
                -1,
                BlockPos.ZERO,
                BlockPos.ZERO,
                RoadPlannerMergeScope.DISABLED,
                Role.END_MERGE);
    }

    public boolean present() {
        return scope.enabled()
                && !roadId.isBlank()
                && fromPathIndex >= 0
                && toPathIndex >= 0
                && fromPathIndex != toPathIndex;
    }

    public enum Role {
        START_REUSE,
        END_MERGE
    }
}
