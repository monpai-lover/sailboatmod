package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class RoadSurfaceStepEmitter {
    private RoadSurfaceStepEmitter() {
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       int startOrder) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        for (int index = 0; index < centerline.size(); index++) {
            if (!isRoadIndex(spans, index)) {
                continue;
            }
            boolean ramp = isRamp(centerline, spans, index);
            BlockState surfaceState = ramp ? rampState(safeSettings, centerline, index) : safeSettings.surfaceState();
            BuildPhase surfacePhase = ramp ? BuildPhase.RAMP : BuildPhase.SURFACE;
            List<BlockPos> footprint = RoadFootprintPlanner.surfacePositions(centerline, index, safeSettings.width());
            for (BlockPos surfacePos : footprint) {
                for (int dy = 1; dy <= 4; dy++) {
                    steps.add(new BuildStep(order++, surfacePos.above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }
                int terrainY = RoadFootprintPlanner.interpolateTerrainY(surfacePos.getX(), surfacePos.getZ(), centerline);
                int bottomY = Math.min(terrainY, surfacePos.getY()) - 3;
                for (int y = surfacePos.getY() - 1; y >= bottomY; y--) {
                    BlockState foundation = y == bottomY ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                    steps.add(new BuildStep(order++, new BlockPos(surfacePos.getX(), y, surfacePos.getZ()), foundation, BuildPhase.FOUNDATION));
                }
                steps.add(new BuildStep(order++, surfacePos, surfaceState, surfacePhase));
            }
        }
        order = addStreetlights(steps, centerline, spans, safeSettings, order);
        return List.copyOf(steps);
    }

    private static int addStreetlights(List<BuildStep> steps,
                                       List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       int order) {
        if (!settings.streetlightsEnabled()) {
            return order;
        }
        int distance = 0;
        for (int index = 0; index < centerline.size(); index++) {
            if (!isRoadIndex(spans, index)) {
                continue;
            }
            boolean place = index == 0 || index == centerline.size() - 1 || distance >= 24;
            if (place) {
                BlockPos center = new BlockPos(centerline.get(index).pos().getX(), centerline.get(index).targetY(), centerline.get(index).pos().getZ());
                int dx = 0;
                int dz = 0;
                if (index + 1 < centerline.size()) {
                    dx = Integer.compare(centerline.get(index + 1).pos().getX() - center.getX(), 0);
                    dz = Integer.compare(centerline.get(index + 1).pos().getZ() - center.getZ(), 0);
                } else if (index > 0) {
                    dx = Integer.compare(center.getX() - centerline.get(index - 1).pos().getX(), 0);
                    dz = Integer.compare(center.getZ() - centerline.get(index - 1).pos().getZ(), 0);
                }
                if (dx == 0 && dz == 0) {
                    dx = 1;
                }
                int perpX = -dz;
                int perpZ = dx;
                BlockPos base = center.offset(perpX * (settings.width() / 2 + 1), 1, perpZ * (settings.width() / 2 + 1));
                steps.add(new BuildStep(order++, base, Blocks.OAK_FENCE.defaultBlockState(), BuildPhase.STREETLIGHT));
                steps.add(new BuildStep(order++, base.above(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), BuildPhase.STREETLIGHT));
                distance = 0;
            } else {
                distance++;
            }
        }
        return order;
    }

    private static boolean isRoadIndex(List<RoadSpan> spans, int index) {
        return spans == null || spans.stream().anyMatch(span -> span.type() == RoadSpanType.ROAD && span.contains(index));
    }

    private static boolean isRamp(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, int index) {
        if (!isRoadIndex(spans, index)) {
            return false;
        }
        int y = centerline.get(index).targetY();
        int prevY = index > 0 ? centerline.get(index - 1).targetY() : y;
        int nextY = index + 1 < centerline.size() ? centerline.get(index + 1).targetY() : y;
        return y != prevY || y != nextY;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, List<RoadCenterlinePoint> centerline, int index) {
        int y = centerline.get(index).targetY();
        int prevY = index > 0 ? centerline.get(index - 1).targetY() : y;
        int nextY = index + 1 < centerline.size() ? centerline.get(index + 1).targetY() : y;
        if (y > prevY || nextY < y) {
            return settings.slabBottomState();
        }
        return settings.slabTopState();
    }
}
