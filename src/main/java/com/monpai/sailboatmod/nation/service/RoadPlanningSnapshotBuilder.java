package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.Map;
import java.util.Set;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadPlanningSnapshotBuilder {
    private RoadPlanningSnapshotBuilder() {}

    public static RoadPlanningSnapshot build(ServerLevel level, BlockPos start, BlockPos end,
                                             Set<Long> blockedColumns, Set<Long> excludedColumns) {
        return new RoadPlanningSnapshot(Map.of(), false, false, java.util.List.of(), start, end);
    }
}
