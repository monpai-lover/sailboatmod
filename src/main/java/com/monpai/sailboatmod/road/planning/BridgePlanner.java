package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class BridgePlanner {
    private static final int MAX_RAMP_STEPS = 10;
    private static final int MIN_DECK_CLEARANCE = 3;
    private static final int SEA_LEVEL = 63;

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

        // Step 1: Find shoreline anchors
        BlockPos srcShore = findShorelineAnchor(cache, source, target, 48);
        BlockPos tgtShore = findShorelineAnchor(cache, target, source, 48);
        if (srcShore == null) srcShore = source;
        if (tgtShore == null) tgtShore = target;

        // Step 2: Three-segment path
        // Segment A: source → srcShore (land road)
        List<BlockPos> segA = findLandPath(source, srcShore, cache);

        // Segment B: srcShore → tgtShore (bridge, shallow-water-preferred A*)
        List<BlockPos> segB = findBridgePath(srcShore, tgtShore, cache);
        if (segB.isEmpty()) {
            return BridgePlanResult.failure("Bridge water crossing path failed");
        }

        // Segment C: tgtShore → target (land road)
        List<BlockPos> segC = findLandPath(tgtShore, target, cache);

        // Step 3: Merge three segments
        List<BlockPos> fullPath = new ArrayList<>();
        fullPath.addAll(segA);
        if (!segB.isEmpty()) {
            int skip = (!segA.isEmpty() && !segB.isEmpty()
                    && segA.get(segA.size() - 1).equals(segB.get(0))) ? 1 : 0;
            for (int i = skip; i < segB.size(); i++) fullPath.add(segB.get(i));
        }
        if (!segC.isEmpty()) {
            int skip = (!fullPath.isEmpty() && !segC.isEmpty()
                    && fullPath.get(fullPath.size() - 1).equals(segC.get(0))) ? 1 : 0;
            for (int i = skip; i < segC.size(); i++) fullPath.add(segC.get(i));
        }

        if (fullPath.isEmpty()) {
            return BridgePlanResult.failure("Bridge full path is empty");
        }

        // Step 4: Post-process and build
        int halfWidth = PathPostProcessor.halfWidthForRoadWidth(width);
        PathPostProcessor post = new PathPostProcessor();
        PathPostProcessor.ProcessedPath processed = post.process(
                fullPath, cache, config.getBridge().getBridgeMinWaterDepth(), halfWidth);
        List<BlockPos> finalPath = processed.path().isEmpty() ? fullPath : processed.path();

        // Step 5: Override bridge config with adaptive deck height
        RoadConfig adaptiveConfig = createAdaptiveConfig(cache, srcShore, tgtShore);
        RoadBuilder builder = new RoadBuilder(adaptiveConfig);
        RoadData roadData = builder.buildRoad("bridge", finalPath, width, cache, "auto",
                processed.placements(), processed.bridgeSpans());

        List<BridgeSpan> spans = processed.bridgeSpans();
        RouteCandidateMetrics metrics = RouteCandidateMetrics.from(segB, spans);
        if (!metrics.bridgeDominant()) {
            return new BridgePlanResult(false, "Bridge route is not bridge-dominant", finalPath, spans, List.of(), null);
        }
        return new BridgePlanResult(true, null, finalPath, spans, roadData.buildSteps(), roadData);
    }

    private List<BlockPos> findLandPath(BlockPos from, BlockPos to, TerrainSamplingCache cache) {
        if (from.equals(to)) return List.of(from);
        int manhattan = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        if (manhattan <= 3) return List.of(from, to);
        Pathfinder pf = PathfinderFactory.create(config.getPathfinding());
        PathResult r = pf.findPath(from, to, cache);
        return r.success() ? r.path() : List.of(from, to);
    }

    private List<BlockPos> findBridgePath(BlockPos from, BlockPos to, TerrainSamplingCache cache) {
        return buildStraightPath(from, to, cache);
    }

    private List<BlockPos> buildStraightPath(BlockPos from, BlockPos to, TerrainSamplingCache cache) {
        List<BlockPos> path = new ArrayList<>();
        int x0 = from.getX(), z0 = from.getZ();
        int x1 = to.getX(), z1 = to.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        while (true) {
            path.add(new BlockPos(x0, cache.getHeight(x0, z0), z0));
            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 < dx) { err += dx; z0 += sz; }
        }
        return path;
    }

    private RoadConfig createAdaptiveConfig(TerrainSamplingCache cache, BlockPos srcShore, BlockPos tgtShore) {
        int srcH = cache.getHeight(srcShore.getX(), srcShore.getZ());
        int tgtH = cache.getHeight(tgtShore.getX(), tgtShore.getZ());
        int maxShoreH = Math.max(srcH, tgtH);
        int adaptiveDeck = Math.max(maxShoreH + MIN_DECK_CLEARANCE, SEA_LEVEL + MIN_DECK_CLEARANCE);
        // Ensure ramp won't exceed MAX_RAMP_STEPS (each step = 0.5 block)
        int maxRampHeight = MAX_RAMP_STEPS / 2;
        adaptiveDeck = Math.max(adaptiveDeck, Math.max(srcH, tgtH) + 1);
        adaptiveDeck = Math.min(adaptiveDeck, Math.min(srcH, tgtH) + maxRampHeight);

        RoadConfig rc = new RoadConfig();
        rc.getBridge().setDeckHeight(adaptiveDeck - SEA_LEVEL);
        return rc;
    }

    private BlockPos findShorelineAnchor(TerrainSamplingCache cache, BlockPos near, BlockPos toward, int searchRadius) {
        double bestScore = Double.MAX_VALUE;
        BlockPos best = null;
        int dirX = Integer.signum(toward.getX() - near.getX());
        int dirZ = Integer.signum(toward.getZ() - near.getZ());

        for (int dx = -searchRadius; dx <= searchRadius; dx += 2) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += 2) {
                int x = near.getX() + dx;
                int z = near.getZ() + dz;
                if (cache.isWater(x, z)) continue;
                boolean nearEdge = cache.isWater(x + 2, z) || cache.isWater(x - 2, z)
                        || cache.isWater(x, z + 2) || cache.isWater(x, z - 2);
                if (!nearEdge) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double align = dx * dirX + dz * dirZ;
                double score = dist - align * 2;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(x, cache.getHeight(x, z), z);
                }
            }
        }
        return best;
    }
}
