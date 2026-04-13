package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public final class SegmentedRoadPathOrchestrator {
    private static final int MAX_SUBDIVISION_DEPTH = 3;

    private SegmentedRoadPathOrchestrator() {
    }

    public enum FailureReason {
        NONE,
        SEARCH_EXHAUSTED,
        NO_BRIDGE_ANCHORS,
        BRIDGE_HEAD_UNREACHABLE,
        PIER_CHAIN_DISCONNECTED,
        SUBDIVISION_LIMIT_EXCEEDED
    }

    public record SegmentRequest(BlockPos from, BlockPos to, int depth) {
    }

    public interface SegmentPlanner {
        SegmentPlan plan(SegmentRequest request);
    }

    public record SegmentPlan(List<BlockPos> path, FailureReason failureReason) {
        public SegmentPlan {
            path = path == null ? List.of() : List.copyOf(path);
            failureReason = failureReason == null ? FailureReason.NONE : failureReason;
        }

        public boolean success() {
            return path.size() >= 2;
        }
    }

    public record SegmentAttempt(BlockPos from, BlockPos to, List<BlockPos> path, FailureReason failureReason) {
        public SegmentAttempt {
            path = path == null ? List.of() : List.copyOf(path);
            failureReason = failureReason == null ? FailureReason.NONE : failureReason;
        }
    }

    public record OrchestratedPath(boolean success,
                                   List<BlockPos> path,
                                   List<SegmentAttempt> segments,
                                   FailureReason failureReason,
                                   List<SegmentAttempt> failedSegments) {
        public OrchestratedPath {
            path = path == null ? List.of() : List.copyOf(path);
            segments = segments == null ? List.of() : List.copyOf(segments);
            failureReason = failureReason == null ? FailureReason.NONE : failureReason;
            failedSegments = failedSegments == null ? List.of() : List.copyOf(failedSegments);
        }
    }

    static OrchestratedPath planForTest(BlockPos start,
                                        BlockPos end,
                                        List<BlockPos> anchors,
                                        Function<SegmentRequest, List<BlockPos>> planner,
                                        Predicate<SegmentRequest> maySubdivide) {
        return plan(start, end, anchors, request -> new SegmentPlan(planner.apply(request), FailureReason.SEARCH_EXHAUSTED), maySubdivide);
    }

    public static OrchestratedPath plan(BlockPos start,
                                        BlockPos end,
                                        List<BlockPos> anchors,
                                        SegmentPlanner planner,
                                        Predicate<SegmentRequest> maySubdivide) {
        List<BlockPos> chain = buildAnchorChain(start, end, anchors);
        ArrayList<SegmentAttempt> successfulSegments = new ArrayList<>();
        ArrayList<SegmentAttempt> failedSegments = new ArrayList<>();
        LinkedHashSet<BlockPos> stitched = new LinkedHashSet<>();

        for (int i = 0; i + 1 < chain.size(); i++) {
            SegmentAttempt resolved = resolveSegment(
                    chain.get(i),
                    chain.get(i + 1),
                    0,
                    Objects.requireNonNull(planner, "planner"),
                    Objects.requireNonNull(maySubdivide, "maySubdivide"),
                    successfulSegments,
                    failedSegments
                );
            if (resolved.path().isEmpty()) {
                return new OrchestratedPath(
                        false,
                        List.of(),
                        successfulSegments,
                        resolved.failureReason(),
                        failedSegments
                );
            }
            stitched.addAll(resolved.path());
        }

        return new OrchestratedPath(true, List.copyOf(stitched), successfulSegments, FailureReason.NONE, List.of());
    }

    static List<BlockPos> collectIntermediateAnchorsForTest(BlockPos start,
                                                            BlockPos end,
                                                            List<BlockPos> anchors,
                                                            int maxAnchors,
                                                            double maxDistanceFromLine) {
        return collectIntermediateAnchors(start, end, anchors, maxAnchors, maxDistanceFromLine);
    }

    public static List<BlockPos> collectIntermediateAnchors(BlockPos start,
                                                            BlockPos end,
                                                            List<BlockPos> anchors,
                                                            int maxAnchors,
                                                            double maxDistanceFromLine) {
        if (start == null || end == null || anchors == null || anchors.isEmpty() || maxAnchors <= 0) {
            return List.of();
        }

        double routeDx = end.getX() - start.getX();
        double routeDz = end.getZ() - start.getZ();
        double routeLengthSq = (routeDx * routeDx) + (routeDz * routeDz);
        if (routeLengthSq <= 0.0D) {
            return List.of();
        }

        return anchors.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .filter(anchor -> !anchor.equals(start) && !anchor.equals(end))
                .filter(anchor -> projectedFraction(start, end, anchor) > 0.0D && projectedFraction(start, end, anchor) < 1.0D)
                .filter(anchor -> maxDistanceFromLine < 0.0D || lateralDistanceFromLine(start, end, anchor, routeLengthSq) <= maxDistanceFromLine)
                .distinct()
                .sorted(Comparator
                        .comparingDouble((BlockPos anchor) -> projectedFraction(start, end, anchor))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .limit(maxAnchors)
                .toList();
    }

    private static List<BlockPos> buildAnchorChain(BlockPos start, BlockPos end, List<BlockPos> anchors) {
        ArrayList<BlockPos> chain = new ArrayList<>();
        chain.add(Objects.requireNonNull(start, "start").immutable());
        for (BlockPos anchor : anchors == null ? List.<BlockPos>of() : anchors) {
            if (anchor == null || anchor.equals(start) || anchor.equals(end)) {
                continue;
            }
            chain.add(anchor.immutable());
        }
        chain.add(Objects.requireNonNull(end, "end").immutable());
        return List.copyOf(chain);
    }

    private static SegmentAttempt resolveSegment(BlockPos from,
                                                 BlockPos to,
                                                 int depth,
                                                 SegmentPlanner planner,
                                                 Predicate<SegmentRequest> maySubdivide,
                                                 List<SegmentAttempt> successfulSegments,
                                                 List<SegmentAttempt> failedSegments) {
        SegmentRequest request = new SegmentRequest(from, to, depth);
        SegmentPlan plan = planner.plan(request);
        if (plan != null && plan.success()) {
            SegmentAttempt success = new SegmentAttempt(from, to, plan.path(), FailureReason.NONE);
            successfulSegments.add(success);
            return success;
        }

        if (depth >= MAX_SUBDIVISION_DEPTH || !maySubdivide.test(request)) {
            SegmentAttempt failure = new SegmentAttempt(from, to, List.of(), FailureReason.SUBDIVISION_LIMIT_EXCEEDED);
            failedSegments.add(failure);
            return failure;
        }

        BlockPos midpoint = midpoint(from, to);
        SegmentAttempt left = resolveSegment(from, midpoint, depth + 1, planner, maySubdivide, successfulSegments, failedSegments);
        if (left.path().isEmpty()) {
            return left;
        }
        SegmentAttempt right = resolveSegment(midpoint, to, depth + 1, planner, maySubdivide, successfulSegments, failedSegments);
        if (right.path().isEmpty()) {
            return right;
        }

        LinkedHashSet<BlockPos> stitched = new LinkedHashSet<>();
        stitched.addAll(left.path());
        stitched.addAll(right.path());
        return new SegmentAttempt(from, to, List.copyOf(stitched), FailureReason.NONE);
    }

    private static double projectedFraction(BlockPos start, BlockPos end, BlockPos anchor) {
        double routeDx = end.getX() - start.getX();
        double routeDz = end.getZ() - start.getZ();
        double routeLengthSq = (routeDx * routeDx) + (routeDz * routeDz);
        if (routeLengthSq <= 0.0D) {
            return 0.0D;
        }
        double anchorDx = anchor.getX() - start.getX();
        double anchorDz = anchor.getZ() - start.getZ();
        return ((anchorDx * routeDx) + (anchorDz * routeDz)) / routeLengthSq;
    }

    private static double lateralDistanceFromLine(BlockPos start, BlockPos end, BlockPos anchor, double routeLengthSq) {
        if (routeLengthSq <= 0.0D) {
            return Double.MAX_VALUE;
        }
        double routeDx = end.getX() - start.getX();
        double routeDz = end.getZ() - start.getZ();
        double anchorDx = anchor.getX() - start.getX();
        double anchorDz = anchor.getZ() - start.getZ();
        double cross = Math.abs((anchorDx * routeDz) - (anchorDz * routeDx));
        return cross / Math.sqrt(routeLengthSq);
    }

    private static BlockPos midpoint(BlockPos from, BlockPos to) {
        return new BlockPos(
                Math.floorDiv(from.getX() + to.getX(), 2),
                Math.floorDiv(from.getY() + to.getY(), 2),
                Math.floorDiv(from.getZ() + to.getZ(), 2)
        );
    }
}
