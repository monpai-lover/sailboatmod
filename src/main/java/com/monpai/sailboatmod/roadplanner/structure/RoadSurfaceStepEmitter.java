package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
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
            RoadCenterlinePoint point = centerline.get(index);
            BlockPos center = new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ());
            boolean ramp = isRamp(centerline, spans, index);
            BlockState surfaceState = ramp ? rampState(safeSettings, index) : safeSettings.surfaceState();
            BuildPhase surfacePhase = ramp ? BuildPhase.RAMP : BuildPhase.SURFACE;
            List<WeaverBuildCandidate> footprint = WeaverSegmentPaver.paveCenterline(List.of(center), safeSettings.width(), surfaceState);
            for (WeaverBuildCandidate candidate : footprint) {
                for (int dy = 1; dy <= 4; dy++) {
                    steps.add(new BuildStep(order++, candidate.pos().above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
                }
                int bottomY = Math.min(point.terrainY(), point.targetY()) - 3;
                for (int y = candidate.pos().getY() - 1; y >= bottomY; y--) {
                    BlockState foundation = y == bottomY ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                    steps.add(new BuildStep(order++, new BlockPos(candidate.pos().getX(), y, candidate.pos().getZ()), foundation, BuildPhase.FOUNDATION));
                }
                steps.add(new BuildStep(order++, candidate.pos(), candidate.state(), surfacePhase));
            }
        }
        return List.copyOf(steps);
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

    private static BlockState rampState(RoadPlannerBuildSettings settings, int index) {
        return (index & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
    }
}
