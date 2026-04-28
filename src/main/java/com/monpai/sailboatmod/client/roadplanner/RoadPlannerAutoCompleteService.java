package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoadPlannerAutoCompleteService {
    private final PathfinderRunner pathfinderRunner;
    private final SegmentClassifier segmentClassifier;
    private final RoadPlannerBridgeRuleService.LandProbe landProbe;

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
        this.pathfinderRunner = pathfinderRunner;
        this.segmentClassifier = segmentClassifier;
        this.landProbe = landProbe == null ? (x, z) -> true : landProbe;
    }

    public RoadPlannerAutoCompleteResult complete(BlockPos start,
                                                  BlockPos destination,
                                                  List<BlockPos> manualNodes,
                                                  int spacingBlocks) {
        if (start == null || destination == null) {
            return RoadPlannerAutoCompleteResult.failure("缺少起点或目的地 town");
        }
        BlockPos from = manualNodes != null && !manualNodes.isEmpty()
                ? manualNodes.get(manualNodes.size() - 1)
                : start;
        int spacing = Math.max(4, spacingBlocks);
        List<BlockPos> suffixNodes = runPathfinder(from, destination);
        if (suffixNodes.isEmpty()) {
            suffixNodes = interpolateRoadWeaverStyle(from, destination, spacing);
        }
        if (suffixNodes.size() < 2) {
            return RoadPlannerAutoCompleteResult.failure("自动寻路失败");
        }
        List<BlockPos> mergedNodes = mergeManualPrefix(manualNodes, suffixNodes);
        List<RoadPlannerSegmentType> segmentTypes = classifySegments(mergedNodes);
        RoadPlannerBridgeSegmentNormalizer.Result normalized = RoadPlannerBridgeSegmentNormalizer.normalize(mergedNodes, segmentTypes, landProbe);
        return new RoadPlannerAutoCompleteResult(true, normalized.nodes(), normalized.segmentTypes(), "自动补全完成: " + normalized.nodes().size() + " 节点");
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
            segmentTypes.add(classifySegment(nodes.get(index - 1), nodes.get(index)));
        }
        return segmentTypes;
    }

    private List<BlockPos> interpolateRoadWeaverStyle(BlockPos from, BlockPos destination, int spacing) {
        double dx = destination.getX() - from.getX();
        double dy = destination.getY() - from.getY();
        double dz = destination.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / spacing));
        List<BlockPos> nodes = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
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
        int vertical = Math.abs(to.getY() - from.getY());
        if (vertical >= 10 || horizontal >= 96) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        if (!landProbe.isLand(from.getX(), from.getZ()) || !landProbe.isLand(to.getX(), to.getZ())) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        int midX = (from.getX() + to.getX()) / 2;
        int midZ = (from.getZ() + to.getZ()) / 2;
        if (!landProbe.isLand(midX, midZ)) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        if (vertical >= 4 || horizontal >= 48) {
            return RoadPlannerSegmentType.BRIDGE_SMALL;
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
}
