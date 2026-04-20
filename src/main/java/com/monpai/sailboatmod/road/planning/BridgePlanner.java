package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class BridgePlanner {
    private final RoadConfig config;

    public BridgePlanner(RoadConfig config) {
        this.config = config;
    }

    public record BridgePlanResult(boolean success, String failureReason,
                                    List<BlockPos> centerPath, List<BridgeSpan> bridgeSpans,
                                    List<BuildStep> buildSteps, RoadData roadData) {
        public static BridgePlanResult failure(String reason) {
            return new BridgePlanResult(false, reason, List.of(), List.of(), List.of(), null);
        }
    }

    public BridgePlanResult plan(ServerLevel level, BlockPos source, BlockPos target, int width) {
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());

        // Step 1: Find shoreline anchors near source and target
        BlockPos sourceAnchor = findShorelineAnchor(cache, source, target, 48);
        BlockPos targetAnchor = findShorelineAnchor(cache, target, source, 48);

        if (sourceAnchor == null) sourceAnchor = source;
        if (targetAnchor == null) targetAnchor = target;

        // Step 2: For bridges, draw a STRAIGHT LINE between anchors (no A* zigzag through water)
        List<BlockPos> straightPath = buildStraightPath(sourceAnchor, targetAnchor, cache);

        if (straightPath.isEmpty()) {
            return BridgePlanResult.failure("Bridge straight path generation failed");
        }

        // Step 3: Detect bridge spans on the straight path
        PathPostProcessor postProcessor = new PathPostProcessor();
        PathPostProcessor.ProcessedPath processed = postProcessor.process(
            straightPath, cache, config.getBridge().getBridgeMinWaterDepth());

        List<BlockPos> finalPath = processed.path().isEmpty() ? straightPath : processed.path();

        // Step 4: Classify spans and build
        List<BridgeSpan> spans = processed.bridgeSpans();
        if (spans.isEmpty()) {
            spans = detectCanyonSpans(finalPath, cache);
        }

        RoadBuilder roadBuilder = new RoadBuilder(config);
        RoadData roadData = roadBuilder.buildRoad("bridge", finalPath, width, cache);

        return new BridgePlanResult(true, null, finalPath, spans, roadData.buildSteps(), roadData);
    }

    private List<BlockPos> buildStraightPath(BlockPos from, BlockPos to, TerrainSamplingCache cache) {
        List<BlockPos> path = new ArrayList<>();
        int x0 = from.getX(), z0 = from.getZ();
        int x1 = to.getX(), z1 = to.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            int y = cache.getHeight(x0, z0);
            path.add(new BlockPos(x0, y, z0));
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 < dx) { err += dx; z0 += sz; }
        }
        return path;
    }

    /**
     * Find a shoreline anchor: a solid ground point near the source that faces toward the target,
     * preferably at the edge of water or a cliff.
     */
    private BlockPos findShorelineAnchor(TerrainSamplingCache cache, BlockPos near, BlockPos toward, int searchRadius) {
        double bestScore = Double.MAX_VALUE;
        BlockPos best = null;

        int dirX = Integer.signum(toward.getX() - near.getX());
        int dirZ = Integer.signum(toward.getZ() - near.getZ());

        for (int dx = -searchRadius; dx <= searchRadius; dx += 4) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += 4) {
                int x = near.getX() + dx;
                int z = near.getZ() + dz;

                if (cache.isWater(x, z)) continue; // Must be on land
                // Check if adjacent to water or void (shoreline)
                boolean nearEdge = cache.isWater(x + 2, z) || cache.isWater(x - 2, z)
                    || cache.isWater(x, z + 2) || cache.isWater(x, z - 2)
                    || isCanyonEdge(cache, x, z);

                if (!nearEdge) continue;

                // Score: prefer closer to source, facing toward target
                double distToSource = Math.sqrt((dx * dx) + (dz * dz));
                double dirAlignment = (dx * dirX + dz * dirZ); // positive = toward target
                double score = distToSource - dirAlignment * 2; // lower is better

                if (score < bestScore) {
                    bestScore = score;
                    int y = cache.getHeight(x, z);
                    best = new BlockPos(x, y, z);
                }
            }
        }
        return best;
    }

    private boolean isCanyonEdge(TerrainSamplingCache cache, int x, int z) {
        int h = cache.getHeight(x, z);
        // Check if there's a significant drop nearby (>= 6 blocks)
        for (int[] dir : new int[][]{{2,0},{-2,0},{0,2},{0,-2}}) {
            int nh = cache.getHeight(x + dir[0], z + dir[1]);
            if (h - nh >= 6) return true;
        }
        return false;
    }

    private List<BridgeSpan> detectCanyonSpans(List<BlockPos> path, TerrainSamplingCache cache) {
        List<BridgeSpan> spans = new ArrayList<>();
        int start = -1;
        for (int i = 1; i < path.size(); i++) {
            int prevH = cache.getHeight(path.get(i-1).getX(), path.get(i-1).getZ());
            int currH = cache.getHeight(path.get(i).getX(), path.get(i).getZ());
            boolean isVoid = Math.abs(prevH - currH) >= 4 ||
                (cache.getHeight(path.get(i).getX(), path.get(i).getZ()) < path.get(i).getY() - 3);

            if (isVoid && start == -1) {
                start = i;
            } else if (!isVoid && start != -1) {
                spans.add(new BridgeSpan(start, i - 1, prevH, currH));
                start = -1;
            }
        }
        if (start != -1) {
            spans.add(new BridgeSpan(start, path.size() - 1, 0, 0));
        }
        return spans;
    }

    private PathfindingConfig createBridgeFriendlyConfig() {
        PathfindingConfig cfg = new PathfindingConfig();
        cfg.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        cfg.setMaxSteps(40000); // Extra budget for water crossing
        // Very low water penalty - we WANT to cross water
        // The base TerrainCostModel.WATER_COLUMN_BASE_PENALTY is 80,
        // but we reduce the weight multipliers
        cfg.setWaterDepthWeight(5.0); // Much lower than default 80
        cfg.setNearWaterCost(5.0);    // Much lower than default 80
        cfg.setElevationWeight(40.0); // Lower than default 80 - bridges handle elevation
        cfg.setDeviationWeight(1.0);  // Slightly higher to keep path direct
        return cfg;
    }
}