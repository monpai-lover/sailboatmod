package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.core.BlockPos;

public record RoadPlannerBuildProgressSnapshot(String roadId,
                                               String sourceTownName,
                                               String targetTownName,
                                               BlockPos focusPos,
                                               int progressPercent,
                                               int activeWorkers) {
    public RoadPlannerBuildProgressSnapshot {
        roadId = roadId == null ? "" : roadId;
        sourceTownName = sourceTownName == null ? "" : sourceTownName;
        targetTownName = targetTownName == null ? "" : targetTownName;
        focusPos = focusPos == null ? BlockPos.ZERO : focusPos.immutable();
        progressPercent = Math.max(0, Math.min(100, progressPercent));
        activeWorkers = Math.max(0, activeWorkers);
    }
}