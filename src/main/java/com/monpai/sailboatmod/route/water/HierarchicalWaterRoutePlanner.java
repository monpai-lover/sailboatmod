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
    public static final int DETAIL_SEGMENT_BLOCKS = 4096;
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
    private long stageDeadline;
    private List<BlockPos> result = List.of();

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
        WaterRoutePolicy policy = coarse ? coarsePolicy : detailedPolicy;
        if (stageDeadline != 0 && System.nanoTime() >= stageDeadline) { fail(WaterRouteFailureReason.TIMEOUT); return status; }
        if (search == null) {
            if (coarse && coarseExpandedNodes >= MAX_COARSE_NODES) { fail(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED); return status; }
            world.unknown = false;
            BlockPos a = coarse ? start : segments.get(completedSegments).from();
            BlockPos b = coarse ? goal : segments.get(completedSegments).to();
            if (stageDeadline == 0) stageDeadline = System.nanoTime() + Math.max(1L, policy.timeoutTicks()) * 50_000_000L;
            if (coarse) policy = coarseWithStep(coarsePolicy.stepSize(), MAX_COARSE_NODES - coarseExpandedNodes);
            search = new WaterRoutePathfinder(world, a, b, policy, coarse ? 64 : 2);
        }
        world.unknown = false;
        int before = search.expandedNodes();
        WaterRoutePathfinder.Status current = search.step(Math.min(MAX_ADVANCE_NODES, nodeBudget), 0);
        expandedNodes += search.expandedNodes() - before;
        if (coarse) coarseExpandedNodes += search.expandedNodes() - before;
        if (current == WaterRoutePathfinder.Status.SUCCESS) {
            if (coarse) {
                List<BlockPos> guidance = new ArrayList<>(search.path());
                removeSnappedEndpoints(guidance);
                splitGuidance(guidance);
                coarse = false;
            } else {
                List<BlockPos> finished;
                try { finished = finishSegment.apply(search.path()); }
                catch (TerrainNotReadyException waiting) { search = null; status = Status.WAITING; return status; }
                if (finished == null || finished.size() < 2) { fail(WaterRouteFailureReason.NO_WATER_PATH); return status; }
                appendCompacted(accumulated, finished);
                completedSegments++;
                if (completedSegments == segments.size()) {
                    result = List.copyOf(accumulated);
                    status = Status.SUCCESS;
                }
            }
            search = null;
            stageDeadline = 0;
        } else if (current == WaterRoutePathfinder.Status.WAITING) {
            // Preserve both frontiers and the pending node; do not redo known terrain or charge it twice.
            status = Status.WAITING;
        } else if (current == WaterRoutePathfinder.Status.FAILED) {
            if (coarse && search.failureReason() == WaterRouteFailureReason.NO_WATER_PATH && coarsePolicy.stepSize() > 8) {
                // A coarse grid can miss a narrow strait or harbour. Retry finer guidance with the same total node/time budget.
                coarsePolicy = coarseWithStep(coarsePolicy.stepSize() / 2, MAX_COARSE_NODES - coarseExpandedNodes);
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

    private void removeSnappedEndpoints(List<BlockPos> guidance) {
        if (guidance.size() > 2 && !start.equals(coarseWorld.snap(start, coarsePolicy))
                && guidance.get(1).equals(coarseWorld.snap(start, coarsePolicy))) guidance.remove(1);
        if (guidance.size() > 2 && !goal.equals(coarseWorld.snap(goal, coarsePolicy))
                && guidance.get(guidance.size() - 2).equals(coarseWorld.snap(goal, coarsePolicy))) guidance.remove(guidance.size() - 2);
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
        private BlockPos snap(BlockPos p, WaterRoutePolicy policy) {
            int step = policy.stepSize();
            return new BlockPos(Math.floorDiv(p.getX(), step) * step, p.getY(), Math.floorDiv(p.getZ(), step) * step);
        }
        @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            BlockPos endpoint = start != null && snap(start, policy).getX() == x && snap(start, policy).getZ() == z ? start
                    : goal != null && snap(goal, policy).getX() == x && snap(goal, policy).getZ() == z ? goal : null;
            int sampleX = endpoint == null ? x : endpoint.getX(), sampleZ = endpoint == null ? z : endpoint.getZ();
            long sampleKey = ((long) sampleX << 32) | (sampleZ & 0xFFFFFFFFL);
            WaterColumn column = start == null ? null : readySamples.get(sampleKey);
            if (column == null) {
                column = delegate.sample(sampleX, sampleZ, policy);
                if (start != null && column != null) readySamples.put(sampleKey, column);
            }
            if (column == null) { unknown = true; return null; }
            return column.passable() ? WaterColumn.passable(new BlockPos(x, column.surfacePos().getY(), z), column.extraCost()) : column;
        }
        @Override public boolean canLoadMoreChunks(int requested) { return false; }
        @Override public int consumedChunkLoads() { return 0; }
    }
}
