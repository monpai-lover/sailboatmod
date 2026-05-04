package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverSegmentPaver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class BridgeStructureEmitter {
    private static final int MAJOR_HEIGHT_BONUS = 5;
    private static final int SMALL_HEIGHT_BONUS = 3;
    private static final int PIER_INTERVAL = 4;

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
        int heightBonus = span.sourceSegmentType() == RoadPlannerSegmentType.BRIDGE_SMALL ? SMALL_HEIGHT_BONUS : MAJOR_HEIGHT_BONUS;
        int entryY = points.get(0).targetY();
        int exitY = points.get(points.size() - 1).targetY();
        int terrainFloor = points.stream().mapToInt(RoadCenterlinePoint::terrainY).min().orElse(Math.min(entryY, exitY));
        int deckY = Math.max(Math.max(entryY, exitY) + heightBonus, terrainFloor + 6);
        int entryRampLen = Math.min(Math.max(2, (deckY - entryY) * 2), Math.max(1, points.size() / 4));
        int exitRampLen = Math.min(Math.max(2, (deckY - exitY) * 2), Math.max(1, points.size() / 4));
        int deckStart = Math.min(points.size() - 1, entryRampLen);
        int deckEndExclusive = Math.max(deckStart + 1, points.size() - exitRampLen);

        for (int index = 0; index < points.size(); index++) {
            RoadCenterlinePoint point = points.get(index);
            int y = bridgeY(index, points.size(), entryY, exitY, deckY, entryRampLen, exitRampLen);
            boolean ramp = index < deckStart || index >= deckEndExclusive;
            BlockState state = ramp ? rampState(settings, index) : settings.surfaceState();
            BuildPhase phase = ramp ? BuildPhase.RAMP : BuildPhase.DECK;
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            for (WeaverBuildCandidate candidate : WeaverSegmentPaver.paveCenterline(List.of(center), settings.width(), state)) {
                steps.add(new BuildStep(order++, candidate.pos(), candidate.state(), phase));
            }
            steps.addAll(railings(points, index, center, settings, order));
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

    private static int bridgeY(int index, int total, int entryY, int exitY, int deckY, int entryRampLen, int exitRampLen) {
        if (index < entryRampLen && entryRampLen > 0) {
            return (int) Math.round(entryY + (deckY - entryY) * (index / (double) entryRampLen));
        }
        if (index >= total - exitRampLen && exitRampLen > 0) {
            int remaining = total - 1 - index;
            return (int) Math.round(exitY + (deckY - exitY) * (remaining / (double) exitRampLen));
        }
        return deckY;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, int index) {
        return (index & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
    }

    private static List<BuildStep> railings(List<RoadCenterlinePoint> points,
                                            int index,
                                            BlockPos center,
                                            RoadPlannerBuildSettings settings,
                                            int startOrder) {
        int dx = 0;
        int dz = 0;
        if (index + 1 < points.size()) {
            dx = Integer.compare(points.get(index + 1).pos().getX() - center.getX(), 0);
            dz = Integer.compare(points.get(index + 1).pos().getZ() - center.getZ(), 0);
        } else if (index > 0) {
            dx = Integer.compare(center.getX() - points.get(index - 1).pos().getX(), 0);
            dz = Integer.compare(center.getZ() - points.get(index - 1).pos().getZ(), 0);
        }
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        int halfWidth = settings.width() / 2;
        int perpX = -dz;
        int perpZ = dx;
        BlockState rail = Blocks.OAK_FENCE.defaultBlockState();
        return List.of(
                new BuildStep(startOrder, center.offset(perpX * (halfWidth + 1), 1, perpZ * (halfWidth + 1)), rail, BuildPhase.RAILING),
                new BuildStep(startOrder + 1, center.offset(perpX * -(halfWidth + 1), 1, perpZ * -(halfWidth + 1)), rail, BuildPhase.RAILING)
        );
    }
}
