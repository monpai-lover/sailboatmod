package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.generation.ThreadPoolManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SegmentedParallelPathfinder implements Pathfinder {
    private final PathfindingConfig config;
    private final Pathfinder delegate;
    private final ThreadPoolManager threadPool;

    public SegmentedParallelPathfinder(PathfindingConfig config, Pathfinder delegate, ThreadPoolManager threadPool) {
        this.config = config;
        this.delegate = delegate;
        this.threadPool = threadPool;
    }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        int manhattan = Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        if (manhattan <= config.getSegmentThreshold()) {
            return delegate.findPath(start, end, cache);
        }

        List<BlockPos> anchors = computeAnchors(start, end, cache);
        List<CompletableFuture<PathResult>> futures = new ArrayList<>();

        for (int i = 0; i < anchors.size() - 1; i++) {
            BlockPos from = anchors.get(i);
            BlockPos to = anchors.get(i + 1);
            futures.add(CompletableFuture.supplyAsync(
                () -> delegate.findPath(from, to, cache),
                threadPool.getExecutor()
            ));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            return PathResult.failure("Parallel pathfinding interrupted: " + e.getMessage());
        }

        List<BlockPos> merged = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            PathResult segResult;
            try {
                segResult = futures.get(i).get();
            } catch (Exception e) {
                return PathResult.failure("Segment " + i + " failed: " + e.getMessage());
            }
            if (!segResult.success()) {
                return PathResult.failure("Segment " + i + ": " + segResult.failureReason());
            }
            List<BlockPos> segPath = segResult.path();
            if (merged.isEmpty()) {
                merged.addAll(segPath);
            } else if (!segPath.isEmpty()) {
                merged.addAll(segPath.subList(1, segPath.size()));
            }
        }
        return PathResult.success(merged);
    }

    private List<BlockPos> computeAnchors(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        int manhattan = Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        int numSegments = Math.min(config.getMaxSegments(),
            Math.max(2, (manhattan + config.getSegmentThreshold() - 1) / config.getSegmentThreshold()));

        List<BlockPos> anchors = new ArrayList<>();
        anchors.add(start);

        for (int i = 1; i < numSegments; i++) {
            double t = (double) i / numSegments;
            int x = (int) Math.round(start.getX() + (end.getX() - start.getX()) * t);
            int z = (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * t);
            int y = cache.getHeight(x, z);
            anchors.add(new BlockPos(x, y, z));
        }

        anchors.add(end);
        return anchors;
    }
}
