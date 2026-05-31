package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reuse run detection adapted from RoadWeaver's RoadSnapService.
 */
public final class RoadGraphReusePlanner {
    private static final int SNAP_THRESHOLD = 12;
    private static final int SPLIT_THRESHOLD = 24;
    private static final int MIN_RUN = 3;
    private static final long SNAP_DIST2 = (long) SNAP_THRESHOLD * SNAP_THRESHOLD;
    private static final long SPLIT_DIST2 = (long) SPLIT_THRESHOLD * SPLIT_THRESHOLD;

    private final RoadGraphRepository repository;

    public RoadGraphReusePlanner(RoadGraphRepository repository) {
        this.repository = repository == null ? new RoadGraphRepository(new RoadNetworkGraphSavedData()) : repository;
    }

    public RoadReusePlan planReuse(String dimensionId,
                                   List<RoadGraphSegmentPlacement> plannedPlacements,
                                   List<BlockPos> logicalCenterline,
                                   CompiledRoadSectionType plannedType) {
        List<RoadGraphSegmentPlacement> placements = plannedPlacements == null ? List.of() : plannedPlacements;
        if (placements.size() < MIN_RUN) {
            return RoadReusePlan.noReuse(placements, logicalCenterline);
        }
        CompiledRoadSectionType safePlannedType = plannedType == null ? CompiledRoadSectionType.ROAD : plannedType;
        MatchResult best = bestMatch(dimensionId, placements, safePlannedType);
        if (best == null || best.runs().isEmpty()) {
            return new RoadReusePlan(logicalCenterline, logicalCenterline, placements,
                    List.of(new RoadReusePlan.Range(0, placements.size() - 1)), List.of(),
                    best == null ? List.of() : best.rejections());
        }
        List<RoadGraphReuseSpan> spans = new ArrayList<>();
        for (int[] run : best.runs()) {
            int plannedFrom = run[0];
            int plannedTo = run[1];
            int sourceFrom = best.targets()[plannedFrom];
            int sourceTo = best.targets()[plannedTo];
            spans.add(new RoadGraphReuseSpan(best.edge().edgeId(), sourceFrom, sourceTo,
                    plannedFrom, plannedTo,
                    placements.get(plannedFrom).middlePos(),
                    placements.get(plannedTo).middlePos(),
                    RoadGraphReuseSpan.Relationship.OWN));
        }
        return new RoadReusePlan(logicalCenterline, logicalCenterline, placements,
                ownedRanges(placements.size(), spans), spans, best.rejections());
    }

    private MatchResult bestMatch(String dimensionId,
                                  List<RoadGraphSegmentPlacement> placements,
                                  CompiledRoadSectionType plannedType) {
        MatchResult best = null;
        ArrayList<RoadReusePlan.Rejection> rejections = new ArrayList<>();
        for (RoadGraphEdgeRecord edge : repository.edgesForDimension(dimensionId)) {
            if (edge == null || !edge.built()) {
                continue;
            }
            if (!compatible(plannedType, edge.sectionType())) {
                if (!placements.isEmpty()) {
                    rejections.add(new RoadReusePlan.Rejection("incompatible_section_type", placements.get(0).middlePos()));
                }
                continue;
            }
            int[] targets = markTargets(placements, edge.placements());
            List<int[]> runs = extractRuns(targets, placements.size());
            if (runs.isEmpty()) {
                continue;
            }
            MatchResult candidate = new MatchResult(edge, targets, runs, List.copyOf(rejections));
            if (best == null || totalRunLength(candidate.runs()) > totalRunLength(best.runs())) {
                best = candidate;
            }
        }
        return best == null && !rejections.isEmpty()
                ? new MatchResult(null, new int[0], List.of(), List.copyOf(rejections))
                : best;
    }

