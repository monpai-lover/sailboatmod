package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Bounded global water guidance followed by independently bounded detailed segments. */
public final class HierarchicalWaterRoutePlanner {
    public static final int MAX_SAMPLES_PER_EXPANSION = 512;
    public static final int DETAIL_SEGMENT_BLOCKS = 2048;
    private static final int MAX_COARSE_NODES = 32_000;
    private static final int MAX_ADVANCE_NODES = 256;
    private static final int MAX_COARSE_STEP = 2048;

    public enum Status { RUNNING, WAITING, SUCCESS, FAILED, CANCELLED }
    public record Progress(Status status, int completedSegments, int totalSegments, int expandedNodes) { }

    private final TrackingWorld coarseWorld;
    private final TrackingWorld detailedWorld;
    private final BlockPos start, goal;
    private WaterRoutePolicy coarsePolicy;
    private final WaterRoutePolicy detailedPolicy;
    private final UnaryOperator<List<BlockPos>> finishSegment;
    private final List<Segment> segments = new ArrayList<>();
    private final List<BlockPos> accumulated = new ArrayList<>();
    private Status status = Status.RUNNING;
    private WaterRouteFailureReason failure = WaterRouteFailureReason.NONE;
    private WaterRoutePathfinder search;
    private boolean coarse = true;
    private int completedSegments, expandedNodes, coarseExpandedNodes;
    private RouteStageBudget stageBudget;
    private int detailStep, detailExpandedNodes;
    private List<BlockPos> result = List.of();
    private List<BlockPos> coarseGuidance = List.of();
    private int skippedGuidanceAnchors;

    public HierarchicalWaterRoutePlanner(WaterRouteWorld coarseWorld, WaterRouteWorld detailedWorld,
                                         BlockPos start, BlockPos goal, WaterRoutePolicy detailedPolicy) {
        this(coarseWorld, detailedWorld, start, goal, detailedPolicy, UnaryOperator.identity());
    }

    HierarchicalWaterRoutePlanner(WaterRouteWorld coarseWorld, WaterRouteWorld detailedWorld,
                                   BlockPos start, BlockPos goal, WaterRoutePolicy detailedPolicy,
                                   UnaryOperator<List<BlockPos>> finishSegment) {
        this.start = start;
        this.goal = goal;
        this.coarseWorld = new TrackingWorld(coarseWorld, start, goal);
        this.detailedWorld = new TrackingWorld(detailedWorld, null, null);
        this.finishSegment = finishSegment;
        WaterRoutePolicy fine = detailedPolicy == null ? WaterRoutePolicy.nbtRefine() : detailedPolicy;
        this.detailedPolicy = new WaterRoutePolicy(Math.max(1, Math.min(16, fine.stepSize())), fine.berthSearchStep(),
                fine.boatHalfWidth(), fine.clearanceHeight(), Math.min(8192, Math.max(6144, fine.maxSearchRadius())),
                Math.min(160_000, Math.max(1, fine.maxExpandedNodes())), 0, Math.max(1, fine.nodesPerTick()), 0, fine.timeoutTicks());
        double distance = start == null || goal == null ? 0 : Math.hypot((double) goal.getX() - start.getX(), (double) goal.getZ() - start.getZ());
        int step = 128;
        while (step < MAX_COARSE_STEP && distance / step > 128) step *= 2;
        int radius = (int) Math.min(Integer.MAX_VALUE, Math.max(8192, Math.ceil(distance * 1.5 + 8192)));
        this.detailStep = this.detailedPolicy.stepSize();
        this.coarsePolicy = new WaterRoutePolicy(step, fine.berthSearchStep(), fine.boatHalfWidth(), fine.clearanceHeight(),
                radius, MAX_COARSE_NODES, 0, 128, 0, Math.max(1200, fine.timeoutTicks()));
        if (coarseWorld == null || detailedWorld == null || start == null || goal == null) fail(WaterRouteFailureReason.NO_WATER_PATH);
    }

