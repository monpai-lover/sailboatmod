package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import java.util.concurrent.CancellationException;

import java.util.ArrayList;
import java.util.List;

public class RoadPlannerAutoCompleteService {
    private final PathfinderRunner pathfinderRunner;
    private final SegmentClassifier segmentClassifier;
    private final RoadPlannerBridgeRuleService.LandProbe landProbe;
    private final TerrainSampler terrainSampler;
    private final boolean terrainAware;
    static final int MAX_OUTPUT_NODES = 16_384;
    private java.util.function.Supplier<String> planningNote = () -> "";

    RoadPlannerAutoCompleteService withPlanningNote(java.util.function.Supplier<String> note) {
        planningNote = java.util.Objects.requireNonNull(note);
        return this;
    }

    public RoadPlannerAutoCompleteService() {
        this(null, null);
    }

    public RoadPlannerAutoCompleteService(PathfinderRunner pathfinderRunner) {
        this(pathfinderRunner, null);
    }

    public RoadPlannerAutoCompleteService(PathfinderRunner pathfinderRunner, SegmentClassifier segmentClassifier) {
        this(pathfinderRunner, segmentClassifier, null);
    }

    public RoadPlannerAutoCompleteService(PathfinderRunner pathfinderRunner,
                                          SegmentClassifier segmentClassifier,
                                          RoadPlannerBridgeRuleService.LandProbe landProbe) {
        this(pathfinderRunner, segmentClassifier, landProbe, null);
    }

    public RoadPlannerAutoCompleteService(PathfinderRunner pathfinderRunner,
                                          SegmentClassifier segmentClassifier,
                                          RoadPlannerBridgeRuleService.LandProbe landProbe,
                                          TerrainSampler terrainSampler) {
        this.pathfinderRunner = pathfinderRunner;
        this.segmentClassifier = segmentClassifier;
        this.terrainAware = landProbe != null;
        this.landProbe = landProbe == null ? (x, z) -> true : landProbe;
        this.terrainSampler = terrainSampler == null ? nodes -> List.of() : terrainSampler;
    }