    private int[] markTargets(List<RoadGraphSegmentPlacement> planned,
                              List<RoadGraphSegmentPlacement> source) {
        int[] targets = new int[planned.size()];
        Arrays.fill(targets, -1);
        boolean snapping = false;
        for (int index = 0; index < planned.size(); index++) {
            Nearest nearest = nearest(source, planned.get(index).middlePos());
            if (nearest == null) {
                snapping = false;
                continue;
            }
            if (!snapping) {
                if (nearest.distanceSqr() <= SNAP_DIST2 && directionCompatible(planned, index, source, nearest.index())) {
                    snapping = true;
                    targets[index] = nearest.index();
                }
            } else if (nearest.distanceSqr() <= SPLIT_DIST2 && directionCompatible(planned, index, source, nearest.index())) {
                targets[index] = nearest.index();
            } else {
                snapping = false;
            }
        }
        return targets;
    }

    private static List<int[]> extractRuns(int[] targets, int size) {
        ArrayList<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int index = 0; index < size; index++) {
            if (targets[index] >= 0) {
                if (start < 0) {
                    start = index;
                }
            } else if (start >= 0) {
                if (index - start >= MIN_RUN) {
                    runs.add(new int[] { start, index - 1 });
                }
                start = -1;
            }
        }
        if (start >= 0 && size - start >= MIN_RUN) {
            runs.add(new int[] { start, size - 1 });
        }
        return List.copyOf(runs);
    }

    private static List<RoadReusePlan.Range> ownedRanges(int size, List<RoadGraphReuseSpan> spans) {
        ArrayList<RoadReusePlan.Range> ranges = new ArrayList<>();
        int cursor = 0;
        for (RoadGraphReuseSpan span : spans) {
            if (cursor < span.plannedFromIndex()) {
                ranges.add(new RoadReusePlan.Range(cursor, span.plannedFromIndex() - 1));
            }
            cursor = span.plannedToIndex() + 1;
        }
        if (cursor < size) {
            ranges.add(new RoadReusePlan.Range(cursor, size - 1));
        }
        return List.copyOf(ranges);
    }

    private static boolean compatible(CompiledRoadSectionType plannedType, CompiledRoadSectionType sourceType) {
        if (plannedType == sourceType) {
            return true;
        }
        return plannedType == CompiledRoadSectionType.ROAD && sourceType == CompiledRoadSectionType.ROAD;
    }

    private static boolean directionCompatible(List<RoadGraphSegmentPlacement> planned, int plannedIndex,
                                               List<RoadGraphSegmentPlacement> source, int sourceIndex) {
        double[] plannedDir = direction(planned, plannedIndex);
        double[] sourceDir = direction(source, sourceIndex);
        if (plannedDir == null || sourceDir == null) {
            return true;
        }
        double dot = Math.abs(plannedDir[0] * sourceDir[0] + plannedDir[1] * sourceDir[1]);
        return dot > 0.3D;
    }

    private static double[] direction(List<RoadGraphSegmentPlacement> placements, int index) {
        if (placements == null || placements.size() < 2) {
            return null;
        }
        int previous = Math.max(0, index - 2);
        int next = Math.min(placements.size() - 1, index + 2);
        if (previous == next) {
            return null;
        }
        BlockPos a = placements.get(previous).middlePos();
        BlockPos b = placements.get(next).middlePos();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double length = Math.hypot(dx, dz);
        return length < 1.0D ? null : new double[] { dx / length, dz / length };
    }

    private static Nearest nearest(List<RoadGraphSegmentPlacement> source, BlockPos target) {
        if (source == null || source.isEmpty() || target == null) {
            return null;
        }
        int bestIndex = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < source.size(); index++) {
            long distance = dist2XZ(target, source.get(index).middlePos());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex < 0 ? null : new Nearest(bestIndex, bestDistance);
    }

    private static long dist2XZ(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static int totalRunLength(List<int[]> runs) {
        int total = 0;
        for (int[] run : runs) {
            total += run[1] - run[0] + 1;
        }
        return total;
    }

    private record Nearest(int index, long distanceSqr) {
    }

    private record MatchResult(RoadGraphEdgeRecord edge,
                               int[] targets,
                               List<int[]> runs,
                               List<RoadReusePlan.Rejection> rejections) {
    }
}
