package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class BridgeStructureEmitter {
    private BridgeStructureEmitter() {
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       BridgeTemplateProvider templateProvider,
                                       int startOrder) {
        return emit(centerline, spans, settings, templateProvider, startOrder, null);
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       BridgeTemplateProvider templateProvider,
                                       int startOrder,
                                       RoadTerrainSampler terrainSampler) {
        if (centerline == null || centerline.isEmpty() || spans == null || spans.isEmpty()) {
            return List.of();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        BridgeTemplateProvider safeProvider = templateProvider == null ? BridgeTemplateProvider.empty() : templateProvider;
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        for (RoadSpan span : spans) {
            if (span.type() != RoadSpanType.BRIDGE) {
                continue;
            }
            List<RoadCenterlinePoint> bridgePoints = centerline.subList(span.startIndex(), span.endIndex() + 1);
            List<BuildStep> templateSteps = safeProvider.buildFromTemplate(bridgePoints, safeSettings, order);
            if (!templateSteps.isEmpty()) {
                steps.addAll(templateSteps);
                order += templateSteps.size();
                continue;
            }
            List<BuildStep> programmatic = emitProgrammaticBridge(bridgePoints, span, safeSettings, order, terrainSampler);
            steps.addAll(programmatic);
            order += programmatic.size();
        }
        return List.copyOf(steps);
    }

    private static List<BuildStep> emitProgrammaticBridge(List<RoadCenterlinePoint> points,
                                                          RoadSpan span,
                                                          RoadPlannerBuildSettings settings,
                                                          int startOrder,
                                                          RoadTerrainSampler terrainSampler) {
        if (points.size() < 2) {
            return List.of();
        }
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                points,
                span,
                terrainSampler,
                new BridgeConfig()
        );
        List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints = plan.points();
        List<RoadCenterlinePoint> bridgeProfile = plannedPoints.stream()
                .map(RoadPlannerBridgeGeometryPlanner.PlannedPoint::point)
                .toList();

        for (int index = 0; index < plannedPoints.size(); index++) {
            RoadPlannerBridgeGeometryPlanner.PlannedPoint planned = plannedPoints.get(index);
            RoadCenterlinePoint point = planned.point();
            int y = point.targetY();
            boolean ramp = planned.phase() == BuildPhase.RAMP;
            BlockState state = ramp ? rampState(settings, index) : settings.surfaceState();
            BuildPhase phase = planned.phase();
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            List<BlockPos> footprint = RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
            for (BlockPos surfacePos : footprint) {
                steps.add(new BuildStep(order++, surfacePos, state, phase));
            }
            steps.addAll(railings(bridgeProfile, index, center, settings, order));
            order = startOrder + steps.size();
        }
        for (RoadPlannerBridgeGeometryPlanner.Pier pier : plan.piers()) {
            for (int pierY = pier.bottomY(); pierY <= pier.topY(); pierY++) {
                steps.add(new BuildStep(order++, new BlockPos(pier.center().getX(), pierY, pier.center().getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
            }
        }
        return List.copyOf(steps);
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, int index) {
        return (index & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
    }

    private static List<BuildStep> railings(List<RoadCenterlinePoint> points,
                                            int index,
                                            BlockPos center,
                                            RoadPlannerBuildSettings settings,
                                            int startOrder) {
        BlockState rail = Blocks.OAK_FENCE.defaultBlockState();
        List<BlockPos> positions = RoadFootprintPlanner.railingPositions(points, index, center, settings.width());
        List<BuildStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new BuildStep(startOrder + steps.size(), pos, rail, BuildPhase.RAILING));
        }
        return List.copyOf(steps);
    }
}
