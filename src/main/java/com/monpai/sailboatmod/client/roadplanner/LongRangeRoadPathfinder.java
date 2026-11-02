package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 道路自动补全的远距离规划(超远两城测试:原单次 step=8 搜索在起伏地形上扩展满 8 万节点也到不了,
 * 期间每次请求在服务器主线程做 40 万次 getBaseHeight)。
 * <ol>
 *   <li>近距离(≤ {@link #DIRECT_LIMIT})照旧:一次 step=8 搜索。</li>
 *   <li>远距离:先用随距离放大的粗步长(≈距离/160,32~512 格)找走向,失败再放大一次;</li>
 *   <li>再把粗路按 ≈{@link #WINDOW_BLOCKS} 格切窗,每窗用 step=8 小预算精寻贴地形;某窗精寻不成或窗口时限到,
 *       该窗沿用粗路节点(下游样条平滑 + 建造时贴地)。</li>
 * </ol>
 * 每次搜索都有节点上限和内部截止检查；整个请求超时返回失败，不伪造完整路线。只在后台线程调用。
 */
public final class LongRangeRoadPathfinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadPlannerAuto");
    public static final int DIRECT_LIMIT = 2048;
    public static final int WINDOW_BLOCKS = 768;
    /** PotentialFieldPathfinder 用 max(5000, maxSteps*4) 作扩展上限:1250 → 每窗 5000 次扩展。 */
    private static final int WINDOW_MAX_STEPS = 1250;

    public record Result(List<BlockPos> path, int coarseStep, int windows, int refinedWindows, boolean deadlineHit) { }

    private final PathfindingConfig base;
    private final LongSupplier nanoClock;

    public LongRangeRoadPathfinder(PathfindingConfig base) {
        this(base, System::nanoTime);
    }

    LongRangeRoadPathfinder(PathfindingConfig base, LongSupplier nanoClock) {
        this.base = base;
        this.nanoClock = nanoClock;
    }

    public static int coarseStepFor(double distance) {
        int step = (int) Math.ceil(distance / 160.0 / 8.0) * 8;
        return Math.max(32, Math.min(512, step));
    }

    public Result findPath(BlockPos from, BlockPos to, TerrainSamplingCache cache, long budgetMillis) {
        try {
            return RoadSearchBudget.run(budgetMillis, nanoClock, () -> findWithinBudget(from, to, cache, budgetMillis));
        } catch (RoadSearchBudget.DeadlineExceeded expired) {
            return new Result(List.of(), coarseStepFor(Math.hypot(to.getX()-(double)from.getX(), to.getZ()-(double)from.getZ())), 0, 0, true);
        }
    }

    private Result findWithinBudget(BlockPos from, BlockPos to, TerrainSamplingCache cache, long budgetMillis) {
        RoadSearchBudget.check();
        long deadline = nanoClock.getAsLong() + Math.max(1, budgetMillis) * 1_000_000L;
        double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (distance <= DIRECT_LIMIT) {
            PathResult direct = pathfinder(base.getAStarStep(), base.getMaxSteps()).findPath(from, to, cache);
            return new Result(direct.success() ? direct.path() : List.of(), base.getAStarStep(), 1, direct.success() ? 1 : 0, false);
        }
        int coarseStep = coarseStepFor(distance);
        PathResult coarse = pathfinder(coarseStep, base.getMaxSteps()).findPath(from, to, cache);
        if (!coarse.success() && !interrupted() && nanoClock.getAsLong() < deadline) {
            coarseStep = Math.min(1024, coarseStep * 2);
            coarse = pathfinder(coarseStep, base.getMaxSteps()).findPath(from, to, cache);
        }
        if (!coarse.success() || coarse.path().size() < 2) {
            LOGGER.info("[RoadPlan] 远距离粗寻失败 distance={} step={} reason={}", Math.round(distance), coarseStep, coarse.failureReason());
            return new Result(List.of(), coarseStep, 0, 0, nanoClock.getAsLong() >= deadline);
        }
        List<BlockPos> guide = coarse.path();
        List<Integer> cuts = new ArrayList<>();
        cuts.add(0);
        double run = 0;
        for (int i = 1; i < guide.size(); i++) {
            run += Math.hypot(guide.get(i).getX() - guide.get(i - 1).getX(), guide.get(i).getZ() - guide.get(i - 1).getZ());
            if (run >= WINDOW_BLOCKS || i == guide.size() - 1) {
                cuts.add(i);
                run = 0;
            }
        }
        Pathfinder fine = pathfinder(base.getAStarStep(), WINDOW_MAX_STEPS);
        List<BlockPos> out = new ArrayList<>();
        int refined = 0;
        boolean deadlineHit = false;
        for (int w = 1; w < cuts.size(); w++) {
            RoadSearchBudget.check();
            List<BlockPos> window = guide.subList(cuts.get(w - 1), cuts.get(w) + 1);
            List<BlockPos> piece = window;
            {
                PathResult r;
                try {
                    r = RoadSearchBudget.run(1_500, nanoClock,
                            () -> fine.findPath(window.getFirst(), window.getLast(), cache));
                } catch (RoadSearchBudget.DeadlineExceeded expired) {
                    // Only a per-window timeout can keep its guide. A whole-request deadline
                    // or cancellation must abort, never be reported as a finished road.
                    RoadSearchBudget.check();
                    r = PathResult.failure("refinement window time budget");
                    deadlineHit = true;
                }
                if (r.success() && r.path().size() >= 2) {
                    piece = new ArrayList<>(r.path());
                    // The fine search stops within 3 steps of the window end; pin exact window endpoints.
                    piece.set(0, window.getFirst());
                    if (!sameXZ(piece.getLast(), window.getLast())) piece.add(window.getLast());
                    refined++;
                }
            }
            for (BlockPos p : piece) {
                if (out.isEmpty() || !sameXZ(out.getLast(), p)) out.add(p);
            }
        }
        LOGGER.info("[RoadPlan] 远距离补全 distance={} 粗步长={} 粗节点={} 窗口={} 精寻成功={} 截止={}",
                Math.round(distance), coarseStep, guide.size(), cuts.size() - 1, refined, deadlineHit);
        return new Result(List.copyOf(out), coarseStep, cuts.size() - 1, refined, deadlineHit);
    }

    private Pathfinder pathfinder(int step, int maxSteps) {
        PathfindingConfig config = new PathfindingConfig();
        config.setAlgorithm(base.getAlgorithm());
        config.setSamplingPrecision(base.getSamplingPrecision());
        config.setElevationWeight(base.getElevationWeight());
        config.setBiomeWeight(base.getBiomeWeight());
        config.setStabilityWeight(base.getStabilityWeight());
        config.setWaterDepthWeight(base.getWaterDepthWeight());
        config.setNearWaterCost(base.getNearWaterCost());
        config.setDeviationWeight(base.getDeviationWeight());
        config.setHeuristicWeight(base.getHeuristicWeight());
        config.setThreadPoolSize(base.getThreadPoolSize());
        config.setSegmentThreshold(base.getSegmentThreshold());
        config.setMaxSegments(base.getMaxSegments());
        config.setAStarStep(step);
        config.setMaxSteps(maxSteps);
        return PathfinderFactory.create(config);
    }

    private static boolean interrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }
}
