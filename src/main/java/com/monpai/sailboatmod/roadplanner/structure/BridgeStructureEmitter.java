package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return emitWithTransitions(centerline, spans, settings, templateProvider, startOrder, terrainSampler).steps();
    }

    public static BridgeEmissionResult emitWithTransitions(List<RoadCenterlinePoint> centerline,
                                                           List<RoadSpan> spans,
                                                           RoadPlannerBuildSettings settings,
                                                           BridgeTemplateProvider templateProvider,
                                                           int startOrder,
                                                           RoadTerrainSampler terrainSampler) {
        if (centerline == null || centerline.isEmpty() || spans == null || spans.isEmpty()) {
            return BridgeEmissionResult.empty();
        }
        RoadPlannerBuildSettings safeSettings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        BridgeTemplateProvider safeProvider = templateProvider == null ? BridgeTemplateProvider.empty() : templateProvider;
        List<BuildStep> steps = new ArrayList<>();
        Set<Long> bridgeSurfaceColumns = new HashSet<>();
        int order = startOrder;
        for (RoadSpan span : spans) {
            if (span.type() != RoadSpanType.BRIDGE) {
                continue;
            }
            BridgeTransitionProfile.Result transition = BridgeTransitionProfile.build(centerline, spans, span);
            List<RoadCenterlinePoint> bridgePoints = transition.points().isEmpty()
                    ? centerline.subList(span.startIndex(), span.endIndex() + 1)
                    : transition.points();
            bridgeSurfaceColumns.addAll(transition.transitionColumns());
            List<BuildStep> templateSteps = safeProvider.buildFromTemplate(bridgePoints, safeSettings, order);
            if (!templateSteps.isEmpty()) {
                steps.addAll(templateSteps);
                addBridgeSurfaceColumns(bridgeSurfaceColumns, templateSteps);
                order += templateSteps.size();
                continue;
            }
            List<BuildStep> programmatic = emitProgrammaticBridge(
                    bridgePoints,
                    span,
                    safeSettings,
                    order,
                    terrainSampler,
                    transition.originalStartOffset(),
                    transition.originalEndExclusive()
            );
            steps.addAll(programmatic);
            addBridgeSurfaceColumns(bridgeSurfaceColumns, programmatic);
            order += programmatic.size();
        }
        return new BridgeEmissionResult(steps, bridgeSurfaceColumns);
    }

    private static void addBridgeSurfaceColumns(Set<Long> columns, List<BuildStep> steps) {
        if (columns == null || steps == null) {
            return;
        }
        for (BuildStep step : steps) {
            if (step == null || step.pos() == null || step.state() == null || step.state().isAir()) {
                continue;
            }
            if (step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK) {
                columns.add(BridgeTransitionProfile.columnKey(step.pos().getX(), step.pos().getZ()));
            }
        }
    }

    private static List<BuildStep> emitProgrammaticBridge(List<RoadCenterlinePoint> points,
                                                          RoadSpan span,
                                                          RoadPlannerBuildSettings settings,
                                                          int startOrder,
                                                          RoadTerrainSampler terrainSampler,
                                                          int originalStartOffset,
                                                          int originalEndExclusive) {
        if (points.size() < 2) {
            return List.of();
        }
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        BridgeConfig bridgeConfig = new BridgeConfig();
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                points,
                span,
                terrainSampler,
                bridgeConfig
        );
        List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints = plan.points();
        List<RoadCenterlinePoint> bridgeProfile = legacyRampEmissionProfile(plannedPoints);
        List<List<BlockPos>> footprints = bridgeFootprints(
                bridgeProfile,
                plannedPoints,
                settings,
                settings.width(),
                originalStartOffset,
                originalEndExclusive
        );
        RoadTerrainSampler supportSampler = terrainSampler == null
                ? RoadTerrainSampler.flat(points.get(0).terrainY())
                : terrainSampler;

        for (RoadPlannerBridgeGeometryPlanner.Pier pier : plan.piers()) {
            for (int pierY = pier.bottomY(); pierY <= pier.topY(); pierY++) {
                steps.add(new BuildStep(order++, new BlockPos(pier.center().getX(), pierY, pier.center().getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.PIER));
            }
        }

        int deckLightIndex = 0;
        for (int index = 0; index < plannedPoints.size(); index++) {
            RoadPlannerBridgeGeometryPlanner.PlannedPoint planned = plannedPoints.get(index);
            RoadCenterlinePoint point = bridgeProfile.get(index);
            int y = point.targetY();
            BlockState state = bridgeSurfaceState(settings, plannedPoints, bridgeProfile, index, originalStartOffset, originalEndExclusive);
            BuildPhase phase = planned.phase();
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            List<BlockPos> footprint = index < footprints.size()
                    ? footprints.get(index)
                    : RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
            for (BlockPos surfacePos : footprint) {
                if (phase == BuildPhase.RAMP) {
                    order = plan.profile().usesPiers()
                            ? addRampSupport(steps, surfacePos, order, supportSampler)
                            : addRampUnderfill(steps, surfacePos, settings.surfaceState(), order);
                }
                steps.add(new BuildStep(order++, surfacePos, state, phase));
                order = addOverheadClearance(steps, surfacePos, order);
            }
            steps.addAll(railings(bridgeProfile, index, center, settings, order));
            order = startOrder + steps.size();
            if (phase == BuildPhase.DECK) {
                if (shouldPlaceBridgeLight(settings, deckLightIndex, bridgeConfig)) {
                    steps.addAll(streetlight(bridgeProfile, index, center, settings, order, deckLightIndex, bridgeConfig));
                    order = startOrder + steps.size();
                }
                deckLightIndex++;
            }
        }
        return List.copyOf(steps);
    }

    private static List<List<BlockPos>> bridgeFootprints(List<RoadCenterlinePoint> profile,
                                                         List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                         RoadPlannerBuildSettings settings,
                                                         int width,
                                                         int originalStartOffset,
                                                         int originalEndExclusive) {
        if (profile == null || profile.isEmpty()) {
            return List.of();
        }
        List<List<BlockPos>> rasterized = alignFootprintsToProfileY(
                RoadBandRasterizer.surfacePositionsByIndex(profile, width),
                profile
        );
        ArrayList<List<BlockPos>> footprints = new ArrayList<>(profile.size());
        for (int index = 0; index < profile.size(); index++) {
            footprints.add(index < rasterized.size() ? rasterized.get(index) : List.of());
        }

        int index = 0;
        while (index < profile.size()) {
            if (plannedPoints == null || index >= plannedPoints.size()
                    || plannedPoints.get(index).phase() != BuildPhase.RAMP) {
                index++;
                continue;
            }
            int start = index;
            int end = index;
            while (end + 1 < profile.size()
                    && end + 1 < plannedPoints.size()
                    && plannedPoints.get(end + 1).phase() == BuildPhase.RAMP) {
                end++;
            }
            replaceRampRunFootprints(footprints, profile, plannedPoints, settings, start, end, width, originalStartOffset, originalEndExclusive);
            index = end + 1;
        }
        return List.copyOf(footprints);
    }

    private static void replaceRampRunFootprints(ArrayList<List<BlockPos>> footprints,
                                                 List<RoadCenterlinePoint> profile,
                                                 List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                 RoadPlannerBuildSettings settings,
                                                 int start,
                                                 int end,
                                                 int width,
                                                 int originalStartOffset,
                                                 int originalEndExclusive) {
        for (int index = start; index <= end; index++) {
            footprints.set(index, List.of());
        }
        Map<Long, RampCell> cellsByColumn = new LinkedHashMap<>();
        for (int index = start; index <= end; index++) {
            RoadCenterlinePoint point = profile.get(index);
            RampBasis basis = rampBasisAt(profile, start, end, index);
            int surfaceHeight = rampSurfaceTopHalfUnits(settings, plannedPoints, profile, index, originalStartOffset, originalEndExclusive);
            for (BlockPos pos : fixedWidthStrip(point, basis, width)) {
                long key = BridgeTransitionProfile.columnKey(pos.getX(), pos.getZ());
                RampCell candidate = new RampCell(index, pos, surfaceHeight);
                RampCell existing = cellsByColumn.get(key);
                if (existing == null || candidate.surfaceTopHalfUnits() >= existing.surfaceTopHalfUnits()) {
                    cellsByColumn.put(key, candidate);
                }
            }
        }
        ArrayList<LinkedHashSet<BlockPos>> byIndex = new ArrayList<>(profile.size());
        for (int index = 0; index < profile.size(); index++) {
            byIndex.add(new LinkedHashSet<>());
        }
        for (RampCell cell : cellsByColumn.values()) {
            byIndex.get(cell.index()).add(cell.pos());
        }
        for (int index = start; index <= end; index++) {
            if (byIndex.get(index).isEmpty()) {
                RoadCenterlinePoint point = profile.get(index);
                RampBasis basis = rampBasisAt(profile, start, end, index);
                for (BlockPos pos : fixedWidthStrip(point, basis, width)) {
                    byIndex.get(index).add(pos);
                }
            }
            footprints.set(index, List.copyOf(byIndex.get(index)));
        }
    }

    private static List<BlockPos> fixedWidthStrip(RoadCenterlinePoint point, RampBasis basis, int width) {
        if (point == null || basis == null) {
            return List.of();
        }
        int halfWidth = Math.max(1, width / 2);
        BlockPos center = new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ());
        ArrayList<BlockPos> strip = new ArrayList<>(halfWidth * 2 + 1);
        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            strip.add(center.offset(basis.normalX() * offset, 0, basis.normalZ() * offset));
        }
        return List.copyOf(strip);
    }

    private static int rampSurfaceTopHalfUnits(RoadPlannerBuildSettings settings,
                                               List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                               List<RoadCenterlinePoint> profile,
                                               int index,
                                               int originalStartOffset,
                                               int originalEndExclusive) {
        BlockState state = bridgeSurfaceState(settings, plannedPoints, profile, index, originalStartOffset, originalEndExclusive);
        int y = profile.get(index).targetY();
        if (state.hasProperty(SlabBlock.TYPE)) {
            return (y * 2) + (state.getValue(SlabBlock.TYPE) == SlabType.TOP ? 2 : 1);
        }
        return (y * 2) + 2;
    }

    private static RampBasis rampBasisAt(List<RoadCenterlinePoint> profile, int start, int end, int index) {
        BlockPos current = profile.get(index).pos();
        int dx = 0;
        int dz = 0;
        if (index + 1 <= end) {
            BlockPos next = profile.get(index + 1).pos();
            dx = next.getX() - current.getX();
            dz = next.getZ() - current.getZ();
        }
        if (dx == 0 && dz == 0 && index > start) {
            BlockPos previous = profile.get(index - 1).pos();
            dx = current.getX() - previous.getX();
            dz = current.getZ() - previous.getZ();
        }
        if (dx == 0 && dz == 0 && index + 1 < profile.size()) {
            BlockPos next = profile.get(index + 1).pos();
            dx = next.getX() - current.getX();
            dz = next.getZ() - current.getZ();
        }
        if (dx == 0 && dz == 0 && index > 0) {
            BlockPos previous = profile.get(index - 1).pos();
            dx = current.getX() - previous.getX();
            dz = current.getZ() - previous.getZ();
        }
        return cardinalRampBasis(dx, dz);
    }

    private static RampBasis cardinalRampBasis(int deltaX, int deltaZ) {
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            int directionX = Integer.compare(deltaX, 0);
            if (directionX != 0) {
                return new RampBasis(0, directionX);
            }
        }
        int directionZ = Integer.compare(deltaZ, 0);
        if (directionZ != 0) {
            return new RampBasis(-directionZ, 0);
        }
        return new RampBasis(0, 1);
    }

    private static List<List<BlockPos>> alignFootprintsToProfileY(List<List<BlockPos>> footprints,
                                                                  List<RoadCenterlinePoint> profile) {
        if (footprints == null || footprints.isEmpty()) {
            return List.of();
        }
        List<List<BlockPos>> aligned = new ArrayList<>(footprints.size());
        for (int index = 0; index < footprints.size(); index++) {
            int y = index < profile.size() ? profile.get(index).targetY() : 64;
            LinkedHashSet<BlockPos> bucket = new LinkedHashSet<>();
            for (BlockPos pos : footprints.get(index)) {
                if (pos != null) {
                    bucket.add(new BlockPos(pos.getX(), y, pos.getZ()));
                }
            }
            aligned.add(List.copyOf(bucket));
        }
        return List.copyOf(aligned);
    }

    private static List<RoadCenterlinePoint> legacyRampEmissionProfile(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints) {
        if (plannedPoints == null || plannedPoints.isEmpty()) {
            return List.of();
        }
        List<RoadCenterlinePoint> profile = new ArrayList<>(plannedPoints.size());
        int index = 0;
        while (index < plannedPoints.size()) {
            RoadPlannerBridgeGeometryPlanner.PlannedPoint planned = plannedPoints.get(index);
            if (planned.phase() != BuildPhase.RAMP) {
                profile.add(planned.point());
                index++;
                continue;
            }

            int start = index;
            int end = index;
            while (end + 1 < plannedPoints.size() && plannedPoints.get(end + 1).phase() == BuildPhase.RAMP) {
                end++;
            }
            boolean ascending = rampRunAscending(plannedPoints, start, end);
            if (!canUseLegacyRampProfile(plannedPoints, start, end, ascending)) {
                for (int rampIndex = start; rampIndex <= end; rampIndex++) {
                    profile.add(plannedPoints.get(rampIndex).point());
                }
            } else if (ascending) {
                addLegacyAscendingRampProfile(profile, plannedPoints, start, end);
            } else {
                addLegacyDescendingRampProfile(profile, plannedPoints, start, end);
            }
            index = end + 1;
        }
        return List.copyOf(profile);
    }

    private static boolean canUseLegacyRampProfile(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                   int start,
                                                   int end,
                                                   boolean ascending) {
        int rampPointCount = end - start + 1;
        int requiredHalfSteps;
        if (ascending) {
            int startY = plannedPoints.get(start).point().targetY();
            int deckSideY = end + 1 < plannedPoints.size()
                    ? plannedPoints.get(end + 1).point().targetY()
                    : plannedPoints.get(end).point().targetY();
            requiredHalfSteps = Math.max(0, deckSideY - startY) * 2;
        } else {
            int deckSideY = legacyDescendingDeckSideY(plannedPoints, start, end);
            int endY = plannedPoints.get(end).point().targetY();
            requiredHalfSteps = Math.max(0, deckSideY - endY) * 2;
        }
        return rampPointCount >= requiredHalfSteps;
    }

    private static void addLegacyAscendingRampProfile(List<RoadCenterlinePoint> profile,
                                                      List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                      int start,
                                                      int end) {
        int currentY = plannedPoints.get(start).point().targetY();
        for (int index = start; index <= end; index++) {
            int localRampIndex = index - start;
            profile.add(plannedPoints.get(index).point().withTargetY(currentY));
            if ((localRampIndex & 1) == 1) {
                currentY++;
            }
        }
    }

    private static void addLegacyDescendingRampProfile(List<RoadCenterlinePoint> profile,
                                                       List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                       int start,
                                                       int end) {
        int currentY = legacyDescendingDeckSideY(plannedPoints, start, end);
        for (int index = start; index <= end; index++) {
            int localRampIndex = index - start;
            if ((localRampIndex & 1) == 0) {
                currentY--;
            }
            profile.add(plannedPoints.get(index).point().withTargetY(currentY));
        }
    }

    private static int legacyDescendingDeckSideY(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                 int start,
                                                 int end) {
        if (start > 0) {
            return plannedPoints.get(start - 1).point().targetY();
        }
        if (end + 1 < plannedPoints.size()) {
            return plannedPoints.get(end + 1).point().targetY();
        }
        int highestRampY = Integer.MIN_VALUE;
        for (int index = start; index <= end; index++) {
            highestRampY = Math.max(highestRampY, plannedPoints.get(index).point().targetY());
        }
        return highestRampY == Integer.MIN_VALUE ? 0 : highestRampY + 1;
    }

    private static int addRampUnderfill(List<BuildStep> steps,
                                        BlockPos surfacePos,
                                        BlockState state,
                                        int order) {
        if (surfacePos == null || state == null) {
            return order;
        }
        steps.add(new BuildStep(order++, surfacePos.below(), state, BuildPhase.FOUNDATION));
        return order;
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
            steps.add(new BuildStep(order++, new BlockPos(surfacePos.getX(), y, surfacePos.getZ()), Blocks.STONE_BRICKS.defaultBlockState(), BuildPhase.FOUNDATION));
        }
        return order;
    }

    private static BlockState bridgeSurfaceState(RoadPlannerBuildSettings settings,
                                                 List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points,
                                                 List<RoadCenterlinePoint> profile,
                                                 int index,
                                                 int originalStartOffset,
                                                 int originalEndExclusive) {
        if (points.get(index).phase() == BuildPhase.RAMP) {
            int start = rampRunStart(points, index);
            int end = rampRunEnd(points, index);
            boolean ascending = rampRunAscending(points, start, end);
            if (!canUseLegacyRampProfile(points, start, end, ascending)) {
                return roadTransitionRampState(settings, profileRampState(settings, profile, index), index, originalStartOffset, originalEndExclusive);
            }
            return roadTransitionRampState(settings, rampState(settings, points, index), index, originalStartOffset, originalEndExclusive);
        }
        if (isDeckRampTransition(points, index)) {
            return settings.slabBottomState();
        }
        return settings.surfaceState();
    }

    private static int rampRunStart(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
        int start = index;
        while (start > 0 && points.get(start - 1).phase() == BuildPhase.RAMP) {
            start--;
        }
        return start;
    }

    private static int rampRunEnd(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
        int end = index;
        while (end + 1 < points.size() && points.get(end + 1).phase() == BuildPhase.RAMP) {
            end++;
        }
        return end;
    }

    private static boolean isDeckRampTransition(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
        if (points == null || index < 0 || index >= points.size() || points.get(index).phase() != BuildPhase.DECK) {
            return false;
        }
        int deckY = points.get(index).point().targetY();
        boolean climbsFromPreviousRamp = index > 0
                && points.get(index - 1).phase() == BuildPhase.RAMP
                && deckY > points.get(index - 1).point().targetY();
        boolean dropsToNextRamp = index + 1 < points.size()
                && points.get(index + 1).phase() == BuildPhase.RAMP
                && deckY > points.get(index + 1).point().targetY();
        return climbsFromPreviousRamp || dropsToNextRamp;
    }

    private static int addOverheadClearance(List<BuildStep> steps, BlockPos surfacePos, int order) {
        for (int dy = 1; dy <= 4; dy++) {
            steps.add(new BuildStep(order++, surfacePos.above(dy), Blocks.AIR.defaultBlockState(), BuildPhase.FOUNDATION));
        }
        return order;
    }

    private static BlockState rampState(RoadPlannerBuildSettings settings, List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
        int start = rampRunStart(points, index);
        int end = rampRunEnd(points, index);
        boolean ascending = rampRunAscending(points, start, end);
        int localRampIndex = index - start;
        if (ascending) {
            return (localRampIndex & 1) == 0 ? settings.slabBottomState() : settings.slabTopState();
        }
        return (localRampIndex & 1) == 0 ? settings.slabTopState() : settings.slabBottomState();
    }

    private static BlockState roadTransitionRampState(RoadPlannerBuildSettings settings,
                                                      BlockState state,
                                                      int index,
                                                      int originalStartOffset,
                                                      int originalEndExclusive) {
        if (index >= originalStartOffset && index < originalEndExclusive) {
            return state;
        }
        return settings.slabTopState();
    }

    private static BlockState profileRampState(RoadPlannerBuildSettings settings, List<RoadCenterlinePoint> profile, int index) {
        int y = profile.get(index).targetY();
        int prevY = index > 0 ? profile.get(index - 1).targetY() : y;
        int nextY = index + 1 < profile.size() ? profile.get(index + 1).targetY() : y;
        if (y > prevY || nextY < y) {
            return settings.slabBottomState();
        }
        return settings.slabTopState();
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

    private static boolean shouldPlaceBridgeLight(RoadPlannerBuildSettings settings, int deckLightIndex, BridgeConfig bridgeConfig) {
        if (settings == null || !settings.streetlightsEnabled()) {
            return false;
        }
        int interval = Math.max(1, bridgeConfig == null ? 8 : bridgeConfig.getLightInterval());
        return deckLightIndex % interval == 0;
    }

    private static List<BuildStep> streetlight(List<RoadCenterlinePoint> points,
                                               int index,
                                               BlockPos center,
                                               RoadPlannerBuildSettings settings,
                                               int startOrder,
                                               int deckLightIndex,
                                               BridgeConfig bridgeConfig) {
        List<BlockPos> railPositions = RoadFootprintPlanner.railingPositions(points, index, center, settings.width());
        if (railPositions.isEmpty()) {
            return List.of();
        }
        int interval = Math.max(1, bridgeConfig == null ? 8 : bridgeConfig.getLightInterval());
        int railIndex = Math.floorDiv(deckLightIndex, interval) % 2;
        BlockPos rail = railPositions.get(Math.min(railIndex, railPositions.size() - 1));
        int inwardX = Integer.compare(center.getX() - rail.getX(), 0);
        int inwardZ = Integer.compare(center.getZ() - rail.getZ(), 0);
        if (inwardX == 0 && inwardZ == 0) {
            inwardX = 1;
        }
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        List<BuildStep> steps = new ArrayList<>();
        BlockPos base = rail.above();
        steps.add(new BuildStep(startOrder + steps.size(), base, fence, BuildPhase.STREETLIGHT));
        steps.add(new BuildStep(startOrder + steps.size(), base.above(), fence, BuildPhase.STREETLIGHT));
        steps.add(new BuildStep(startOrder + steps.size(), base.above(2), fence, BuildPhase.STREETLIGHT));
        BlockPos arm = base.above(2).offset(inwardX, 0, inwardZ);
        steps.add(new BuildStep(startOrder + steps.size(), arm, fence, BuildPhase.STREETLIGHT));
        steps.add(new BuildStep(startOrder + steps.size(), arm.below(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), BuildPhase.STREETLIGHT));
        return List.copyOf(steps);
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

    private record RampBasis(int normalX, int normalZ) {
    }

    private record RampCell(int index, BlockPos pos, int surfaceTopHalfUnits) {
    }
}
