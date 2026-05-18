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
        List<List<BlockPos>> footprints = RoadBandRasterizer.surfacePositionsByIndex(bridgeProfile, settings.width());
        RoadTerrainSampler supportSampler = terrainSampler == null
                ? RoadTerrainSampler.flat(points.get(0).terrainY())
                : terrainSampler;

        for (RoadPlannerBridgeGeometryPlanner.Pier pier : plan.piers()) {
            for (int pierY = pier.bottomY(); pierY <= pier.topY(); pierY++) {
                steps.add(new BuildStep(order++, new BlockPos(pier.center().getX(), pierY, pier.center().getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
            }
        }

        for (int index = 0; index < plannedPoints.size(); index++) {
            RoadPlannerBridgeGeometryPlanner.PlannedPoint planned = plannedPoints.get(index);
            RoadCenterlinePoint point = planned.point();
            int y = point.targetY();
            boolean ramp = planned.phase() == BuildPhase.RAMP;
            BlockState state = ramp ? rampState(settings, plannedPoints, index) : settings.surfaceState();
            BuildPhase phase = planned.phase();
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            List<BlockPos> footprint = index < footprints.size()
                    ? footprints.get(index)
                    : RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
            for (BlockPos surfacePos : footprint) {
                if (phase == BuildPhase.RAMP && plan.profile().usesPiers()) {
                    order = addRampSupport(steps, surfacePos, order, supportSampler);
                }
                steps.add(new BuildStep(order++, surfacePos, state, phase));
                order = addOverheadClearance(steps, surfacePos, order);
            }
            steps.addAll(railings(bridgeProfile, index, center, settings, order));
            order = startOrder + steps.size();
        }
        return List.copyOf(steps);
    }

    private static int addRampSupport(List<BuildStep> steps,
                                      BlockPos surfacePos,
                                      int order,
                                      RoadTerrainSampler terrainSampler) {
        if (surfacePos == null) {
            return order;
        }
        int topY = surfacePos.getY() - 1;
        if (topY < 0) {
            return order;
        }
        int bottomY = terrainSampler == null
                ? topY
                : Math.min(topY, terrainSampler.oceanFloorY(surfacePos.getX(), surfacePos.getZ()));
        for (int y = bottomY; y <= topY; y++) {
            steps.add(new BuildStep(order++, new BlockPos(surfacePos.getX(), y, surfacePos.getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
        }
        return order;
    }

    private static int addOverheadClearance(List<BuildStep> steps, BlockPos surfacePos, int order) {
        for (int dy = 1; dy <= 4; dy++) {
            steps.add(new BuildStep(order++, surfacePos.above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
        }
        return order;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
        int start = index;
        while (start > 0 && points.get(start - 1).phase() == BuildPhase.RAMP) {
            start--;
        }
        int end = index;
        while (end + 1 < points.size() && points.get(end + 1).phase() == BuildPhase.RAMP) {
            end++;
        }
        boolean ascending = rampRunAscending(points, start, end);
        int localRampIndex = index - start;
        if (ascending) {
            return (localRampIndex & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
        }
        return (localRampIndex & 1) == 0 ? settings.slabTopState() : settings.slabBottomState();
    }

    private static boolean rampRunAscending(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int start, int end) {
        int startY = points.get(start).point().targetY();
        int endY = points.get(end).point().targetY();
        if (startY != endY) {
            return endY > startY;
        }
        int beforeY = start > 0 ? points.get(start - 1).point().targetY() : startY;
        int afterY = end + 1 < points.size() ? points.get(end + 1).point().targetY() : endY;
        return afterY >= beforeY;
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
