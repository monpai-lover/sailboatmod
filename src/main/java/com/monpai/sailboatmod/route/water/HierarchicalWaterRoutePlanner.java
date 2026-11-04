package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.function.LongSupplier;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

/** Bounded global water guidance followed by independently bounded detailed segments. */
public final class HierarchicalWaterRoutePlanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final long MAX_TASK_MILLIS = 600_000L;
    private final LongSupplier clock;
    private final long taskStarted;
    private RouteSearchProgress searchProgress;
    private long nextBudgetLog, loggedGrant;

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
    /** edgeDirect.get(i): edge accumulated[i] -> accumulated[i+1] came entirely from direct (open-water) legs. */
    private final List<Boolean> edgeDirect = new ArrayList<>();
    private List<Boolean> resultDirect = List.of();
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
    private boolean detailedStartValidated;
    private Segment prefixProbeSegment;
    private double prefixProbeDistance;
    private Segment suffixProbeSegment;
    private double suffixProbeDistance;
    private BiPredicate<BlockPos, BlockPos> directCertifier;

    public HierarchicalWaterRoutePlanner(WaterRouteWorld coarseWorld, WaterRouteWorld detailedWorld,
                                         BlockPos start, BlockPos goal, WaterRoutePolicy detailedPolicy) {
        this(coarseWorld, detailedWorld, start, goal, detailedPolicy, UnaryOperator.identity());
    }

    HierarchicalWaterRoutePlanner(WaterRouteWorld coarseWorld, WaterRouteWorld detailedWorld,
                                   BlockPos start, BlockPos goal, WaterRoutePolicy detailedPolicy,
                                   UnaryOperator<List<BlockPos>> finishSegment) {
        this(coarseWorld, detailedWorld, start, goal, detailedPolicy, finishSegment, System::nanoTime);
    }

    /** Injectable monotonic clock for deterministic slow-terrain and cap regressions. */
    HierarchicalWaterRoutePlanner(WaterRouteWorld coarseWorld, WaterRouteWorld detailedWorld,
                                   BlockPos start, BlockPos goal, WaterRoutePolicy detailedPolicy,
                                   UnaryOperator<List<BlockPos>> finishSegment, LongSupplier clock) {
        this.clock = clock;
        this.taskStarted = clock.getAsLong();
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

    /**
     * Guidance already planned by {@link GlobalWaterGridPlanner}: open-water legs (verified with coastal clearance
     * against the native land mask) are appended as-is; the rest go through the normal detailed search below.
     * The whole route is still verified by the post-processor before it is published.
     */
    static HierarchicalWaterRoutePlanner withGuidance(WaterRouteWorld detailedWorld, BlockPos start, BlockPos goal,
                                                      WaterRoutePolicy detailedPolicy, UnaryOperator<List<BlockPos>> finishSegment,
                                                      List<GlobalWaterGridPlanner.Leg> legs) {
        return withGuidance(detailedWorld, start, goal, detailedPolicy, finishSegment, legs, null);
    }

    static HierarchicalWaterRoutePlanner withGuidance(WaterRouteWorld detailedWorld, BlockPos start, BlockPos goal,
                                                      WaterRoutePolicy detailedPolicy, UnaryOperator<List<BlockPos>> finishSegment,
                                                      List<GlobalWaterGridPlanner.Leg> legs,
                                                      BiPredicate<BlockPos, BlockPos> directCertifier) {
        return withGuidance(detailedWorld, start, goal, detailedPolicy, finishSegment, legs, directCertifier, System::nanoTime);
    }

    static HierarchicalWaterRoutePlanner withGuidance(WaterRouteWorld detailedWorld, BlockPos start, BlockPos goal,
                                                      WaterRoutePolicy detailedPolicy, UnaryOperator<List<BlockPos>> finishSegment,
                                                      List<GlobalWaterGridPlanner.Leg> legs,
                                                      BiPredicate<BlockPos, BlockPos> directCertifier, LongSupplier clock) {
        HierarchicalWaterRoutePlanner planner = new HierarchicalWaterRoutePlanner(detailedWorld, detailedWorld, start, goal,
                detailedPolicy, finishSegment, clock);
        planner.directCertifier = directCertifier;
        planner.coarse = false;
        List<BlockPos> guidance = new ArrayList<>();
        for (GlobalWaterGridPlanner.Leg leg : legs) {
            planner.segments.add(new Segment(leg.from(), leg.to(), leg.direct()));
            if (guidance.isEmpty()) guidance.add(leg.from());
            guidance.add(leg.to());
        }
        planner.coarseGuidance = List.copyOf(guidance);
        if (planner.segments.isEmpty()) planner.fail(WaterRouteFailureReason.NO_WATER_PATH);
        return planner;
    }

    /** One bounded search slice. No unfinished route is published; the caller may poll WAITING and cancel at any time. */
    public Status step(int nodeBudget) {
        if (status == Status.SUCCESS || status == Status.FAILED || status == Status.CANCELLED || nodeBudget <= 0) return status;
        if (WaterRouteCancellation.cancelled()) { cancel(); return status; }
        if (Math.max(0, clock.getAsLong() - taskStarted) >= MAX_TASK_MILLIS * 1_000_000L) {
            fail(WaterRouteFailureReason.TIMEOUT);
            LOGGER.warn("[WaterPath] 全任务硬时限到达 {}ms; {}", MAX_TASK_MILLIS, budgetDiagnostic());
            return status;
        }
        status = Status.RUNNING;
        TrackingWorld world = coarse ? coarseWorld : detailedWorld;
        WaterRoutePolicy policy = coarse ? coarsePolicy : detailWithStep(detailStep);
        if (stageBudget == null) {
            stageBudget = RouteStageBudget.adaptive((long) policy.timeoutTicks() * 50L, 60_000L, clock);
            searchProgress = null;
            nextBudgetLog = loggedGrant = 0;
        }
        WaterRouteFailureReason expired = stageBudget.advance(false);
        if (expired != WaterRouteFailureReason.NONE) { fail(expired); return status; }
        if (search == null) {
            if (coarse && coarseExpandedNodes >= MAX_COARSE_NODES) { fail(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED); return status; }
            if (!coarse) {
                if (!prepareDetailedStart(policy) || !prepareDetailedEndpoint(policy)) return status;
                // A mask-certified edge still needs an actual-water handoff. Checking before
                // append prevents an artificial land anchor becoming the next search's start.
                Segment leg = segments.get(completedSegments);
                if (leg.direct()) {
                    appendTracked(List.of(leg.from(), leg.to()), true);
                    completedSegments++;
                    if (completedSegments == segments.size()) {
                        result = List.copyOf(accumulated);
                        resultDirect = List.copyOf(edgeDirect);
                        status = Status.SUCCESS;
                    }
                    stageBudget = null;
                    searchProgress = null;
                    detailStep = detailedPolicy.stepSize();
                    detailExpandedNodes = 0;
                    return status;
                }
            }
            world.unknown = false;
            BlockPos a = coarse ? start : segments.get(completedSegments).from();
            BlockPos b = coarse ? goal : segments.get(completedSegments).to();
            if (coarse) policy = coarseWithStep(coarsePolicy.stepSize(), MAX_COARSE_NODES - coarseExpandedNodes);
            if (searchProgress == null)
                searchProgress = new RouteSearchProgress(a.getX(), a.getZ(), b.getX(), b.getZ());
            search = new WaterRoutePathfinder(world, a, b, policy,
                    coarse ? Math.min(16, Math.max(2, policy.stepSize() / 4)) : 1, this::expandedNode);

        }
        world.unknown = false;
        int before = search.expandedNodes();
        WaterRoutePathfinder.Status current = search.step(Math.min(MAX_ADVANCE_NODES, nodeBudget), 0);
        expandedNodes += search.expandedNodes() - before;
        if (coarse) coarseExpandedNodes += search.expandedNodes() - before;
        else detailExpandedNodes += search.expandedNodes() - before;
        stageBudget.completedExpansions(coarse ? coarseExpandedNodes : detailExpandedNodes);
        logBudget(false);
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
                appendTracked(finished, false);
                completedSegments++;
                if (completedSegments == segments.size()) {
                    result = List.copyOf(accumulated);
                    resultDirect = List.copyOf(edgeDirect);
                    status = Status.SUCCESS;
                }
            }
            search = null;
            stageBudget = null;
            searchProgress = null;
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
        edgeDirect.clear();
        segments.clear();
        result = List.of();
    }

    public List<BlockPos> path() { return status == Status.SUCCESS ? result : List.of(); }
    /** Per edge of {@link #path()}: true when it is an unmodified direct (open-water) guidance leg. */
    public List<Boolean> directEdges() { return status == Status.SUCCESS ? resultDirect : List.of(); }

    /** Compact within one certification class; retain the boundary between direct and detailed edges. */
    private void appendTracked(List<BlockPos> incoming, boolean direct) {
        for (BlockPos point : incoming) {
            if (!accumulated.isEmpty() && accumulated.getLast().equals(point)) continue;
            boolean flag = direct;
            while (accumulated.size() >= 2 && collinearForward(accumulated.get(accumulated.size() - 2), accumulated.getLast(), point)) {
                if (edgeDirect.getLast() != flag) break;
                accumulated.removeLast();
                edgeDirect.removeLast();
            }
            if (!accumulated.isEmpty()) edgeDirect.add(flag);
            accumulated.add(point);
        }
    }

    private static boolean collinearForward(BlockPos a, BlockPos b, BlockPos point) {
        long abx = (long) b.getX() - a.getX(), abz = (long) b.getZ() - a.getZ();
        long bcx = (long) point.getX() - b.getX(), bcz = (long) point.getZ() - b.getZ();
        return a.getY() == b.getY() && b.getY() == point.getY() && abx * bcz == abz * bcx && abx * bcx + abz * bcz > 0;
    }
    public Progress progress() { return new Progress(status, completedSegments, segments.size(), expandedNodes); }
    public WaterRouteFailureReason failureReason() { return failure; }
    public int currentStepSize() { return coarse ? coarsePolicy.stepSize() : detailStep; }
    public int pendingEdges() { return search == null ? 0 : search.pendingEdges(); }
    public boolean isCoarseStage() { return coarse; }
    /** Guidance only, never a sailing route until all detailed segments are verified. */
    public List<BlockPos> coarseGuidance() { return coarseGuidance; }
    public int skippedGuidanceAnchors() { return skippedGuidanceAnchors; }

    private boolean prepareDetailedStart(WaterRoutePolicy policy) {
        if (detailedStartValidated) return true;
        WaterColumn column = detailedWorld.sample(start.getX(), start.getZ(), policy);
        if (column == null) return awaitDetailedTerrain();
        if (!column.passable() || !start.equals(column.surfacePos())
                || !segments.get(completedSegments).from().equals(start)) {
            LOGGER.warn("[WaterPath] 真实起点不能验证 start={} actual={} diagnostic={}", start, column,
                    detailedWorld.sampleDiagnostic(start.getX(), start.getZ(), policy));
            fail(WaterRouteFailureReason.NO_WATER_PATH);
            return false;
        }
        detailedStartValidated = true;
        return true;
    }

    private boolean awaitDetailedTerrain() {
        WaterRouteFailureReason expired = stageBudget.advance(true);
        if (expired != WaterRouteFailureReason.NONE) fail(expired);
        else status = Status.WAITING;
        return false;
    }

    /**
     * A LOD waypoint is a hint, not a mandatory docking position. Native LOD and
     * actual generator columns differ at some shores. Never force a fine search
     * to end on an intermediate hint which precise 2x2 sampling proves is land.
     * Merge that hint into the following segment, preserving the already verified
     * starting point. Real user endpoints are never moved or removed. Unknown data
     * suspends this preparation; it is NOT interpreted as a bad hint or as water.
     * A long direct leg is cut only when its handoff needs repair; healthy ocean
     * legs keep their fast path. Each poll samples at most 32 hints/cutpoints.
     */
    private boolean prepareDetailedEndpoint(WaterRoutePolicy policy) {
        for (int checks = 0; checks < 32; checks++) {
            WaterRouteCancellation.check();
            Segment segment = segments.get(completedSegments);
            if (prefixProbeSegment == segment) {
                if (prefixProbeDistance <= 0) {
                    // No usable prefix anchor was found on the certified line. The existing
                    // bounded detailed search may still detour, but no prefix is invented.
                    segments.set(completedSegments, new Segment(segment.from(), segment.to(), false));
                    prefixProbeSegment = null;
                    continue;
                }
                RepairCut candidate = repairCut(segment.to(), segment.from(), prefixProbeDistance, segment.from().getY());
                if (candidate == null) { prefixProbeDistance = 0; continue; }
                BlockPos cut = candidate.point();
                WaterColumn atCut = detailedWorld.sample(cut.getX(), cut.getZ(), policy);
                if (atCut == null) return awaitDetailedTerrain();
                if (atCut.passable() && cut.equals(atCut.surfacePos()) && !cut.equals(segment.from())) {
                    Boolean certified = certifyRepairCut(segment.from(), cut, candidate.inherited());
                    if (certified == null) return false;
                    if (certified) {
                        segments.set(completedSegments, new Segment(segment.from(), cut, true));
                        segments.add(completedSegments + 1, new Segment(cut, segment.to(), false));
                        prefixProbeSegment = null;
                        return true;
                    }
                    // A changed native line may be long. Try at most one such certificate
                    // per slice; cheap actual-water cutpoint probes keep their 32-check cap.
                    prefixProbeDistance -= DETAIL_SEGMENT_BLOCKS / 32.0;
                    return false;
                }
                prefixProbeDistance -= DETAIL_SEGMENT_BLOCKS / 32.0;
                continue;
            }
            if (suffixProbeSegment == segment) {
                Segment next = segments.get(completedSegments + 1);
                if (suffixProbeDistance <= 0) {
                    mergeFollowingSegment(segment);
                    suffixProbeSegment = null;
                    continue;
                }
                RepairCut candidate = repairCut(next.from(), next.to(), suffixProbeDistance, segment.from().getY());
                if (candidate == null) { suffixProbeDistance = 0; continue; }
                BlockPos cut = candidate.point();
                WaterColumn atCut = detailedWorld.sample(cut.getX(), cut.getZ(), policy);
                if (atCut == null) return awaitDetailedTerrain();
                if (atCut.passable() && cut.equals(atCut.surfacePos()) && !cut.equals(next.to())) {
                    Boolean certified = certifyRepairCut(cut, next.to(), candidate.inherited());
                    if (certified == null) return false;
                    if (certified) {
                        segments.set(completedSegments, new Segment(segment.from(), cut, false));
                        segments.set(completedSegments + 1, new Segment(cut, next.to(), true));
                        skippedGuidanceAnchors++;
                        suffixProbeSegment = null;
                        return true;
                    }
                    suffixProbeDistance -= DETAIL_SEGMENT_BLOCKS / 32.0;
                    return false;
                }
                suffixProbeDistance -= DETAIL_SEGMENT_BLOCKS / 32.0;
                continue;
            }
            BlockPos hint = segment.to();
            WaterColumn column = detailedWorld.sample(hint.getX(), hint.getZ(), policy);
            if (column == null) return awaitDetailedTerrain();
            boolean finalEndpoint = completedSegments == segments.size() - 1;
            boolean sameWaterHeight = column.passable() && column.surfacePos() != null
                    && column.surfacePos().getY() == segment.from().getY();
            if (finalEndpoint && (!sameWaterHeight || !goal.equals(column.surfacePos()))) {
                LOGGER.warn("[WaterPath] 真实终点不能验证 goal={} actual={} diagnostic={}", goal, column,
                        detailedWorld.sampleDiagnostic(hint.getX(), hint.getZ(), policy));
                fail(WaterRouteFailureReason.NO_WATER_PATH);
                return false;
            }
            if (!finalEndpoint && segment.direct() && (!sameWaterHeight || !hint.equals(column.surfacePos()))
                    && Math.hypot((double) hint.getX() - segment.from().getX(),
                    (double) hint.getZ() - segment.from().getZ()) > DETAIL_SEGMENT_BLOCKS) {
                prefixProbeSegment = segment;
                prefixProbeDistance = DETAIL_SEGMENT_BLOCKS;
                continue;
            }
            if (sameWaterHeight) {
                BlockPos exact = column.surfacePos();
                if (!exact.equals(hint)) {
                    segments.set(completedSegments, new Segment(segment.from(), exact, false));
                    if (!finalEndpoint) {
                        Segment next = segments.get(completedSegments + 1);
                        segments.set(completedSegments + 1, new Segment(exact, next.to(), false));
                    }
                }
                return true;
            }
            LOGGER.info("[WaterPath] 跳过不可用中间引导点 hint={} actual={} from={} direct={} diagnostic={}",
                    hint, column, segment.from(), segment.direct(),
                    detailedWorld.sampleDiagnostic(hint.getX(), hint.getZ(), policy));
            Segment next = segments.get(completedSegments + 1);
            if (next.direct() && Math.hypot((double) next.to().getX() - next.from().getX(),
                    (double) next.to().getZ() - next.from().getZ()) > DETAIL_SEGMENT_BLOCKS) {
                suffixProbeSegment = segment;
                suffixProbeDistance = DETAIL_SEGMENT_BLOCKS;
                continue;
            }
            mergeFollowingSegment(segment);
        }
        return false; // RUNNING: bounded preparation continues on the next slice.
    }

    private void mergeFollowingSegment(Segment segment) {
        Segment next = segments.remove(completedSegments + 1);
        // The merged segment is still searched at full precision and finishSegment-verified.
        segments.set(completedSegments, new Segment(segment.from(), next.to(), false));
        skippedGuidanceAnchors++;
    }

    private record RepairCut(BlockPos point, boolean inherited) { }

    /** Prefer an exact integer subsegment: rounding a diagonal can change its certified hull cells. */
    private RepairCut repairCut(BlockPos anchor, BlockPos other, double distance, int height) {
        long dx = (long) other.getX() - anchor.getX(), dz = (long) other.getZ() - anchor.getZ();
        long divisor = gcd(Math.abs(dx), Math.abs(dz));
        if (divisor == 0) return null;
        long ux = dx / divisor, uz = dz / divisor;
        long hops = Math.min(divisor - 1, (long) Math.floor(distance / Math.hypot(ux, uz)));
        if (hops > 0) return new RepairCut(new BlockPos((int) (anchor.getX() + ux * hops), height,
                (int) (anchor.getZ() + uz * hops)), true);
        if (directCertifier == null) return null;
        double fraction = Math.min(1.0, distance / Math.hypot(dx, dz));
        BlockPos cut = new BlockPos((int) Math.round(anchor.getX() + dx * fraction), height,
                (int) Math.round(anchor.getZ() + dz * fraction));
        return cut.getX() == anchor.getX() && cut.getZ() == anchor.getZ() ? null : new RepairCut(cut, false);
    }

    private static long gcd(long a, long b) {
        while (b != 0) { long remainder = a % b; a = b; b = remainder; }
        return a;
    }

    /** Unknown native mask data suspends a changed edge, and never certifies it as open water. */
    private Boolean certifyRepairCut(BlockPos a, BlockPos b, boolean inherited) {
        if (inherited) return true;
        final boolean certified;
        try { certified = directCertifier != null && directCertifier.test(a, b); }
        catch (TerrainNotReadyException waiting) { awaitDetailedTerrain(); return null; }
        WaterRouteFailureReason expired = stageBudget.advance(false);
        if (Math.max(0, clock.getAsLong() - taskStarted) >= MAX_TASK_MILLIS * 1_000_000L)
            expired = WaterRouteFailureReason.TIMEOUT;
        if (expired != WaterRouteFailureReason.NONE) { fail(expired); return null; }
        return certified;
    }

    private WaterRouteFailureReason expandedNode(boolean forward, BlockPos point) {
        // Evaluate the old deadline first: a result arriving after it cannot resurrect work.
        WaterRouteFailureReason expired = stageBudget.advance(false);
        if (expired != WaterRouteFailureReason.NONE) return expired;
        searchProgress.expanded(forward, point.getX(), point.getZ());
        stageBudget.reward(searchProgress.snapshot());
        return stageBudget.advance(false);
    }

    public String budgetDiagnostic() {
        String budget = stageBudget == null ? "stage_complete" : stageBudget.diagnostic();
        if (searchProgress == null) return budget;
        var p = searchProgress.snapshot();
        return budget + " bestForwardRemaining=" + Math.round(p.bestForwardRemaining())
                + " bestBackwardRemaining=" + Math.round(p.bestBackwardRemaining())
                + " approachBlocks=" + Math.round(p.approachBlocks())
                + " approachGrantMs=" + p.approachRewardMillis()
                + " detourGrantMs=" + p.detourRewardMillis();
    }

    public String budgetSummary() {
        if (stageBudget == null) return "阶段完成";
        long taskLeft = Math.max(0, MAX_TASK_MILLIS - Math.max(0, clock.getAsLong() - taskStarted) / 1_000_000L);
        return "奖励+" + stageBudget.awardedMillis() / 1000 + "s · 余量"
                + Math.min(taskLeft, stageBudget.remainingMillis()) / 1000 + "s";
    }

    /** Keeps the existing progress packet format and the owner's recoverable task queue. */
    public static String withBudget(String stage, HierarchicalWaterRoutePlanner planner) {
        return stage + " · " + planner.budgetSummary();
    }

    private void logBudget(boolean force) {
        if (stageBudget == null) return;
        long now = clock.getAsLong();
        if (force || now >= nextBudgetLog || stageBudget.awardedMillis() >= loggedGrant + 8_000L) {
            LOGGER.info("[WaterPath] 推进奖励预算 stage={} step={} nodes={} {}",
                    coarse ? "COARSE" : "DETAIL", currentStepSize(), expandedNodes, budgetDiagnostic());
            nextBudgetLog = now + 10_000_000_000L;
            loggedGrant = stageBudget.awardedMillis();
        }
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
        logBudget(true);
        status = Status.FAILED;
        failure = reason;
        search = null;
        accumulated.clear();
        edgeDirect.clear();
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
                if (!next.equals(previous)) segments.add(new Segment(previous, next, false));
                previous = next;
            }
        }
        if (segments.isEmpty()) segments.add(new Segment(start, goal, false));
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

    private record Segment(BlockPos from, BlockPos to, boolean direct) { }

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
        @Override public String sampleDiagnostic(int x, int z, WaterRoutePolicy policy) {
            return delegate.sampleDiagnostic(x, z, policy);
        }
    }
}
