package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.roadplanner.structure.*;
import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.config.BridgeConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reuses the authoritative structure emitters, retaining global bridge profiles across local windows. */
public final class RoadPlannerStageCompiler {
    private final RoadPlannerBuildSettings settings;
    private final boolean requiresExplicitWater;
    private final List<BridgeGroup> bridges = new ArrayList<>();
    private final Map<BridgeGroup, BridgeReference> references = new HashMap<>();

    public RoadPlannerStageCompiler(RoadPlannerStagedPlan plan, RoadPlannerBuildSettings settings) {
        this.settings = settings;
        requiresExplicitWater = com.monpai.sailboatmod.client.roadplanner.RoadPlannerRouteExpander.requiresStreaming(plan.nodes());
        for (int leg = 0; leg < plan.types().size(); leg++) {
            if (!RoadSpanClassifier.isBridge(plan.types().get(leg))) continue;
            int first = leg;
            while (leg + 1 < plan.types().size() && RoadSpanClassifier.isBridge(plan.types().get(leg + 1))) leg++;
            bridges.add(new BridgeGroup(plan.nodes().subList(first, leg + 2), plan.types().get(first)));
        }
    }

    public RoadNodeExpansionResult expand(RoadPlannerStagedPlan.Stage stage, RoadTerrainSampler source, boolean prediction) {
        if (source == null) throw new IllegalStateException("Actual road terrain is unavailable");
        if (requiresExplicitWater) validateRoadSurface(stage, source);
        if (prediction) {
            for (RoadRouteSection section : RoadRouteSectionNormalizer.sections(stage.nodes(), stage.types())) {
                for (RoadCenterlinePoint point : RoadCenterlineBuilder.build(section, source)) {
                    if (!RoadSpanClassifier.isBridge(point.segmentType())) continue;
                    BridgeGroup group = closest(point.pos());
                    if (group == null) continue;
                    BridgeReference ref = references.computeIfAbsent(group, ignored -> new BridgeReference(
                            source.terrainY(group.nodes.get(0).getX(), group.nodes.get(0).getZ()),
                            source.terrainY(group.nodes.get(group.nodes.size() - 1).getX(), group.nodes.get(group.nodes.size() - 1).getZ())));
                    int water = source.waterSurfaceY(point.pos().getX(), point.pos().getZ());
                    if (water <= point.targetY() || source.isWater(point.pos().getX(), water, point.pos().getZ())) ref.water = Math.max(ref.water, water);
                }
            }
        } else {
            validateActualCore(stage, source);
        }
        RoadTerrainSampler contextual = new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return source.terrainY(x, z); }
            @Override public int waterSurfaceY(int x, int z) { return source.waterSurfaceY(x, z); }
            @Override public int oceanFloorY(int x, int z) { return source.oceanFloorY(x, z); }
            @Override public boolean isWater(int x, int y, int z) { return source.isWater(x, y, z); }
            @Override public BridgeWindow bridgeWindow(List<RoadCenterlinePoint> points) {
                if (points.isEmpty()) return null;
                BridgeGroup group = closest(points.get(points.size() / 2).pos());
                if (group == null || group.intervals <= 128) return null;
                BridgeReference ref = references.get(group);
                if (ref == null) throw new IllegalStateException("桥梁预测尚未完成，请重新预览道路。");
                int water = ref.water == Integer.MIN_VALUE ? Math.min(ref.entry, ref.exit) - 1 : ref.water;
                return new BridgeWindow(group.index(points.get(0).pos()), group.intervals,
                        Math.max(ref.entry, water), Math.max(ref.exit, water), water, group.type);
            }
        };
        RoadNodeExpansionResult expanded = RoadNodeStructureExpander.expand(stage.nodes(), stage.types(), settings,
                contextual, prediction ? RoadStructureMode.PREVIEW : RoadStructureMode.BUILD);
        var steps = expanded.buildSteps().stream().filter(step -> stage.ownsColumn(step.pos().getX(), step.pos().getZ())).toList();
        var preview = expanded.previewBlocks().stream().filter(block -> stage.ownsColumn(block.pos().getX(), block.pos().getZ())).toList();
        var centers = expanded.centerline().stream().filter(point -> stage.ownsColumn(point.pos().getX(), point.pos().getZ())).toList();
        return new RoadNodeExpansionResult(expanded.canonicalNodes(), expanded.canonicalSegmentTypes(), centers,
                RoadSpanClassifier.classify(centers), preview, steps, expanded.issues());
    }

    private static void validateRoadSurface(RoadPlannerStagedPlan.Stage stage, RoadTerrainSampler source) {
        for (RoadRouteSection section : RoadRouteSectionNormalizer.sections(stage.nodes(), stage.types())) {
            for (RoadCenterlinePoint point : RoadCenterlineBuilder.build(section, source)) {
                int x = point.pos().getX(), z = point.pos().getZ();
                if (point.segmentType() == RoadPlannerSegmentType.ROAD && stage.coreContains(x, z)
                        && source.isWater(x, point.terrainY() - 1, z)) {
                    throw new IllegalStateException("远距离道路当前路段包含水域，请把跨水路段设置为桥梁后重新预览。");
                }
            }
        }
    }

    private void validateActualCore(RoadPlannerStagedPlan.Stage stage, RoadTerrainSampler source) {
        if (bridges.isEmpty()) return;
        for (RoadRouteSection section : RoadRouteSectionNormalizer.sections(stage.nodes(), stage.types())) {
            for (RoadCenterlinePoint point : RoadCenterlineBuilder.build(section, source)) {
                int x = point.pos().getX(), z = point.pos().getZ();
                if (!RoadSpanClassifier.isBridge(point.segmentType()) || !stage.coreContains(x, z)) continue;
                BridgeGroup group = closest(point.pos());
                if (group == null || group.intervals <= 128) continue;
                BridgeReference ref = references.get(group);
                if (ref == null) throw changedBridge();
                int index = group.index(point.pos());
                int water = ref.water == Integer.MIN_VALUE ? Math.min(ref.entry, ref.exit) - 1 : ref.water;
                int actualWater = source.waterSurfaceY(x, z);
                if (source.isWater(x, actualWater, z) && actualWater > water) throw changedBridge();
                if (index <= 0 && point.terrainY() != ref.entry || index >= group.intervals && point.terrainY() != ref.exit) throw changedBridge();
                RoadSpan global = new RoadSpan(RoadSpanType.BRIDGE, 0, group.intervals, group.type);
                var planned = RoadPlannerBridgeGeometryPlanner.planWindow(List.of(point), global, source, new BridgeConfig(),
                        index, group.intervals, Math.max(ref.entry, water), Math.max(ref.exit, water), water);
                if (point.terrainY() > planned.points().get(0).point().targetY() + 1) throw changedBridge();
            }
        }
    }

    private static IllegalStateException changedBridge() {
        return new IllegalStateException("桥梁当前真实岸高或水位已超出预览方案，请取消施工并重新预览道路。");
    }

    private BridgeGroup closest(BlockPos point) {
        BridgeGroup selected = null;
        double best = Double.POSITIVE_INFINITY;
        for (BridgeGroup group : bridges) {
            double distance = group.nearest(point)[0];
            if (distance < best) { best = distance; selected = group; }
        }
        return best <= 1024 ? selected : null;
    }

    private static final class BridgeReference {
        final int entry, exit;
        int water = Integer.MIN_VALUE;
        BridgeReference(int entry, int exit) { this.entry = entry; this.exit = exit; }
    }

    private static final class BridgeGroup {
        final List<BlockPos> nodes;
        final int intervals;
        final RoadPlannerSegmentType type;
        BridgeGroup(List<BlockPos> nodes, RoadPlannerSegmentType type) {
            this.nodes = List.copyOf(nodes);
            this.type = type;
            long count = 0;
            for (int i = 1; i < nodes.size(); i++) count += Math.max(1, (long) Math.ceil(Math.hypot(
                    (double) nodes.get(i).getX() - nodes.get(i - 1).getX(), (double) nodes.get(i).getZ() - nodes.get(i - 1).getZ())));
            intervals = (int) Math.min(Integer.MAX_VALUE - 1L, count);
        }
        int index(BlockPos point) { return (int) Math.round(nearest(point)[1]); }
        double[] nearest(BlockPos point) {
            double best = Double.POSITIVE_INFINITY, at = 0, prefix = 0;
            for (int i = 1; i < nodes.size(); i++) {
                BlockPos a = nodes.get(i - 1), b = nodes.get(i);
                double dx = (double) b.getX() - a.getX(), dz = (double) b.getZ() - a.getZ();
                double squared = dx * dx + dz * dz;
                double raw = squared == 0 ? 0 : (((double) point.getX() - a.getX()) * dx + ((double) point.getZ() - a.getZ()) * dz) / squared;
                double t = Math.max(0, Math.min(1, raw));
                double ex = point.getX() - (a.getX() + dx * t), ez = point.getZ() - (a.getZ() + dz * t);
                double distance = ex * ex + ez * ez;
                double length = Math.max(1, Math.ceil(Math.sqrt(squared)));
                if (distance < best) { best = distance; at = prefix + raw * length; }
                prefix += length;
            }
            return new double[]{best, at};
        }
    }
}
