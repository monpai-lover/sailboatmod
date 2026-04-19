package com.monpai.sailboatmod.road.pathfinding;

import net.minecraft.core.BlockPos;
import java.util.List;

public record PathResult(List<BlockPos> path, boolean success, String failureReason) {
    public static PathResult success(List<BlockPos> path) {
        return new PathResult(path, true, null);
    }
    public static PathResult failure(String reason) {
        return new PathResult(List.of(), false, reason);
    }
}
