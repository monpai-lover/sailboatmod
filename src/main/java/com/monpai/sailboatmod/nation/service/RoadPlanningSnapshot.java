package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.Map;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public record RoadPlanningSnapshot(
        Map<Long, ColumnSample> columns,
        boolean sourceIslandLike,
        boolean targetIslandLike,
        List<BlockPos> bridgeDeckAnchors,
        BlockPos start,
        BlockPos end
) {
    public record ColumnSample(int surfaceY, boolean water) {}

    public boolean islandLikeAtEitherEndpoint() {
        return sourceIslandLike || targetIslandLike;
    }

    public ColumnSample column(int x, int z) {
        return columns == null ? null : columns.get(((long) x << 32) ^ (z & 0xffffffffL));
    }
}
