package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class SegmentedRoadPathOrchestrator {
    private SegmentedRoadPathOrchestrator() {}

    public enum FailureReason { NONE, SEARCH_EXHAUSTED, SUBDIVISION_LIMIT }

    public record SegmentRequest(BlockPos from, BlockPos to) {}
    public record SegmentPlan(List<BlockPos> path, FailureReason failureReason) {}
    public record OrchestratedPath(boolean success, List<BlockPos> path,
                                   FailureReason failureReason,
                                   List<SegmentRequest> failedSegments) {}

    public static OrchestratedPath plan(
            BlockPos start, BlockPos end,
            List<BlockPos> anchors,
            Function<SegmentRequest, SegmentPlan> resolver,
            Predicate<SegmentRequest> subdivide) {
        if (start == null || end == null || resolver == null) {
            return new OrchestratedPath(false, List.of(), FailureReason.SEARCH_EXHAUSTED, List.of());
        }

        List<BlockPos> waypoints = new ArrayList<>();
        waypoints.add(start);
        if (anchors != null) waypoints.addAll(anchors);
        waypoints.add(end);

        List<BlockPos> fullPath = new ArrayList<>();
        List<SegmentRequest> failed = new ArrayList<>();

        for (int i = 0; i < waypoints.size() - 1; i++) {
            SegmentRequest req = new SegmentRequest(waypoints.get(i), waypoints.get(i + 1));
            SegmentPlan plan = resolveSegment(req, resolver, subdivide, 0);
            if (plan.failureReason() != FailureReason.NONE || plan.path().isEmpty()) {
                failed.add(req);
            } else {
                if (fullPath.isEmpty()) {
                    fullPath.addAll(plan.path());
                } else {
                    fullPath.addAll(plan.path().subList(1, plan.path().size()));
                }
            }
        }

        if (!failed.isEmpty()) {
            FailureReason reason = failed.stream()
                .map(f -> resolver.apply(f).failureReason())
                .filter(r -> r != FailureReason.NONE)
                .findFirst().orElse(FailureReason.SEARCH_EXHAUSTED);
            return new OrchestratedPath(false, fullPath, reason, failed);
        }
        return new OrchestratedPath(true, fullPath, FailureReason.NONE, List.of());
    }

    private static SegmentPlan resolveSegment(SegmentRequest req,
                                              Function<SegmentRequest, SegmentPlan> resolver,
                                              Predicate<SegmentRequest> subdivide,
                                              int depth) {
        if (depth > 4) {
            return new SegmentPlan(List.of(), FailureReason.SUBDIVISION_LIMIT);
        }
        SegmentPlan result = resolver.apply(req);
        if (result.failureReason() == FailureReason.NONE && !result.path().isEmpty()) {
            return result;
        }
        if (subdivide != null && subdivide.test(req)) {
            BlockPos mid = new BlockPos(
                (req.from().getX() + req.to().getX()) / 2,
                (req.from().getY() + req.to().getY()) / 2,
                (req.from().getZ() + req.to().getZ()) / 2
            );
            SegmentPlan first = resolveSegment(new SegmentRequest(req.from(), mid), resolver, subdivide, depth + 1);
            SegmentPlan second = resolveSegment(new SegmentRequest(mid, req.to()), resolver, subdivide, depth + 1);
            if (first.failureReason() == FailureReason.NONE && second.failureReason() == FailureReason.NONE) {
                List<BlockPos> combined = new ArrayList<>(first.path());
                if (!second.path().isEmpty()) {
                    combined.addAll(second.path().subList(1, second.path().size()));
                }
                return new SegmentPlan(combined, FailureReason.NONE);
            }
        }
        return result;
    }

    public static List<BlockPos> collectIntermediateAnchors(
            BlockPos start, BlockPos end,
            List<BlockPos> candidates,
            int maxAnchors, double corridorDistance) {
        if (start == null || end == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double lineLen = Math.sqrt(dx * dx + dz * dz);
        if (lineLen < 1.0) return List.of();

        List<BlockPos> inCorridor = new ArrayList<>();
        for (BlockPos c : candidates) {
            double t = ((c.getX() - start.getX()) * dx + (c.getZ() - start.getZ()) * dz) / (lineLen * lineLen);
            if (t <= 0.05 || t >= 0.95) continue;
            double projX = start.getX() + t * dx;
            double projZ = start.getZ() + t * dz;
            double dist = Math.sqrt((c.getX() - projX) * (c.getX() - projX) + (c.getZ() - projZ) * (c.getZ() - projZ));
            if (dist <= corridorDistance) {
                inCorridor.add(c);
            }
        }

        inCorridor.sort(Comparator.comparingDouble(c -> {
            double t = ((c.getX() - start.getX()) * dx + (c.getZ() - start.getZ()) * dz) / (lineLen * lineLen);
            return t;
        }));

        if (inCorridor.size() <= maxAnchors) return inCorridor;
        int step = inCorridor.size() / maxAnchors;
        List<BlockPos> selected = new ArrayList<>();
        for (int i = 0; i < inCorridor.size() && selected.size() < maxAnchors; i += step) {
            selected.add(inCorridor.get(i));
        }
        return selected;
    }

    public static boolean isContinuousResolvedPath(BlockPos start, BlockPos end, List<BlockPos> path) {
        return path != null && path.size() >= 2;
    }
}
