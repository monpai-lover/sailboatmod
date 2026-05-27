package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private static final int DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE = 2;
    private static final int MAX_RAMP_TRANSITION_SAMPLES_PER_SIDE = 24;
    private static final int CLEARANCE_HEIGHT = 6;

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
            transition = transitionWithEnoughRampSamples(centerline, spans, span, transition, terrainSampler);
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

    private static BridgeTransitionProfile.Result transitionWithEnoughRampSamples(List<RoadCenterlinePoint> centerline,
                                                                                  List<RoadSpan> spans,
                                                                                  RoadSpan span,
                                                                                  BridgeTransitionProfile.Result initial,
                                                                                  RoadTerrainSampler terrainSampler) {
        if (centerline == null || centerline.isEmpty() || span == null || initial == null || initial.points().isEmpty()) {
            return initial;
        }
        if (!centerlineRunIsStraight(initial.points())) {
            return initial;
        }
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                initial.points(),
                span,
                terrainSampler,
                new BridgeConfig()
        );
        int leftSamples = transitionSamplesForSide(initial.points(), initial.originalStartOffset(), plan.deckY(), true);
        int rightSamples = transitionSamplesForSide(initial.points(), initial.originalEndExclusive(), plan.deckY(), false);
        if (!canExpandLeftTransitionSamples(initial.points(), initial.originalStartOffset())) {
            leftSamples = DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE;
        }
        if (!canExpandRightTransitionSamples(initial.points(), initial.originalEndExclusive())) {
            rightSamples = DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE;
        }
        if (leftSamples <= DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE && rightSamples <= DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE) {
            return initial;
        }
        return BridgeTransitionProfile.build(
                centerline,
                spans,
                span,
                Math.min(MAX_RAMP_TRANSITION_SAMPLES_PER_SIDE, leftSamples),
                Math.min(MAX_RAMP_TRANSITION_SAMPLES_PER_SIDE, rightSamples)
        );
    }

    private static int transitionSamplesForSide(List<RoadCenterlinePoint> points,
                                                int originalBoundary,
                                                int deckY,
                                                boolean leftSide) {
        if (points == null || points.isEmpty()) {
            return DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE;
        }
        int sampleIndex = leftSide
                ? Math.max(0, Math.min(points.size() - 1, originalBoundary - 1))
                : Math.max(0, Math.min(points.size() - 1, originalBoundary));
        int shoreY = points.get(sampleIndex).targetY();
        int requiredHalfSteps = Math.max(0, deckY - shoreY) * 2;
        return Math.max(DEFAULT_RAMP_TRANSITION_SAMPLES_PER_SIDE, requiredHalfSteps);
    }

    private static boolean centerlineRunIsStraight(List<RoadCenterlinePoint> points) {
        if (points == null || points.size() <= 2) {
            return true;
        }
        BlockPos first = points.get(0).pos();
        BlockPos last = points.get(points.size() - 1).pos();
        boolean xMajor = Math.abs(last.getX() - first.getX()) >= Math.abs(last.getZ() - first.getZ());
        for (RoadCenterlinePoint point : points) {
            if (xMajor) {
                if (point.pos().getZ() != first.getZ()) {
                    return false;
                }
            } else if (point.pos().getX() != first.getX()) {
                return false;
            }
        }
        return true;
    }

    private static boolean canExpandLeftTransitionSamples(List<RoadCenterlinePoint> points, int originalStartOffset) {
        if (points == null || originalStartOffset <= 0 || originalStartOffset + 1 >= points.size()) {
            return false;
        }
        return sameCardinalDirection(
                points.get(originalStartOffset - 1).pos(),
                points.get(originalStartOffset).pos(),
                points.get(originalStartOffset).pos(),
                points.get(originalStartOffset + 1).pos()
        );
    }

    private static boolean canExpandRightTransitionSamples(List<RoadCenterlinePoint> points, int originalEndExclusive) {
        int bridgeEndIndex = originalEndExclusive - 1;
        if (points == null || bridgeEndIndex <= 0 || bridgeEndIndex + 1 >= points.size()) {
            return false;
        }
        return sameCardinalDirection(
                points.get(bridgeEndIndex - 1).pos(),
                points.get(bridgeEndIndex).pos(),
                points.get(bridgeEndIndex).pos(),
                points.get(bridgeEndIndex + 1).pos()
        );
    }

    private static boolean sameCardinalDirection(BlockPos firstFrom, BlockPos firstTo, BlockPos secondFrom, BlockPos secondTo) {
        Direction first = cardinalDirectionBetween(firstFrom, firstTo);
        Direction second = cardinalDirectionBetween(secondFrom, secondTo);
        return first != null && first == second;
    }

    private static Direction cardinalDirectionBetween(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return null;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
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
        List<RoadCenterlinePoint> bridgeProfile = legacyRampEmissionProfile(plannedPoints, originalStartOffset, originalEndExclusive);
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
            if (phase == BuildPhase.DECK && shouldTreatEntryDeckBoundaryAsRamp(state, plannedPoints, index, originalStartOffset)) {
                phase = BuildPhase.RAMP;
            }
            BlockPos center = new BlockPos(point.pos().getX(), y, point.pos().getZ());
            List<BlockPos> footprint = index < footprints.size()
                    ? footprints.get(index)
                    : RoadFootprintPlanner.surfacePositions(bridgeProfile, index, settings.width());
            for (BlockPos surfacePos : footprint) {
                BlockPos placedSurfacePos = surfacePos;
                BlockState placedState = state;
                if (phase == BuildPhase.RAMP || phase == BuildPhase.DECK) {
                    int surfaceTopHalfUnits = bridgeSurfaceTopHalfUnitsForCell(
                            settings,
                            plannedPoints,
                            bridgeProfile,
                            index,
                            phase,
                            state,
                            surfacePos,
                            originalStartOffset,
                            originalEndExclusive);
                    placedSurfacePos = posAtSurfaceTopHalfUnits(surfacePos.getX(), surfacePos.getZ(), surfaceTopHalfUnits);
                    if (surfaceTopHalfUnits != buildStateSurfaceTopHalfUnits(state, surfacePos)) {
                        placedState = slabStateForSurfaceTopHalfUnits(settings, surfaceTopHalfUnits);
                    }
                }
                if (phase == BuildPhase.RAMP) {
                    order = plan.profile().usesPiers()
                            ? addRampSupport(steps, placedSurfacePos, order, supportSampler)
                            : addRampUnderfill(steps, placedSurfacePos, settings.surfaceState(), order);
                }
                steps.add(new BuildStep(order++, placedSurfacePos, placedState, phase));
                order = addOverheadClearance(steps, placedSurfacePos, order);
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
        return collapseVisibleBridgeSurfaceColumns(steps);
    }

    private static boolean shouldTreatEntryDeckBoundaryAsRamp(BlockState state,
                                                              List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                              int index,
                                                              int originalStartOffset) {
        if (state == null || !state.hasProperty(SlabBlock.TYPE) || state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
            return false;
        }
        if (plannedPoints == null || index <= 0 || index >= plannedPoints.size() || plannedPoints.get(index - 1).phase() != BuildPhase.RAMP) {
            return false;
        }
        int start = rampRunStart(plannedPoints, index - 1);
        int end = rampRunEnd(plannedPoints, index - 1);
        return rampRunAscending(plannedPoints, start, end)
                && start < originalStartOffset
                && originalStartOffset <= index;
    }

    private static List<BuildStep> collapseVisibleBridgeSurfaceColumns(List<BuildStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        Map<Long, BuildStep> selectedSurfaceByColumn = new LinkedHashMap<>();
        for (BuildStep step : steps) {
            if (!isVisibleBridgeSurface(step)) {
                continue;
            }
            long key = BridgeTransitionProfile.columnKey(step.pos().getX(), step.pos().getZ());
            BuildStep existing = selectedSurfaceByColumn.get(key);
            if (existing == null || shouldReplaceVisibleBridgeSurface(existing, step)) {
                selectedSurfaceByColumn.put(key, step);
            }
        }
        ArrayList<BuildStep> filtered = new ArrayList<>(steps.size());
        for (BuildStep step : steps) {
            if (isVisibleBridgeSurface(step)) {
                BuildStep selected = selectedSurfaceByColumn.get(BridgeTransitionProfile.columnKey(step.pos().getX(), step.pos().getZ()));
                if (selected != step) {
                    continue;
                }
            }
            filtered.add(step);
        }
        return List.copyOf(filtered);
    }

    private static boolean shouldReplaceVisibleBridgeSurface(BuildStep existing, BuildStep candidate) {
        if (existing.phase() != candidate.phase()) {
            if (candidate.phase() == BuildPhase.RAMP && existing.phase() == BuildPhase.DECK) {
                return true;
            }
            if (candidate.phase() == BuildPhase.DECK && existing.phase() == BuildPhase.RAMP) {
                return false;
            }
        }
        int candidateTop = buildStepSurfaceTopHalfUnits(candidate);
        int existingTop = buildStepSurfaceTopHalfUnits(existing);
        return candidateTop > existingTop || (candidateTop == existingTop && candidate.order() > existing.order());
    }

    private static boolean isVisibleBridgeSurface(BuildStep step) {
        return step != null
                && step.pos() != null
                && step.state() != null
                && !step.state().isAir()
                && (step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK);
    }

    private static int buildStepSurfaceTopHalfUnits(BuildStep step) {
        if (step.state().hasProperty(SlabBlock.TYPE)) {
            return (step.pos().getY() * 2) + (step.state().getValue(SlabBlock.TYPE) == SlabType.TOP ? 2 : 1);
        }
        return (step.pos().getY() * 2) + 2;
    }

    private static int bridgeSurfaceTopHalfUnitsForCell(RoadPlannerBuildSettings settings,
                                                        List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                        List<RoadCenterlinePoint> profile,
                                                        int index,
                                                        BuildPhase phase,
                                                        BlockState state,
                                                        BlockPos surfacePos,
        int originalStartOffset,
        int originalEndExclusive) {
        if (phase == BuildPhase.RAMP) {
            int start = rampRunStart(plannedPoints, index);
            int end = rampRunEnd(plannedPoints, index);
            if (!rampRunHasTurn(plannedPoints, start, end)) {
                return rampSurfaceTopHalfUnits(settings, plannedPoints, profile, index, originalStartOffset, originalEndExclusive);
            }
            return projectedRampSurfaceTopHalfUnits(
                    settings,
                    plannedPoints,
                    profile,
                    start,
                    end,
                    surfacePos.getX(),
                    surfacePos.getZ(),
                    originalStartOffset,
                    originalEndExclusive
            );
        }
        int baseTopHalfUnits = buildStateSurfaceTopHalfUnits(state, surfacePos);
        if (phase != BuildPhase.DECK) {
            return baseTopHalfUnits;
        }
        return smoothedDeckTransitionTopHalfUnits(
                settings,
                plannedPoints,
                profile,
                index,
                surfacePos,
                baseTopHalfUnits,
                originalStartOffset,
                originalEndExclusive);
    }

    private static int smoothedDeckTransitionTopHalfUnits(RoadPlannerBuildSettings settings,
                                                         List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                         List<RoadCenterlinePoint> profile,
                                                         int index,
                                                         BlockPos surfacePos,
                                                         int baseTopHalfUnits,
                                                         int originalStartOffset,
                                                         int originalEndExclusive) {
        if (!deckRunHasTurn(plannedPoints, index)) {
            return baseTopHalfUnits;
        }
        int smoothed = baseTopHalfUnits;
        int previousRampIndex = previousRampIndexBeforeDeckRun(plannedPoints, index);
        if (previousRampIndex >= 0) {
            int start = rampRunStart(plannedPoints, previousRampIndex);
            int end = rampRunEnd(plannedPoints, previousRampIndex);
            RampProjection projection = projectedRampSurface(
                    settings,
                    plannedPoints,
                    profile,
                    start,
                    end,
                    surfacePos.getX(),
                    surfacePos.getZ(),
                    originalStartOffset,
                    originalEndExclusive
            );
            if (isNearRampProjection(settings, projection)) {
                smoothed = Math.min(smoothed, projection.surfaceTopHalfUnits() + 1);
            }
        }
        int nextRampIndex = nextRampIndexAfterDeckRun(plannedPoints, index);
        if (nextRampIndex >= 0) {
            int start = rampRunStart(plannedPoints, nextRampIndex);
            int end = rampRunEnd(plannedPoints, nextRampIndex);
            RampProjection projection = projectedRampSurface(
                    settings,
                    plannedPoints,
                    profile,
                    start,
                    end,
                    surfacePos.getX(),
                    surfacePos.getZ(),
                    originalStartOffset,
                    originalEndExclusive
            );
            if (isNearRampProjection(settings, projection)) {
                smoothed = Math.min(smoothed, projection.surfaceTopHalfUnits() + 1);
            }
        }
        return smoothed;
    }

    private static int previousRampIndexBeforeDeckRun(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints, int index) {
        int cursor = index - 1;
        while (cursor >= 0 && plannedPoints.get(cursor).phase() == BuildPhase.DECK) {
            cursor--;
        }
        return cursor >= 0 && plannedPoints.get(cursor).phase() == BuildPhase.RAMP ? cursor : -1;
    }

    private static int nextRampIndexAfterDeckRun(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints, int index) {
        int cursor = index + 1;
        while (cursor < plannedPoints.size() && plannedPoints.get(cursor).phase() == BuildPhase.DECK) {
            cursor++;
        }
        return cursor < plannedPoints.size() && plannedPoints.get(cursor).phase() == BuildPhase.RAMP ? cursor : -1;
    }

    private static boolean rampRunHasTurn(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints, int start, int end) {
        if (plannedPoints == null || end - start < 2) {
            return false;
        }
        Direction previous = null;
        for (int index = start; index < end; index++) {
            Direction current = cardinalDirectionBetween(
                    plannedPoints.get(index).point().pos(),
                    plannedPoints.get(index + 1).point().pos()
            );
            if (current == null) {
                continue;
            }
            if (previous != null && current != previous) {
                return true;
            }
            previous = current;
        }
        return false;
    }

    private static boolean deckRunHasTurn(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints, int index) {
        int start = index;
        while (start > 0 && plannedPoints.get(start - 1).phase() == BuildPhase.DECK) {
            start--;
        }
        int end = index;
        while (end + 1 < plannedPoints.size() && plannedPoints.get(end + 1).phase() == BuildPhase.DECK) {
            end++;
        }
        Direction previous = null;
        for (int cursor = start; cursor < end; cursor++) {
            Direction current = cardinalDirectionBetween(
                    plannedPoints.get(cursor).point().pos(),
                    plannedPoints.get(cursor + 1).point().pos()
            );
            if (current == null) {
                continue;
            }
            if (previous != null && current != previous) {
                return true;
            }
            previous = current;
        }
        return false;
    }

    private static boolean isNearRampProjection(RoadPlannerBuildSettings settings, RampProjection projection) {
        double maxDistance = Math.max(1, settings.width());
        return projection.distanceSq() <= maxDistance * maxDistance;
    }

    private static int buildStateSurfaceTopHalfUnits(BlockState state, BlockPos pos) {
        if (state != null && state.hasProperty(SlabBlock.TYPE)) {
            return (pos.getY() * 2) + (state.getValue(SlabBlock.TYPE) == SlabType.TOP ? 2 : 1);
        }
        return (pos.getY() * 2) + 2;
    }

    private static BlockState slabStateForSurfaceTopHalfUnits(RoadPlannerBuildSettings settings, int surfaceTopHalfUnits) {
        return (surfaceTopHalfUnits & 1) == 1 ? settings.slabBottomState() : settings.slabTopState();
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
            boolean ramp = plannedPoints != null
                    && index < plannedPoints.size()
                    && plannedPoints.get(index).phase() == BuildPhase.RAMP;
            if (!ramp) {
                footprints.add(index < rasterized.size() ? rasterized.get(index) : bridgeDeckPlacerFootprint(profile, index, width));
                continue;
            }
            LinkedHashSet<BlockPos> rampFootprint = new LinkedHashSet<>();
            if (index < rasterized.size()) {
                rampFootprint.addAll(rasterized.get(index));
            }
            rampFootprint.addAll(bridgeDeckPlacerFootprint(profile, index, width));
            footprints.add(List.copyOf(rampFootprint));
        }
        return List.copyOf(footprints);
    }

    private static List<BlockPos> bridgeDeckPlacerFootprint(List<RoadCenterlinePoint> profile, int index, int width) {
        if (profile == null || profile.isEmpty() || index < 0 || index >= profile.size()) {
            return List.of();
        }
        int halfWidth = Math.max(1, width / 2);
        RoadCenterlinePoint point = profile.get(index);
        Direction localDir = getDirection(profile, index);
        Direction perpDir = localDir.getClockWise();
        ArrayList<BlockPos> positions = new ArrayList<>(halfWidth * 2 + 1);
        for (int offset = -halfWidth; offset <= halfWidth; offset++) {
            positions.add(new BlockPos(
                    point.pos().getX() + perpDir.getStepX() * offset,
                    point.targetY(),
                    point.pos().getZ() + perpDir.getStepZ() * offset
            ));
        }
        return List.copyOf(positions);
    }

    private static Direction getDirection(List<RoadCenterlinePoint> profile, int index) {
        int lookBack = Math.max(0, index - 4);
        int lookAhead = Math.min(profile.size() - 1, index + 4);
        BlockPos prev = profile.get(lookBack).pos();
        BlockPos next = profile.get(lookAhead).pos();
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
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
        boolean ascending = rampRunAscending(plannedPoints, start, end);
        Map<Long, RampCell> cellsByColumn = new LinkedHashMap<>();
        for (int index = start; index <= end; index++) {
            RoadCenterlinePoint point = profile.get(index);
            RampBasis basis = rampBasisAt(profile, start, end, index);
            for (BlockPos pos : fixedWidthStrip(point, basis, width)) {
                long key = BridgeTransitionProfile.columnKey(pos.getX(), pos.getZ());
                RampProjection projection = projectedRampSurface(
                        settings,
                        plannedPoints,
                        profile,
                        start,
                        end,
                        pos.getX(),
                        pos.getZ(),
                        originalStartOffset,
                        originalEndExclusive
                );
                RampCell candidate = new RampCell(
                        index,
                        posAtSurfaceTopHalfUnits(pos.getX(), pos.getZ(), projection.surfaceTopHalfUnits()),
                        projection.surfaceTopHalfUnits(),
                        projection.distanceSq()
                );
                RampCell existing = cellsByColumn.get(key);
                if (existing == null || shouldReplaceRampCell(existing, candidate, ascending)) {
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

    private static boolean shouldReplaceRampCell(RampCell existing, RampCell candidate, boolean ascending) {
        if (candidate.distanceSq() < existing.distanceSq() - 1.0E-9D) {
            return true;
        }
        if (Math.abs(candidate.distanceSq() - existing.distanceSq()) <= 1.0E-9D) {
            return ascending
                    ? candidate.surfaceTopHalfUnits() <= existing.surfaceTopHalfUnits()
                    : candidate.surfaceTopHalfUnits() >= existing.surfaceTopHalfUnits();
        }
        return false;
    }

    private static int projectedRampSurfaceTopHalfUnits(RoadPlannerBuildSettings settings,
                                                        List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                        List<RoadCenterlinePoint> profile,
                                                        int start,
                                                        int end,
                                                        int x,
                                                        int z,
                                                        int originalStartOffset,
                                                        int originalEndExclusive) {
        return projectedRampSurface(
                settings,
                plannedPoints,
                profile,
                start,
                end,
                x,
                z,
                originalStartOffset,
                originalEndExclusive
        ).surfaceTopHalfUnits();
    }

    private static RampProjection projectedRampSurface(RoadPlannerBuildSettings settings,
                                                       List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                       List<RoadCenterlinePoint> profile,
                                                       int start,
                                                       int end,
                                                       int x,
                                                       int z,
                                                       int originalStartOffset,
                                                       int originalEndExclusive) {
        if (profile == null || profile.isEmpty() || plannedPoints == null || plannedPoints.isEmpty()) {
            return new RampProjection(0, Double.MAX_VALUE);
        }
        int safeStart = Math.max(0, Math.min(start, Math.min(profile.size(), plannedPoints.size()) - 1));
        int safeEnd = Math.max(safeStart, Math.min(end, Math.min(profile.size(), plannedPoints.size()) - 1));
        if (safeStart == safeEnd) {
            return new RampProjection(
                    rampSurfaceTopHalfUnits(settings, plannedPoints, profile, safeStart, originalStartOffset, originalEndExclusive),
                    0.0D
            );
        }

        int bestSegment = safeStart;
        double bestT = 0.0D;
        double bestDistSq = Double.MAX_VALUE;
        for (int segment = safeStart; segment < safeEnd; segment++) {
            RoadCenterlinePoint a = profile.get(segment);
            RoadCenterlinePoint b = profile.get(segment + 1);
            double ax = a.pos().getX();
            double az = a.pos().getZ();
            double bx = b.pos().getX();
            double bz = b.pos().getZ();
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D
                    ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lengthSq));
            double projectedX = ax + (dx * t);
            double projectedZ = az + (dz * t);
            double distSq = ((x - projectedX) * (x - projectedX)) + ((z - projectedZ) * (z - projectedZ));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestSegment = segment;
                bestT = t;
            }
        }

        int startHalfUnits = rampSurfaceTopHalfUnits(settings, plannedPoints, profile, bestSegment, originalStartOffset, originalEndExclusive);
        int endHalfUnits = rampSurfaceTopHalfUnits(settings, plannedPoints, profile, bestSegment + 1, originalStartOffset, originalEndExclusive);
        return new RampProjection(
                interpolateHalfUnits(startHalfUnits, endHalfUnits, bestT),
                bestDistSq
        );
    }

    private static int interpolateHalfUnits(int startHalfUnits, int endHalfUnits, double t) {
        double interpolated = startHalfUnits + (Math.max(0.0D, Math.min(1.0D, t)) * (endHalfUnits - startHalfUnits));
        if (endHalfUnits >= startHalfUnits) {
            return (int) Math.floor(interpolated + 1.0E-9D);
        }
        return (int) Math.ceil(interpolated - 1.0E-9D);
    }

    private static BlockPos posAtSurfaceTopHalfUnits(int x, int z, int surfaceTopHalfUnits) {
        return new BlockPos(x, Math.floorDiv(surfaceTopHalfUnits - 1, 2), z);
    }

    private static BlockState rampSurfaceStateForCell(RoadPlannerBuildSettings settings,
                                                      List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                      List<RoadCenterlinePoint> profile,
                                                      int index,
                                                      BlockPos surfacePos,
                                                      int originalStartOffset,
                                                      int originalEndExclusive) {
        if (surfacePos == null) {
            return settings.slabBottomState();
        }
        int start = rampRunStart(plannedPoints, index);
        int end = rampRunEnd(plannedPoints, index);
        int surfaceTopHalfUnits = projectedRampSurfaceTopHalfUnits(
                settings,
                plannedPoints,
                profile,
                start,
                end,
                surfacePos.getX(),
                surfacePos.getZ(),
                originalStartOffset,
                originalEndExclusive
        );
        return (surfaceTopHalfUnits & 1) == 1 ? settings.slabBottomState() : settings.slabTopState();
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

    private static List<RoadCenterlinePoint> legacyRampEmissionProfile(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> plannedPoints,
                                                                       int originalStartOffset,
                                                                       int originalEndExclusive) {
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
            if (ascending && usesEntryRoadTransitionHalfSteps(plannedPoints, start, end, originalStartOffset)) {
                addLegacyAscendingRampProfile(profile, plannedPoints, start, end, originalStartOffset);
            } else if (!ascending && usesExitRoadTransitionHalfSteps(plannedPoints, start, end, originalEndExclusive)) {
                addLegacyDescendingRampProfile(profile, plannedPoints, start, end);
            } else if (!canUseLegacyRampProfile(plannedPoints, start, end, ascending)) {
                for (int rampIndex = start; rampIndex <= end; rampIndex++) {
                    profile.add(plannedPoints.get(rampIndex).point());
                }
            } else if (ascending) {
                addLegacyAscendingRampProfile(profile, plannedPoints, start, end, originalStartOffset);
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
                                                      int end,
                                                      int originalStartOffset) {
        if (usesEntryRoadTransitionHalfSteps(plannedPoints, start, end, originalStartOffset)) {
            int currentHalfUnits = (plannedPoints.get(start).point().targetY() * 2) + 1;
            int deckSideY = end + 1 < plannedPoints.size()
                    ? plannedPoints.get(end + 1).point().targetY()
                    : plannedPoints.get(end).point().targetY();
            int maxRampHalfUnits = (deckSideY * 2) + 1;
            for (int index = start; index <= end; index++) {
                int surfaceHalfUnits = Math.min(currentHalfUnits + (index - start), maxRampHalfUnits);
                profile.add(plannedPoints.get(index).point().withTargetY(Math.floorDiv(surfaceHalfUnits - 1, 2)));
            }
            return;
        }
        int currentY = plannedPoints.get(start).point().targetY();
        for (int index = start; index <= end; index++) {
            int localRampIndex = index - start;
            profile.add(plannedPoints.get(index).point().withTargetY(currentY));
            if ((localRampIndex & 1) == 1) {
                currentY++;
            }
        }
    }

    private static boolean usesEntryRoadTransitionHalfSteps(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points,
                                                           int start,
                                                           int end,
                                                           int originalStartOffset) {
        return start < originalStartOffset
                && originalStartOffset <= end + 1
                && plannedPointRunIsStraight(points)
                && rampRunIsStraight(points, start, end)
                && straightThroughBoundary(points, originalStartOffset);
    }

    private static boolean usesExitRoadTransitionHalfSteps(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points,
                                                          int start,
                                                          int end,
                                                          int originalEndExclusive) {
        return start <= originalEndExclusive
                && originalEndExclusive <= end + 1
                && points != null
                && plannedPointRunIsStraight(points)
                && rampRunIsStraight(points, start, end)
                && straightThroughBoundary(points, originalEndExclusive - 1);
    }

    private static boolean plannedPointRunIsStraight(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points) {
        if (points == null || points.size() <= 2) {
            return true;
        }
        List<RoadCenterlinePoint> centerline = new ArrayList<>(points.size());
        for (RoadPlannerBridgeGeometryPlanner.PlannedPoint point : points) {
            centerline.add(point.point());
        }
        return centerlineRunIsStraight(centerline);
    }

    private static boolean rampRunIsStraight(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int start, int end) {
        if (points == null || start < 0 || end >= points.size() || end <= start) {
            return true;
        }
        BlockPos first = points.get(start).point().pos();
        BlockPos last = points.get(end).point().pos();
        boolean xMajor = Math.abs(last.getX() - first.getX()) >= Math.abs(last.getZ() - first.getZ());
        for (int index = start + 1; index <= end; index++) {
            BlockPos pos = points.get(index).point().pos();
            if (xMajor) {
                if (pos.getZ() != first.getZ()) {
                    return false;
                }
            } else if (pos.getX() != first.getX()) {
                return false;
            }
        }
        return true;
    }

    private static boolean straightThroughBoundary(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int centerIndex) {
        if (points == null || centerIndex <= 0 || centerIndex + 1 >= points.size()) {
            return false;
        }
        return sameCardinalDirection(
                points.get(centerIndex - 1).point().pos(),
                points.get(centerIndex).point().pos(),
                points.get(centerIndex).point().pos(),
                points.get(centerIndex + 1).point().pos()
        );
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
            if (ascending && usesEntryRoadTransitionHalfSteps(points, start, end, originalStartOffset)) {
                return profileRampState(settings, profile, index);
            }
            if (!ascending && usesExitRoadTransitionHalfSteps(points, start, end, originalEndExclusive)) {
                return profileRampState(settings, profile, index);
            }
            if (!canUseLegacyRampProfile(points, start, end, ascending)) {
                return roadTransitionRampState(settings, profileRampState(settings, profile, index), index, originalStartOffset, originalEndExclusive);
            }
            return roadTransitionRampState(settings, rampState(settings, points, index), index, originalStartOffset, originalEndExclusive);
        }
        BlockState deckTransition = deckRampTransitionState(settings, points, profile, index, originalStartOffset, originalEndExclusive);
        if (deckTransition != null) {
            return deckTransition;
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

    private static BlockState deckRampTransitionState(RoadPlannerBuildSettings settings,
                                                      List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points,
                                                      List<RoadCenterlinePoint> profile,
                                                      int index,
                                                      int originalStartOffset,
                                                      int originalEndExclusive) {
        if (settings == null || !isDeckIndex(points, index)) {
            return null;
        }
        int deckY = profileTargetY(profile, points, index);
        if (index > 0
                && points.get(index - 1).phase() == BuildPhase.RAMP
                && deckY > profileTargetY(profile, points, index - 1)
                && rampSurfaceTopHalfUnits(settings, points, profile, index - 1, originalStartOffset, originalEndExclusive) < (deckY * 2) + 1) {
            return settings.slabBottomState();
        }
        if (index + 1 < points.size()
                && points.get(index + 1).phase() == BuildPhase.RAMP
                && deckY > profileTargetY(profile, points, index + 1)
                && rampSurfaceTopHalfUnits(settings, points, profile, index + 1, originalStartOffset, originalEndExclusive) < (deckY * 2) + 1) {
            return settings.slabBottomState();
        }
        return null;
    }

    private static int profileTargetY(List<RoadCenterlinePoint> profile,
                                      List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points,
                                      int index) {
        if (profile != null && index >= 0 && index < profile.size()) {
            return profile.get(index).targetY();
        }
        return points.get(index).point().targetY();
    }

    private static boolean isDeckIndex(List<RoadPlannerBridgeGeometryPlanner.PlannedPoint> points, int index) {
        if (points == null || index < 0 || index >= points.size() || points.get(index).phase() != BuildPhase.DECK) {
            return false;
        }
        return true;
    }

    private static int addOverheadClearance(List<BuildStep> steps, BlockPos surfacePos, int order) {
        for (int dy = 1; dy <= CLEARANCE_HEIGHT; dy++) {
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
        if (index + 2 < profile.size() && y == nextY && profile.get(index + 2).targetY() > y) {
            return settings.slabBottomState();
        }
        if (index > 1 && y == prevY && profile.get(index - 2).targetY() > y) {
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
        List<BlockPos> railPositions = bridgeRailingPositions(points, index, center, settings.width());
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
        List<BlockPos> positions = bridgeRailingPositions(points, index, center, settings.width());
        List<BuildStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new BuildStep(startOrder + steps.size(), pos, rail, BuildPhase.RAILING));
        }
        return List.copyOf(steps);
    }

    private static List<BlockPos> bridgeRailingPositions(List<RoadCenterlinePoint> points, int index, BlockPos center, int width) {
        if (points == null || points.isEmpty() || index < 0 || index >= points.size() || center == null) {
            return List.of();
        }
        int halfWidth = Math.max(1, width / 2);
        Direction perpDir = getDirection(points, index).getClockWise();
        return List.of(
                center.offset(perpDir.getStepX() * -(halfWidth + 1), 1, perpDir.getStepZ() * -(halfWidth + 1)),
                center.offset(perpDir.getStepX() * (halfWidth + 1), 1, perpDir.getStepZ() * (halfWidth + 1))
        );
    }

    private record RampBasis(int normalX, int normalZ) {
    }

    private record RampCell(int index, BlockPos pos, int surfaceTopHalfUnits, double distanceSq) {
    }

    private record RampProjection(int surfaceTopHalfUnits, double distanceSq) {
    }
}
