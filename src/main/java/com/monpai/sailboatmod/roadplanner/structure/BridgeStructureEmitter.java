package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class BridgeStructureEmitter {
    private static final int MAJOR_HEIGHT_BONUS = 5;
    private static final int SMALL_HEIGHT_BONUS = 3;
    private static final int PIER_INTERVAL = 4;

    private record BridgeProfile(List<RoadCenterlinePoint> points, int deckStartInclusive, int deckEndExclusive) {
    }

    private BridgeStructureEmitter() {
    }

    public static List<BuildStep> emit(List<RoadCenterlinePoint> centerline,
                                       List<RoadSpan> spans,
                                       RoadPlannerBuildSettings settings,
                                       BridgeTemplateProvider templateProvider,
                                       int startOrder) {
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
            List<BuildStep> programmatic = emitProgrammaticBridge(bridgePoints, span, safeSettings, order);
            steps.addAll(programmatic);
            order += programmatic.size();
        }
        return List.copyOf(steps);
    }

    private static List<BuildStep> emitProgrammaticBridge(List<RoadCenterlinePoint> points,
                                                          RoadSpan span,
                                                          RoadPlannerBuildSettings settings,
                                                          int startOrder) {
        if (points.size() < 2) {
            return List.of();
        }
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        BridgeProfile profile = buildBridgeProfile(points, span);
        List<RoadCenterlinePoint> bridgeProfile = profile.points();
        int deckStart = profile.deckStartInclusive();
        int deckEndExclusive = profile.deckEndExclusive();

        for (int index = 0; index < points.size(); index++) {
            RoadCenterlinePoint point = bridgeProfile.get(index);
            int y = point.targetY();
            boolean ramp = index < deckStart || index >= deckEndExclusive;
            BlockState state = ramp ? rampState(settings, index) : settings.surfaceState();
            BuildPhase phase = ramp ? BuildPhase.RAMP : BuildPhase.DECK;
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            List<BlockPos> footprint = RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
            for (BlockPos surfacePos : footprint) {
                steps.add(new BuildStep(order++, surfacePos, state, phase));
            }
            steps.addAll(railings(bridgeProfile, index, center, settings, order));
            order = startOrder + steps.size();
            if (!ramp && index % PIER_INTERVAL == 0) {
                int bottomY = Math.min(point.terrainY(), y - 1);
                for (int pierY = y - 1; pierY >= bottomY; pierY--) {
                    steps.add(new BuildStep(order++, new BlockPos(center.getX(), pierY, center.getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
                }
            }
        }
        return List.copyOf(steps);
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, int index) {
        return (index & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
    }

    private static BridgeProfile buildBridgeProfile(List<RoadCenterlinePoint> points, RoadSpan span) {
        int entryY = points.get(0).targetY();
        int exitY = points.get(points.size() - 1).targetY();
        int terrainFloor = points.stream().mapToInt(RoadCenterlinePoint::terrainY).min().orElse(Math.min(entryY, exitY));
        boolean smallBridge = span.sourceSegmentType() == RoadPlannerSegmentType.BRIDGE_SMALL;
        int desiredDeckY = smallBridge
                ? Math.max(Math.max(entryY, exitY) + 1, terrainFloor + 2)
                : Math.max(Math.max(entryY, exitY) + MAJOR_HEIGHT_BONUS, terrainFloor + 6);
        int maxAvailableRampSamples = Math.max(2, Math.max(1, (points.size() - 2) / 2));
        int maxDeckYFromEntry = entryY + maxAvailableRampSamples;
        int maxDeckYFromExit = exitY + maxAvailableRampSamples;
        int deckY = Math.min(desiredDeckY, Math.min(maxDeckYFromEntry, maxDeckYFromExit));
        deckY = Math.max(deckY, Math.max(entryY, exitY) + 1);

        int entryRampLen = Math.max(2, Math.min(points.size() / 3, Math.abs(deckY - entryY) + 1));
        int exitRampLen = Math.max(2, Math.min(points.size() / 3, Math.abs(deckY - exitY) + 1));
        int deckStart = Math.min(points.size() - 1, entryRampLen);
        int deckEndExclusive = Math.max(deckStart + 1, points.size() - exitRampLen);

        List<RoadCenterlinePoint> adjusted = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            RoadCenterlinePoint point = points.get(index);
            int y = bridgeY(index, points.size(), entryY, exitY, deckY, entryRampLen, exitRampLen);
            adjusted.add(point.withTargetY(y));
        }
        return new BridgeProfile(List.copyOf(adjusted), deckStart, deckEndExclusive);
    }

    private static int bridgeY(int index, int total, int entryY, int exitY, int deckY, int entryRampLen, int exitRampLen) {
        if (index < entryRampLen && entryRampLen > 1) {
            return monotonicRampY(entryY, deckY, index, entryRampLen - 1);
        }
        if (index >= total - exitRampLen && exitRampLen > 1) {
            int localIndex = total - 1 - index;
            return monotonicRampY(exitY, deckY, localIndex, exitRampLen - 1);
        }
        return deckY;
    }

    private static int monotonicRampY(int lowY, int highY, int localIndexFromLowEnd, int maxLocalIndex) {
        int delta = highY - lowY;
        if (delta <= 0 || maxLocalIndex <= 0) {
            return highY;
        }
        int rise = Math.min(delta, localIndexFromLowEnd);
        return lowY + rise;
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
