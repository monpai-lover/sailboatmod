package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class RoadPlanningIslandClassifier {
    private static final int DEFAULT_ISLAND_AREA_LIMIT = 32;
    private static final int[][] SAMPLE_DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    private RoadPlanningIslandClassifier() {
    }

    public static IslandSummary classify(Set<BlockPos> targetLandmass,
                                         Set<BlockPos> sourceLandmass,
                                         boolean separatedByWater) {
        int targetArea = targetLandmass == null ? 0 : (int) targetLandmass.stream().filter(Objects::nonNull).count();
        int sourceArea = sourceLandmass == null ? 0 : (int) sourceLandmass.stream().filter(Objects::nonNull).count();
        boolean islandLike = separatedByWater
                && targetArea > 0
                && targetArea <= DEFAULT_ISLAND_AREA_LIMIT
                && (sourceArea == 0 || targetArea < sourceArea);
        return new IslandSummary(islandLike, targetArea, sourceArea, separatedByWater);
    }

    static IslandSummary classifyForTest(Set<BlockPos> targetLandmass,
                                         Set<BlockPos> sourceLandmass,
                                         boolean separatedByWater) {
        return classify(targetLandmass, sourceLandmass, separatedByWater);
    }

    public record IslandSummary(boolean isIslandLike,
                                int targetArea,
                                int sourceArea,
                                boolean separatedByWater) {
    }

    public static IslandSummary classify(java.util.Map<Long, RoadPlanningSnapshot.ColumnSample> columns,
                                         BlockPos targetAnchor,
                                         BlockPos sourceAnchor,
                                         int sampleStep) {
        if (columns == null || columns.isEmpty() || sampleStep <= 0) {
            return new IslandSummary(false, 0, 0, false);
        }
        BlockPos targetSeed = nearestLandSample(columns, targetAnchor);
        BlockPos sourceSeed = nearestLandSample(columns, sourceAnchor);
        Set<Long> targetLandmass = floodFillLandmass(columns, targetSeed, sampleStep);
        Set<Long> sourceLandmass = floodFillLandmass(columns, sourceSeed, sampleStep);
        boolean sameLandmass = !targetLandmass.isEmpty()
                && !sourceLandmass.isEmpty()
                && targetLandmass.equals(sourceLandmass);
        boolean separatedByWater = !sameLandmass
                && !targetLandmass.isEmpty()
                && !sourceLandmass.isEmpty()
                && (hasAdjacentWater(columns, targetLandmass, sampleStep)
                || hasAdjacentWater(columns, sourceLandmass, sampleStep));
        return classify(
                toBlockPosSet(targetLandmass),
                toBlockPosSet(sourceLandmass),
                separatedByWater
        );
    }

    private static BlockPos nearestLandSample(java.util.Map<Long, RoadPlanningSnapshot.ColumnSample> columns,
                                              BlockPos anchor) {
        if (columns == null || columns.isEmpty() || anchor == null) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (java.util.Map.Entry<Long, RoadPlanningSnapshot.ColumnSample> entry : columns.entrySet()) {
            RoadPlanningSnapshot.ColumnSample sample = entry.getValue();
            if (!isLandSample(sample)) {
                continue;
            }
            BlockPos samplePos = columnPos(entry.getKey(), sample);
            double distance = samplePos.distSqr(anchor);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = samplePos;
            }
        }
        return best;
    }

    private static Set<Long> floodFillLandmass(java.util.Map<Long, RoadPlanningSnapshot.ColumnSample> columns,
                                               BlockPos start,
                                               int sampleStep) {
        if (columns == null || columns.isEmpty() || start == null) {
            return Set.of();
        }
        long startKey = BlockPos.asLong(start.getX(), 0, start.getZ());
        if (!isLandSample(columns.get(startKey))) {
            return Set.of();
        }
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        HashSet<Long> visited = new HashSet<>();
        open.add(start);
        visited.add(startKey);
        while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            for (int step : neighborSteps(sampleStep)) {
                for (int[] direction : SAMPLE_DIRECTIONS) {
                    BlockPos next = new BlockPos(
                            current.getX() + (direction[0] * step),
                            current.getY(),
                            current.getZ() + (direction[1] * step)
                    );
                    long nextKey = BlockPos.asLong(next.getX(), 0, next.getZ());
                    if (visited.contains(nextKey) || !isLandSample(columns.get(nextKey))) {
                        continue;
                    }
                    visited.add(nextKey);
                    open.addLast(next);
                }
            }
        }
        return Set.copyOf(visited);
    }

    private static boolean hasAdjacentWater(java.util.Map<Long, RoadPlanningSnapshot.ColumnSample> columns,
                                            Set<Long> landmass,
                                            int sampleStep) {
        if (columns == null || columns.isEmpty() || landmass == null || landmass.isEmpty()) {
            return false;
        }
        for (long key : landmass) {
            BlockPos pos = BlockPos.of(key);
            for (int step : neighborSteps(sampleStep)) {
                for (int[] direction : SAMPLE_DIRECTIONS) {
                    long neighborKey = BlockPos.asLong(
                            pos.getX() + (direction[0] * step),
                            0,
                            pos.getZ() + (direction[1] * step)
                    );
                    RoadPlanningSnapshot.ColumnSample sample = columns.get(neighborKey);
                    if (sample != null && sample.water()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int[] neighborSteps(int sampleStep) {
        return sampleStep <= 1 ? new int[] {1} : new int[] {1, sampleStep};
    }

    private static boolean isLandSample(RoadPlanningSnapshot.ColumnSample sample) {
        return sample != null && sample.traversableSurface() != null && !sample.water();
    }

    private static Set<BlockPos> toBlockPosSet(Set<Long> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        HashSet<BlockPos> positions = new HashSet<>(keys.size());
        for (long key : keys) {
            positions.add(BlockPos.of(key).immutable());
        }
        return Set.copyOf(positions);
    }

    private static BlockPos columnPos(Long key, RoadPlanningSnapshot.ColumnSample sample) {
        if (key == null) {
            return sample == null ? null : sample.traversableSurface();
        }
        BlockPos pos = BlockPos.of(key);
        int y = sample == null || sample.traversableSurface() == null
                ? 0
                : sample.traversableSurface().getY();
        return new BlockPos(pos.getX(), y, pos.getZ());
    }
}