    /** One bounded search slice. No unfinished route is published; the caller may poll WAITING and cancel at any time. */
    public Status step(int nodeBudget) {
        if (status == Status.SUCCESS || status == Status.FAILED || status == Status.CANCELLED || nodeBudget <= 0) return status;
        if (WaterRouteCancellation.cancelled()) { cancel(); return status; }
        status = Status.RUNNING;
        TrackingWorld world = coarse ? coarseWorld : detailedWorld;
        WaterRoutePolicy policy = coarse ? coarsePolicy : detailWithStep(detailStep);
        if (stageBudget == null) stageBudget = new RouteStageBudget((long) policy.timeoutTicks() * 50L, 60_000L);
        WaterRouteFailureReason expired = stageBudget.advance(false);
        if (expired != WaterRouteFailureReason.NONE) { fail(expired); return status; }
        if (search == null) {
            if (coarse && coarseExpandedNodes >= MAX_COARSE_NODES) { fail(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED); return status; }
            if (!coarse && !prepareDetailedEndpoint(policy)) return status;
            world.unknown = false;
            BlockPos a = coarse ? start : segments.get(completedSegments).from();
            BlockPos b = coarse ? goal : segments.get(completedSegments).to();
            if (coarse) policy = coarseWithStep(coarsePolicy.stepSize(), MAX_COARSE_NODES - coarseExpandedNodes);
            search = new WaterRoutePathfinder(world, a, b, policy, coarse ? Math.min(16, Math.max(2, policy.stepSize() / 4)) : 1);
        }
        world.unknown = false;
        int before = search.expandedNodes();
        WaterRoutePathfinder.Status current = search.step(Math.min(MAX_ADVANCE_NODES, nodeBudget), 0);
        expandedNodes += search.expandedNodes() - before;
        if (coarse) coarseExpandedNodes += search.expandedNodes() - before;
        else detailExpandedNodes += search.expandedNodes() - before;
        if (current == WaterRoutePathfinder.Status.SUCCESS) {
            if (coarse) {
                List<BlockPos> guidance = new ArrayList<>(search.path());
                coarseGuidance = List.copyOf(guidance);
                splitGuidance(guidance);
                coarse = false;
            } else {
                List<BlockPos> finished;
                try { finished = finishSegment.apply(search.path()); }
                catch (TerrainNotReadyException waiting) {
                    // Keep the successful frontier/path; only retry the unfinished final validation.
                    stageBudget.advance(true); status = Status.WAITING; return status;
                }
                if (finished == null || finished.size() < 2) { fail(WaterRouteFailureReason.NO_WATER_PATH); return status; }
                appendCompacted(accumulated, finished);
                completedSegments++;
                if (completedSegments == segments.size()) {
                    result = List.copyOf(accumulated);
                    status = Status.SUCCESS;
                }
            }
            search = null;
            stageBudget = null;
            detailStep = detailedPolicy.stepSize();
            detailExpandedNodes = 0;
        } else if (current == WaterRoutePathfinder.Status.WAITING) {
            // Preserve both frontiers and the pending node; do not redo known terrain or charge it twice.
            stageBudget.advance(true);
            status = Status.WAITING;
        } else if (current == WaterRoutePathfinder.Status.FAILED) {
            if (coarse && search.failureReason() == WaterRouteFailureReason.NO_WATER_PATH && coarsePolicy.stepSize() > 1) {
                // A coarse grid can miss a narrow strait or harbour. Retry finer guidance with the same total node/time budget.
                coarsePolicy = coarseWithStep(coarsePolicy.stepSize() / 2, MAX_COARSE_NODES - coarseExpandedNodes);
                search = null;
            } else if (!coarse && search.failureReason() == WaterRouteFailureReason.NO_WATER_PATH
                    && detailStep > 1 && detailExpandedNodes < detailedPolicy.maxExpandedNodes()) {
                detailStep = Math.max(1, detailStep / 2);
                search = null;
            } else fail(search.failureReason());
        }
        return status;
    }

    public void cancel() {
        if (status == Status.SUCCESS || status == Status.FAILED) return;
        status = Status.CANCELLED;
        search = null;
        accumulated.clear();
        segments.clear();
        result = List.of();
    }

    public List<BlockPos> path() { return status == Status.SUCCESS ? result : List.of(); }
    public Progress progress() { return new Progress(status, completedSegments, segments.size(), expandedNodes); }
    public WaterRouteFailureReason failureReason() { return failure; }
    public int currentStepSize() { return coarse ? coarsePolicy.stepSize() : detailStep; }
    public int pendingEdges() { return search == null ? 0 : search.pendingEdges(); }
    public boolean isCoarseStage() { return coarse; }
    /** Guidance only, never a sailing route until all detailed segments are verified. */
    public List<BlockPos> coarseGuidance() { return coarseGuidance; }
    public int skippedGuidanceAnchors() { return skippedGuidanceAnchors; }

    /**
     * A LOD waypoint is a hint, not a mandatory docking position. Native LOD and
     * actual generator columns differ at some shores. Never force a fine search
     * to end on an intermediate hint which precise 2x2 sampling proves is land.
     * Merge that hint into the following segment, preserving the already verified
     * starting point. Real user endpoints are never moved or removed. Unknown data
     * suspends this preparation; it is NOT interpreted as a bad hint or as water.
     * Each poll is bounded so a long run of bad LOD hints remains cancellable.
     */
    private boolean prepareDetailedEndpoint(WaterRoutePolicy policy) {
        for (int checks = 0; checks < 32; checks++) {
            WaterRouteCancellation.check();
            Segment segment = segments.get(completedSegments);
            BlockPos hint = segment.to();
            WaterColumn column = detailedWorld.sample(hint.getX(), hint.getZ(), policy);
            if (column == null) {
                WaterRouteFailureReason expired = stageBudget.advance(true);
                if (expired != WaterRouteFailureReason.NONE) fail(expired);
                else status = Status.WAITING;
                return false;
            }
            boolean finalEndpoint = completedSegments == segments.size() - 1;
            if (column.passable() && column.surfacePos() != null) {
                BlockPos exact = column.surfacePos();
                if (finalEndpoint && !exact.equals(goal)) {
                    fail(WaterRouteFailureReason.NO_WATER_PATH);
                    return false;
                }
                if (!exact.equals(hint)) {
                    segments.set(completedSegments, new Segment(segment.from(), exact));
                    if (!finalEndpoint) {
                        Segment next = segments.get(completedSegments + 1);
                        segments.set(completedSegments + 1, new Segment(exact, next.to()));
                    }
                }
                return true;
            }
            if (finalEndpoint) {
                fail(WaterRouteFailureReason.NO_WATER_PATH);
                return false;
            }
            Segment next = segments.remove(completedSegments + 1);
            // Do not jump over terrain: the merged segment is still searched at
            // full precision, then checked by finishSegment before it is appended.
            segments.set(completedSegments, new Segment(segment.from(), next.to()));
            skippedGuidanceAnchors++;
        }
        return false; // RUNNING: bounded preparation continues on the next slice.
    }

