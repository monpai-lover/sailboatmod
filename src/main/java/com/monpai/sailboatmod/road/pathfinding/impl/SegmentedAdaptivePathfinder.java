package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class SegmentedAdaptivePathfinder implements Pathfinder {

    private static final int SAMPLE_INTERVAL = 32;
    private static final int MIN_SEGMENT_LENGTH = 48;
    private static final double HILLY_THRESHOLD = 8.0;
    private static final double MOUNTAINOUS_THRESHOLD = 16.0;

    private final PathfindingConfig baseConfig;

    public SegmentedAdaptivePathfinder(PathfindingConfig config) {
        this.baseConfig = config;
    }

    private enum TerrainClass { FLAT, HILLY, MOUNTAINOUS, WATER }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        int manhattan = Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        if (manhattan < MIN_SEGMENT_LENGTH * 2) {
            PathfindingConfig.Algorithm algo = classifySingle(start, end, cache);
            return runWithAlgorithm(algo, start, end, cache);
        }

        List<Waypoint> waypoints = analyzeAndSegment(start, end, cache);
        if (waypoints.size() < 2) {
            return runWithAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD, start, end, cache);
        }

        List<BlockPos> fullPath = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            Waypoint wp = waypoints.get(i);
            BlockPos segStart = wp.pos;
            BlockPos segEnd = waypoints.get(i + 1).pos;
            PathResult segResult = runWithAlgorithm(wp.algorithm, segStart, segEnd, cache);
            if (!segResult.success()) {
                return runWithAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD, start, end, cache);
            }
            List<BlockPos> segPath = segResult.path();
            int startIdx = fullPath.isEmpty() ? 0 : 1;
            for (int j = startIdx; j < segPath.size(); j++) {
                fullPath.add(segPath.get(j));
            }
        }
        return fullPath.isEmpty() ? PathResult.failure("Segmented pathfinding produced empty path") : PathResult.success(fullPath);
    }

    private List<Waypoint> analyzeAndSegment(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        int sampleCount = Math.max(2, (int) (dist / SAMPLE_INTERVAL));

        List<SamplePoint> samples = new ArrayList<>(sampleCount + 1);
        for (int i = 0; i <= sampleCount; i++) {
            double t = (double) i / sampleCount;
            int sx = (int) Math.round(start.getX() + dx * t);
            int sz = (int) Math.round(start.getZ() + dz * t);
            int sy = cache.getHeight(sx, sz);
            boolean water = cache.isWater(sx, sz) || cache.isWaterBiome(sx, sz);
            samples.add(new SamplePoint(new BlockPos(sx, sy, sz), water));
        }

        List<Waypoint> waypoints = new ArrayList<>();
        TerrainClass prevClass = classifyTerrain(samples, 0, Math.min(3, samples.size()));
        int segStart = 0;

        for (int i = 1; i < samples.size(); i++) {
            TerrainClass tc = classifyTerrain(samples, Math.max(0, i - 1), Math.min(samples.size(), i + 2));
            if (tc != prevClass && (i - segStart) * SAMPLE_INTERVAL >= MIN_SEGMENT_LENGTH) {
                waypoints.add(new Waypoint(samples.get(segStart).pos, algorithmFor(prevClass)));
                segStart = i;
                prevClass = tc;
            }
        }
        waypoints.add(new Waypoint(samples.get(segStart).pos, algorithmFor(prevClass)));
        waypoints.add(new Waypoint(end, algorithmFor(prevClass)));
        return waypoints;
    }

    private TerrainClass classifyTerrain(List<SamplePoint> samples, int from, int to) {
        boolean anyWater = false;
        double maxHeightDiff = 0;
        for (int i = from; i < to; i++) {
            if (samples.get(i).water) anyWater = true;
            if (i > from) {
                double diff = Math.abs(samples.get(i).pos.getY() - samples.get(i - 1).pos.getY());
                maxHeightDiff = Math.max(maxHeightDiff, diff);
            }
        }
        if (anyWater) return TerrainClass.WATER;
        if (maxHeightDiff >= MOUNTAINOUS_THRESHOLD) return TerrainClass.MOUNTAINOUS;
        if (maxHeightDiff >= HILLY_THRESHOLD) return TerrainClass.HILLY;
        return TerrainClass.FLAT;
    }

    private static PathfindingConfig.Algorithm algorithmFor(TerrainClass tc) {
        return switch (tc) {
            case FLAT -> PathfindingConfig.Algorithm.BASIC_ASTAR;
            case HILLY -> PathfindingConfig.Algorithm.GRADIENT_DESCENT;
            case MOUNTAINOUS, WATER -> PathfindingConfig.Algorithm.POTENTIAL_FIELD;
        };
    }

    private PathfindingConfig.Algorithm classifySingle(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        int vertDiff = Math.abs(end.getY() - start.getY());
        boolean water = cache.isWater(start.getX(), start.getZ()) || cache.isWater(end.getX(), end.getZ());
        if (water) return PathfindingConfig.Algorithm.POTENTIAL_FIELD;
        if (vertDiff >= MOUNTAINOUS_THRESHOLD) return PathfindingConfig.Algorithm.POTENTIAL_FIELD;
        if (vertDiff >= HILLY_THRESHOLD) return PathfindingConfig.Algorithm.GRADIENT_DESCENT;
        return PathfindingConfig.Algorithm.BASIC_ASTAR;
    }

    private PathResult runWithAlgorithm(PathfindingConfig.Algorithm algo, BlockPos from, BlockPos to, TerrainSamplingCache cache) {
        PathfindingConfig cfg = new PathfindingConfig();
        cfg.setAlgorithm(algo);
        cfg.setAStarStep(baseConfig.getAStarStep());
        Pathfinder pathfinder = PathfinderFactory.create(cfg);
        return pathfinder.findPath(from, to, cache);
    }

    private record SamplePoint(BlockPos pos, boolean water) {}
    private record Waypoint(BlockPos pos, PathfindingConfig.Algorithm algorithm) {}
}
