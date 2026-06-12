package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

public final class WaterRouteTask {
    private final String dimensionId;
    private final BlockPos sourceDockPos;
    private final BlockPos targetDockPos;
    private final BlockPos sourceBerthPos;
    private final BlockPos targetBerthPos;
    private final String requesterName;
    private final WaterRoutePolicy policy;
    private final WaterRoutePathfinder pathfinder;
    private final CompletionHandler completionHandler;
    private final String key;
    private int elapsedTicks;
    private boolean completed;

    public WaterRouteTask(String dimensionId,
                          BlockPos sourceDockPos,
                          BlockPos targetDockPos,
                          BlockPos sourceBerthPos,
                          BlockPos targetBerthPos,
                          String requesterName,
                          WaterRoutePolicy policy,
                          WaterRoutePathfinder pathfinder,
                          CompletionHandler completionHandler) {
        this.dimensionId = dimensionId == null ? "" : dimensionId;
        this.sourceDockPos = sourceDockPos;
        this.targetDockPos = targetDockPos;
        this.sourceBerthPos = sourceBerthPos;
        this.targetBerthPos = targetBerthPos;
        this.requesterName = requesterName == null ? "" : requesterName;
        this.policy = policy == null ? WaterRoutePolicy.defaults() : policy;
        this.pathfinder = Objects.requireNonNull(pathfinder, "pathfinder");
        this.completionHandler = completionHandler;
        this.key = key(this.dimensionId, sourceDockPos, targetDockPos);
    }

    boolean advance() {
        if (completed) {
            return true;
        }
        elapsedTicks++;
        if (elapsedTicks > policy.timeoutTicks()) {
            complete(WaterRouteResult.failure(WaterRouteFailureReason.TIMEOUT));
            return true;
        }
        WaterRoutePathfinder.Status status = pathfinder.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
        if (status == WaterRoutePathfinder.Status.SUCCESS) {
            complete(WaterRouteResult.success(pathfinder.path()));
        } else if (status == WaterRoutePathfinder.Status.FAILED) {
            complete(WaterRouteResult.failure(pathfinder.failureReason()));
        }
        return completed;
    }

    private void complete(WaterRouteResult<List<BlockPos>> result) {
        if (completed) {
            return;
        }
        completed = true;
        if (completionHandler != null) {
            completionHandler.complete(result);
        }
    }

    public String dimensionId() {
        return dimensionId;
    }

    public BlockPos sourceDockPos() {
        return sourceDockPos;
    }

    public BlockPos targetDockPos() {
        return targetDockPos;
    }

    public BlockPos sourceBerthPos() {
        return sourceBerthPos;
    }

    public BlockPos targetBerthPos() {
        return targetBerthPos;
    }

    public String requesterName() {
        return requesterName;
    }

    public String key() {
        return key;
    }

    public boolean completed() {
        return completed;
    }

    private static String key(String dimensionId, BlockPos sourceDockPos, BlockPos targetDockPos) {
        return dimensionId + "|" + posKey(sourceDockPos) + "|" + posKey(targetDockPos);
    }

    private static String posKey(BlockPos pos) {
        return pos == null ? "null" : Long.toString(pos.asLong());
    }

    @FunctionalInterface
    public interface CompletionHandler {
        void complete(WaterRouteResult<List<BlockPos>> result);
    }
}