    private WaterRoutePolicy detailWithStep(int step) {
        WaterRoutePolicy p = detailedPolicy;
        return new WaterRoutePolicy(step, p.berthSearchStep(), p.boatHalfWidth(), p.clearanceHeight(),
                p.maxSearchRadius(), Math.max(1, p.maxExpandedNodes() - detailExpandedNodes),
                0, p.nodesPerTick(), 0, p.timeoutTicks());
    }

    static final class TerrainNotReadyException extends RuntimeException {
        TerrainNotReadyException() { super("Water terrain is not ready", null, false, false); }
    }

    private WaterRoutePolicy coarseWithStep(int step, int nodes) {
        return new WaterRoutePolicy(step, coarsePolicy.berthSearchStep(), coarsePolicy.boatHalfWidth(), coarsePolicy.clearanceHeight(),
                coarsePolicy.maxSearchRadius(), Math.max(1, nodes), 0, coarsePolicy.nodesPerTick(), 0, coarsePolicy.timeoutTicks());
    }

    private void fail(WaterRouteFailureReason reason) {
        status = Status.FAILED;
        failure = reason;
        search = null;
        accumulated.clear();
    }

    private void splitGuidance(List<BlockPos> guidance) {
        for (int i = 1; i < guidance.size(); i++) {
            BlockPos a = guidance.get(i - 1), b = guidance.get(i);
            int pieces = Math.max(1, (int) Math.ceil(Math.hypot((double) b.getX() - a.getX(), (double) b.getZ() - a.getZ()) / DETAIL_SEGMENT_BLOCKS));
            BlockPos previous = a;
            for (int part = 1; part <= pieces; part++) {
                BlockPos next = part == pieces ? b : new BlockPos(
                        (int) Math.round(a.getX() + (b.getX() - (double) a.getX()) * part / pieces), a.getY(),
                        (int) Math.round(a.getZ() + (b.getZ() - (double) a.getZ()) * part / pieces));
                if (!next.equals(previous)) segments.add(new Segment(previous, next));
                previous = next;
            }
        }
        if (segments.isEmpty()) segments.add(new Segment(start, goal));
    }

    /** Removes only exactly collinear, forward points; no verified corner or edge is shortened across terrain. */
    static void appendCompacted(List<BlockPos> into, List<BlockPos> incoming) {
        for (BlockPos point : incoming) {
            if (!into.isEmpty() && into.getLast().equals(point)) continue;
            while (into.size() >= 2) {
                BlockPos a = into.get(into.size() - 2), b = into.getLast();
                long abx = (long) b.getX() - a.getX(), abz = (long) b.getZ() - a.getZ();
                long bcx = (long) point.getX() - b.getX(), bcz = (long) point.getZ() - b.getZ();
                if (a.getY() != b.getY() || b.getY() != point.getY() || abx * bcz != abz * bcx || abx * bcx + abz * bcz <= 0) break;
                into.removeLast();
            }
            into.add(point);
        }
    }

    private record Segment(BlockPos from, BlockPos to) { }

    private static final class TrackingWorld implements WaterRouteWorld {
        private final WaterRouteWorld delegate;
        private final BlockPos start, goal;
        private final Map<Long, WaterColumn> readySamples = new LinkedHashMap<>(128, 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, WaterColumn> eldest) { return size() > 65_536; }
        };
        private boolean unknown;
        private TrackingWorld(WaterRouteWorld delegate, BlockPos start, BlockPos goal) { this.delegate = delegate; this.start = start; this.goal = goal; }
        @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            // Actual grid points must be sampled at their own coordinates. Relabelling a
            // harbour's water as an offshore snapped cell could manufacture a route through land.
            long sampleKey = ((long) x << 32) | (z & 0xFFFFFFFFL);
            WaterColumn column = start == null ? null : readySamples.get(sampleKey);
            if (column == null) {
                column = delegate.sample(x, z, policy);
                if (start != null && column != null) readySamples.put(sampleKey, column);
            }
            if (column == null) { unknown = true; return null; }
            return column.passable() ? WaterColumn.passable(new BlockPos(x, column.surfacePos().getY(), z), column.extraCost()) : column;
        }
        @Override public boolean canLoadMoreChunks(int requested) { return false; }
        @Override public int consumedChunkLoads() { return 0; }
    }
}