    public RoadPlannerAutoCompleteResult complete(BlockPos start,
                                                  BlockPos destination,
                                                  List<BlockPos> manualNodes,
                                                  int spacingBlocks) {
        RoadSearchBudget.check();
        if (manualNodes != null && manualNodes.size() > 1024)
            return RoadPlannerAutoCompleteResult.failure("手工节点过多，请分段规划");
        if (start == null || destination == null) {
            return RoadPlannerAutoCompleteResult.failure("缺少起点或目的地 town");
        }
        BlockPos from = manualNodes != null && !manualNodes.isEmpty()
                ? manualNodes.get(manualNodes.size() - 1)
                : start;
        int spacing = Math.max(4, spacingBlocks);
        List<BlockPos> suffixNodes = runPathfinder(from, destination);
        boolean usedFallback = false;
        RoadSearchBudget.check();
        if (suffixNodes.isEmpty()) {
            if (pathfinderRunner != null)
                return RoadPlannerAutoCompleteResult.failure("道路寻路失败或超时，未生成直线兜底");
            suffixNodes = interpolateRoadWeaverStyle(from, destination, spacing);
            usedFallback = true;
        }
        if (suffixNodes.size() < 2) {
            return RoadPlannerAutoCompleteResult.failure("自动寻路失败");
        }
        if (suffixNodes.size() > 65_536)
            return RoadPlannerAutoCompleteResult.failure("道路节点过多，请分段规划");
        int rawCount = suffixNodes.size();
        double length = 0;
        for (int i = 1; i < suffixNodes.size(); i++) {
            RoadSearchBudget.check();
            BlockPos a = suffixNodes.get(i-1), b = suffixNodes.get(i);
            length += Math.hypot(b.getX()-(double)a.getX(), b.getZ()-(double)a.getZ());
        }
        if (length > 1_000_000) return RoadPlannerAutoCompleteResult.failure("道路过长，请分段规划");
        spacing = Math.max(spacing, (int)Math.ceil(length / (MAX_OUTPUT_NODES / 2.0)));
        List<BlockPos> rawNodes = suffixNodes;
        // 关键修复:A* 是 8 邻网格寻路,接近水平/对角方向时在「对角格+正交格」间交替 → 规则 W 锯齿。
        // 这些节点就是预览青色节点 + 实际建路中心线的源头。必须在「分段(classifySegments)之前」对 A* 节点
        // 做样条平滑——分段在平滑后重新分类,segmentType 不会错乱;预览与建路都用同一条平滑节点序列。
        suffixNodes = com.monpai.sailboatmod.route.PathSmoother.smooth2DInterpolatedY(suffixNodes, spacing);
        RoadSearchBudget.check();
        // Do not turn a dry detour into a new sea crossing just to round grid corners.
        // Existing river/bridge runs remain allowed. This is a 4-block preview check,
        // not building-time support/width validation.
        if (terrainAware && longestWaterRun(suffixNodes) > longestWaterRun(rawNodes) + 8)
            suffixNodes = rawNodes;
        org.slf4j.LoggerFactory.getLogger("RoadPlannerAuto").info(
                "[RoadPlan] complete: runner={} 兜底={} 原始节点={} 平滑后={} spacing={} 首点={} 尾点={}",
                pathfinderRunner == null ? "null" : "有", usedFallback, rawCount, suffixNodes.size(), spacing,
                suffixNodes.isEmpty() ? "-" : suffixNodes.get(0),
                suffixNodes.isEmpty() ? "-" : suffixNodes.get(suffixNodes.size() - 1));
        if (suffixNodes.size() < 2) {
            return RoadPlannerAutoCompleteResult.failure("自动寻路失败");
        }
        List<BlockPos> mergedNodes = mergeManualPrefix(manualNodes, suffixNodes);
        if (mergedNodes.size() > MAX_OUTPUT_NODES)
            return RoadPlannerAutoCompleteResult.failure("道路节点过多，请分段规划");
        List<RoadPlannerSegmentType> segmentTypes = classifySegments(mergedNodes);
        RoadPlannerBridgeSegmentNormalizer.Result normalized = RoadPlannerBridgeSegmentNormalizer.normalize(mergedNodes, segmentTypes, landProbe);
        return new RoadPlannerAutoCompleteResult(
                true,
                normalized.nodes(),
                normalized.segmentTypes(),
                "自动补全完成: " + normalized.nodes().size() + " 节点" + planningNote.get(),
                sampleTerrain(normalized.nodes()));
    }

    private double longestWaterRun(List<BlockPos> path) {
        double run = 0, longest = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i-1), b = path.get(i);
            double d = Math.hypot(b.getX()-(double)a.getX(), b.getZ()-(double)a.getZ());
            int n = Math.max(1, (int)Math.ceil(d/4));
            for (int k = 1; k <= n; k++) {
                RoadSearchBudget.check();
                int x = (int)Math.round(a.getX() + (b.getX()-(double)a.getX())*k/n);
                int z = (int)Math.round(a.getZ() + (b.getZ()-(double)a.getZ())*k/n);
                run = landProbe.isLand(x,z) ? 0 : run + d/n;
                longest = Math.max(longest, run);
            }
        }
        return longest;
    }

    private List<RoadPlannerTerrainSample> sampleTerrain(List<BlockPos> nodes) {
        try {
            List<RoadPlannerTerrainSample> samples = terrainSampler.sample(nodes);
            return samples == null ? List.of() : List.copyOf(samples);
        } catch (RoadSearchBudget.DeadlineExceeded | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<BlockPos> mergeManualPrefix(List<BlockPos> manualNodes, List<BlockPos> suffixNodes) {
        if (manualNodes == null || manualNodes.isEmpty()) {
            return suffixNodes;
        }
        List<BlockPos> merged = new ArrayList<>();
        for (BlockPos node : manualNodes) {
            addDistinct(merged, node);
        }
        for (BlockPos node : suffixNodes) {
            addDistinct(merged, node);
        }
        return List.copyOf(merged);
    }

    private void addDistinct(List<BlockPos> nodes, BlockPos node) {
        if (node != null && (nodes.isEmpty() || !nodes.get(nodes.size() - 1).equals(node))) {
            nodes.add(node.immutable());
        }
    }

    private List<BlockPos> runPathfinder(BlockPos from, BlockPos destination) {
        if (pathfinderRunner == null) {
            return List.of();
        }
        List<BlockPos> path = pathfinderRunner.findPath(from, destination);
        return path == null ? List.of() : path.stream().map(BlockPos::immutable).toList();
    }

    public List<BlockPos> runPathfinderOnly(BlockPos from, BlockPos destination) {
        return runPathfinder(from, destination);
    }

    private List<RoadPlannerSegmentType> classifySegments(List<BlockPos> nodes) {
        if (segmentClassifier != null) {
            List<RoadPlannerSegmentType> terrainTypes = segmentClassifier.classify(nodes);
            if (terrainTypes != null && terrainTypes.size() == nodes.size() - 1) {
                return terrainTypes;
            }
        }
        List<RoadPlannerSegmentType> segmentTypes = new ArrayList<>();
        for (int index = 1; index < nodes.size(); index++) {
            RoadSearchBudget.check();
            segmentTypes.add(classifySegment(nodes.get(index - 1), nodes.get(index)));
        }
        return segmentTypes;
    }

    private List<BlockPos> interpolateRoadWeaverStyle(BlockPos from, BlockPos destination, int spacing) {
        double dx = destination.getX() - from.getX();
        double dy = destination.getY() - from.getY();
        double dz = destination.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.min(MAX_OUTPUT_NODES / 2, Math.max(1, (int) Math.ceil(distance / spacing)));
        List<BlockPos> nodes = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            RoadSearchBudget.check();
            double t = step / (double) steps;
            double smooth = t * t * (3.0D - 2.0D * t);
            int x = (int) Math.round(from.getX() + dx * smooth);
            int y = (int) Math.round(from.getY() + dy * smooth);
            int z = (int) Math.round(from.getZ() + dz * smooth);
            addDistinct(nodes, new BlockPos(x, y, z));
        }
        addDistinct(nodes, destination);
        return List.copyOf(nodes);
    }

    private RoadPlannerSegmentType classifySegment(BlockPos from, BlockPos to) {
        int horizontal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        boolean fromLand = landProbe.isLand(from.getX(), from.getZ());
        boolean toLand = landProbe.isLand(to.getX(), to.getZ());
        if (!fromLand || !toLand) {
            return horizontal <= 24 ? RoadPlannerSegmentType.BRIDGE_SMALL : RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        int samples = Math.max(3, horizontal / 8);
        int waterCount = 0;
        for (int s = 1; s < samples; s++) {
            double t = s / (double) samples;
            int sx = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int sz = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            if (!landProbe.isLand(sx, sz)) {
                waterCount++;
            }
        }
        if (waterCount > samples / 2) {
            return horizontal <= 24 ? RoadPlannerSegmentType.BRIDGE_SMALL : RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        return RoadPlannerSegmentType.ROAD;
    }

    @FunctionalInterface
    public interface PathfinderRunner {
        List<BlockPos> findPath(BlockPos from, BlockPos destination);
    }

    @FunctionalInterface
    public interface SegmentClassifier {
        List<RoadPlannerSegmentType> classify(List<BlockPos> nodes);
    }

    @FunctionalInterface
    public interface TerrainSampler {
        List<RoadPlannerTerrainSample> sample(List<BlockPos> nodes);
    }
}
