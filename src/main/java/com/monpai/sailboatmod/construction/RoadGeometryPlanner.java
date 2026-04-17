package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class RoadGeometryPlanner {
    private static final int MAX_SLOPE_STEP_PER_THREE_SEGMENTS = 1;
    private static final int DEFAULT_NAVIGABLE_BRIDGE_RAMP_SEGMENTS = 4;
    private static final double STAIR_TRAVEL_BAND_MAX_DIST_SQ = 2.25D;
    private static final int ARCHED_CROWN_HEIGHT_SHORT = 2;
    private static final int ARCHED_CROWN_HEIGHT_LONG = 3;
    private static final int BASE_RIBBON_HALF_WIDTH = 3;
    private static final double SHARP_TURN_MIN_ANGLE_RADIANS = Math.PI / 4.0D;
    private static final double VERY_SHARP_TURN_MIN_ANGLE_RADIANS = Math.PI * 0.75D;

    private RoadGeometryPlanner() {
    }

    public static RoadGeometryPlan plan(List<BlockPos> centerPath, Function<BlockPos, BlockState> blockStateSupplier) {
        return plan(centerPath, blockStateSupplier, List.of());
    }

    public static RoadGeometryPlan plan(RoadCorridorPlan corridorPlan,
                                        Function<BlockPos, BlockState> blockStateSupplier) {
        Objects.requireNonNull(corridorPlan, "corridorPlan");
        Objects.requireNonNull(blockStateSupplier, "blockStateSupplier");
        if (!isUsableCorridorPlan(corridorPlan)) {
            return new RoadGeometryPlan(List.of(), List.of());
        }

        LinkedHashMap<Long, GhostCandidate> ghostByPos = new LinkedHashMap<>();
        for (RoadCorridorPlan.CorridorSlice slice : corridorPlan.slices()) {
            for (GhostRoadBlock ghostBlock : sliceGhostBlocks(corridorPlan, slice.index(), blockStateSupplier)) {
                addGhost(ghostByPos, ghostBlock.pos(), ghostBlock.state(), corridorPlan.centerPath().get(slice.index()), slice.index());
            }
        }
        for (int i = 1; i < corridorPlan.slices().size(); i++) {
            for (GhostRoadBlock connector : connectAdjacentSlices(corridorPlan, i - 1, i, blockStateSupplier)) {
                addGhost(ghostByPos, connector.pos(), connector.state(), corridorPlan.centerPath().get(i), i);
            }
        }

        List<GhostRoadBlock> ghostBlocks = new ArrayList<>(ghostByPos.size());
        for (GhostCandidate candidate : ghostByPos.values()) {
            ghostBlocks.add(new GhostRoadBlock(candidate.pos(), candidate.state()));
        }
        ghostBlocks = enforceTurningSlopeSlabStates(corridorPlan.centerPath(), corridorDeckHeights(corridorPlan), ghostBlocks);
        ghostBlocks = List.copyOf(ghostBlocks);
        List<RoadBuildStep> buildSteps = new ArrayList<>(ghostBlocks.size());
        for (int i = 0; i < ghostBlocks.size(); i++) {
            GhostRoadBlock ghost = ghostBlocks.get(i);
            buildSteps.add(new RoadBuildStep(i, ghost.pos(), ghost.state()));
        }
        return new RoadGeometryPlan(ghostBlocks, List.copyOf(buildSteps));
    }

    public static RoadGeometryPlan plan(List<BlockPos> centerPath,
                                        Function<BlockPos, BlockState> blockStateSupplier,
                                        List<RoadBridgePlanner.BridgeProfile> bridgeProfiles) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(blockStateSupplier, "blockStateSupplier");
        Objects.requireNonNull(bridgeProfiles, "bridgeProfiles");
        if (centerPath.isEmpty()) {
            return new RoadGeometryPlan(List.of(), List.of());
        }

        List<BlockPos> path = new ArrayList<>(centerPath.size());
        for (int i = 0; i < centerPath.size(); i++) {
            path.add(Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i).immutable());
        }
        int[] sampledHeights = samplePlacementHeights(path);
        int[] placementHeights = buildPlacementHeightProfile(path, bridgeProfiles);

        LinkedHashMap<Long, GhostCandidate> ghostByPos = new LinkedHashMap<>();
        for (int i = 0; i < path.size(); i++) {
            boolean stairSegment = isStairSegment(sampledHeights, i);
            List<BlockPos> slicePositions = slicePositions(path, placementHeights, i);
            BlockPos sourceCenter = path.get(i);
            for (BlockPos pos : slicePositions) {
                BlockState state = resolveState(path, placementHeights, i, pos, stairSegment, blockStateSupplier);
                addGhost(ghostByPos, pos, state, sourceCenter, i);
            }
        }

        List<GhostRoadBlock> ghostBlocks = new ArrayList<>(ghostByPos.size());
        for (GhostCandidate candidate : ghostByPos.values()) {
            ghostBlocks.add(new GhostRoadBlock(candidate.pos(), candidate.state()));
        }
        ghostBlocks = enforceTurningSlopeSlabStates(path, placementHeights, ghostBlocks);
        ghostBlocks = List.copyOf(ghostBlocks);
        List<RoadBuildStep> buildSteps = new ArrayList<>(ghostBlocks.size());
        for (int i = 0; i < ghostBlocks.size(); i++) {
            GhostRoadBlock ghost = ghostBlocks.get(i);
            buildSteps.add(new RoadBuildStep(i, ghost.pos(), ghost.state()));
        }
        return new RoadGeometryPlan(ghostBlocks, List.copyOf(buildSteps));
    }

    public static List<BlockPos> slicePositions(List<BlockPos> centerPath, int index) {
        return slicePositions(centerPath, buildPlacementHeightProfile(centerPath), index);
    }

    public static List<BlockPos> slicePositions(RoadCorridorPlan corridorPlan, int index) {
        Objects.requireNonNull(corridorPlan, "corridorPlan");
        if (!isUsableCorridorPlan(corridorPlan)) {
            return List.of();
        }
        if (index < 0 || index >= corridorPlan.slices().size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + corridorPlan.slices().size());
        }
        RoadCorridorPlan.CorridorSlice slice = corridorPlan.slices().get(index);
        if (slice.index() != index) {
            return List.of();
        }
        if (!slice.surfacePositions().isEmpty() && !usesSlopeSemantics(slice.segmentKind())) {
            return slice.surfacePositions();
        }
        int[] deckHeights = corridorDeckHeights(corridorPlan);
        if (usesSlopeSemantics(slice.segmentKind())) {
            return slicePositionsAtFixedHeight(sliceFootprint(corridorPlan, index), deckHeights[index]);
        }
        return slicePositions(corridorPlan.centerPath(), deckHeights, index);
    }

    public static List<GhostRoadBlock> sliceGhostBlocks(RoadCorridorPlan corridorPlan,
                                                        int index,
                                                        Function<BlockPos, BlockState> blockStateSupplier) {
        Objects.requireNonNull(corridorPlan, "corridorPlan");
        Objects.requireNonNull(blockStateSupplier, "blockStateSupplier");
        if (!isUsableCorridorPlan(corridorPlan)) {
            return List.of();
        }
        if (index < 0 || index >= corridorPlan.slices().size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + corridorPlan.slices().size());
        }
        RoadCorridorPlan.CorridorSlice slice = corridorPlan.slices().get(index);
        if (slice.index() != index) {
            return List.of();
        }
        int[] placementHeights = corridorDeckHeights(corridorPlan);
        boolean stairSegment = shouldUseCorridorStairState(corridorPlan, index, placementHeights);
        List<GhostRoadBlock> ghostBlocks = new ArrayList<>();
        for (BlockPos pos : slicePositions(corridorPlan, index)) {
            BlockState sourceState = Objects.requireNonNull(blockStateSupplier.apply(pos), "blockStateSupplier returned null for pos " + pos);
            if (slice.segmentKind() == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD_PLATFORM) {
                ghostBlocks.add(new GhostRoadBlock(pos, fullBlockStateForFamily(sourceState)));
                continue;
            }
            if (slice.segmentKind() == RoadCorridorPlan.SegmentKind.APPROACH_RAMP) {
                ghostBlocks.add(new GhostRoadBlock(pos, slabStateForFamily(sourceState)));
                continue;
            }
            ghostBlocks.add(new GhostRoadBlock(
                    pos,
                    resolveState(corridorPlan.centerPath(), placementHeights, index, pos, stairSegment, ignored -> sourceState)
            ));
        }
        return List.copyOf(ghostBlocks);
    }

    private static List<GhostRoadBlock> connectAdjacentSlices(RoadCorridorPlan corridorPlan,
                                                              int previousIndex,
                                                              int currentIndex,
                                                              Function<BlockPos, BlockState> blockStateSupplier) {
        List<BlockPos> previous = slicePositions(corridorPlan, previousIndex);
        List<BlockPos> current = slicePositions(corridorPlan, currentIndex);
        if (touchesOrOverlaps(previous, current)) {
            return List.of();
        }
        List<GhostRoadBlock> connectors = new ArrayList<>();
        for (BlockPos currentPos : current) {
            BlockPos nearest = nearestHorizontalMatch(currentPos, previous);
            if (nearest == null || horizontalDistance(currentPos, nearest) > 1) {
                continue;
            }
            GhostRoadBlock stairConnector = buildTransitionConnector(
                    corridorPlan,
                    previousIndex,
                    currentIndex,
                    nearest,
                    currentPos,
                    blockStateSupplier
            );
            if (stairConnector != null) {
                connectors.add(stairConnector);
                continue;
            }
            for (BlockPos connectorPos : connectorPositions(nearest, currentPos)) {
                connectors.add(new GhostRoadBlock(
                        connectorPos,
                        Objects.requireNonNull(blockStateSupplier.apply(connectorPos), "blockStateSupplier returned null for pos " + connectorPos)
                ));
            }
        }
        return connectors.isEmpty() ? List.of() : List.copyOf(connectors);
    }

    public static int[] buildPlacementHeightProfile(List<BlockPos> centerPath) {
        return buildPlacementHeightProfile(centerPath, List.of());
    }

    public static int[] buildPlacementHeightProfile(List<BlockPos> centerPath,
                                                    List<RoadBridgePlanner.BridgeProfile> bridgeProfiles) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(bridgeProfiles, "bridgeProfiles");
        if (centerPath.isEmpty()) {
            return new int[0];
        }
        int[] sampledHeights = samplePlacementHeights(centerPath);
        int[] smoothed = smoothPlacementHeights(sampledHeights);
        smoothed = smoothPlacementHeights(flattenTurningSlopeHeights(centerPath, smoothed));
        int[] bridged = applyBridgeProfiles(smoothed, bridgeProfiles);
        int[] transitioned = propagateBridgeApproachHeights(bridged, bridgeProfiles);
        return constrainToTerrainEnvelope(transitioned, sampledHeights, bridgeProfiles);
    }

    public static int[] buildPlacementHeightProfileFromSpanPlans(List<BlockPos> centerPath,
                                                                 List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(bridgePlans, "bridgePlans");
        if (centerPath.isEmpty()) {
            return new int[0];
        }
        int[] sampledHeights = samplePlacementHeights(centerPath);
        int[] smoothed = smoothPlacementHeights(sampledHeights);
        smoothed = smoothPlacementHeights(flattenTurningSlopeHeights(centerPath, smoothed));
        int[] adjusted = applyBridgeSpanPlans(smoothed, bridgePlans);
        adjusted = propagateBridgeApproachHeightsFromSpanPlans(adjusted, bridgePlans);
        return constrainToTerrainEnvelopeFromSpanPlans(adjusted, sampledHeights, bridgePlans);
    }

    public static int interpolatePlacementHeight(int x, int z, List<BlockPos> centerPath, int[] placementHeights) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(placementHeights, "placementHeights");
        if (centerPath.isEmpty() || placementHeights.length == 0) {
            return 64;
        }
        if (placementHeights.length != centerPath.size()) {
            throw new IllegalArgumentException("placementHeights size must match centerPath size");
        }
        if (centerPath.size() == 1) {
            return placementHeights[0];
        }

        Projection projection = findNearestProjection(x, z, centerPath);
        return interpolateHeight(projection.segmentIndex(), projection.t(), placementHeights);
    }

    public static boolean shouldUseSlabTransition(int x, int z, int currentY, List<BlockPos> centerPath, int[] placementHeights) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(placementHeights, "placementHeights");
        int yAhead = interpolatePlacementHeight(x + 1, z, centerPath, placementHeights);
        int yBehind = interpolatePlacementHeight(x - 1, z, centerPath, placementHeights);
        int yLeft = interpolatePlacementHeight(x, z + 1, centerPath, placementHeights);
        int yRight = interpolatePlacementHeight(x, z - 1, centerPath, placementHeights);

        boolean needsSlabX = (yAhead > currentY && yBehind >= currentY)
                || (yBehind > currentY && yAhead >= currentY);
        boolean needsSlabZ = (yLeft > currentY && yRight >= currentY)
                || (yRight > currentY && yLeft >= currentY);
        return needsSlabX || needsSlabZ;
    }

    public static boolean isWithinStairTravelBand(int x, int z, List<BlockPos> centerPath) {
        Objects.requireNonNull(centerPath, "centerPath");
        if (centerPath.isEmpty()) {
            return false;
        }
        if (centerPath.size() == 1) {
            BlockPos only = centerPath.get(0);
            int dx = x - only.getX();
            int dz = z - only.getZ();
            return (dx * dx + dz * dz) <= STAIR_TRAVEL_BAND_MAX_DIST_SQ;
        }
        return findNearestProjection(x, z, centerPath).distanceSq() <= STAIR_TRAVEL_BAND_MAX_DIST_SQ;
    }

    public static SlopeProfile analyzeSlopeProfile(List<BlockPos> centerPath) {
        Objects.requireNonNull(centerPath, "centerPath");
        if (centerPath.size() < 2) {
            return new SlopeProfile(false, 0, 0);
        }
        int[] placementHeights = samplePlacementHeights(centerPath);
        int consecutiveSteepSteps = 0;
        int maxRise = 0;
        for (int i = 1; i < placementHeights.length; i++) {
            int rise = placementHeights[i] - placementHeights[i - 1];
            maxRise = Math.max(maxRise, Math.abs(rise));
            if (Math.abs(rise) >= 1) {
                consecutiveSteepSteps++;
            } else {
                consecutiveSteepSteps = 0;
            }
            if (consecutiveSteepSteps >= 2) {
                return new SlopeProfile(true, consecutiveSteepSteps, maxRise);
            }
        }
        return new SlopeProfile(false, consecutiveSteepSteps, maxRise);
    }

    public static RibbonSlice buildRibbonSlice(List<BlockPos> centerPath, int index) {
        Objects.requireNonNull(centerPath, "centerPath");
        if (index < 0 || index >= centerPath.size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + centerPath.size());
        }

        BlockPos surface = Objects.requireNonNull(centerPath.get(index), "centerPath contains null at index " + index);
        BlockPos current = new BlockPos(surface.getX(), 0, surface.getZ());
        RibbonBasis basis = resolveRibbonBasis(centerPath, index);

        int insideHalfWidth = BASE_RIBBON_HALF_WIDTH;
        int outsideSign = Integer.signum(basis.turnSign());
        int outsideWidening = outsideSign == 0
                ? 0
                : resolveOutsideWidening(
                basis.incomingX(),
                basis.incomingZ(),
                basis.outgoingX(),
                basis.outgoingZ()
        );
        int outsideHalfWidth = BASE_RIBBON_HALF_WIDTH + outsideWidening;

        LinkedHashSet<BlockPos> columns = new LinkedHashSet<>();
        columns.add(current);
        if (outsideSign == 0) {
            addRibbonSide(columns, current, basis.normalX(), basis.normalZ(), insideHalfWidth, 1);
            addRibbonSide(columns, current, basis.normalX(), basis.normalZ(), insideHalfWidth, -1);
        } else {
            addRibbonSide(columns, current, basis.normalX(), basis.normalZ(), outsideHalfWidth, outsideSign);
            addRibbonSide(columns, current, basis.normalX(), basis.normalZ(), insideHalfWidth, -outsideSign);
        }

        return new RibbonSlice(List.copyOf(columns), insideHalfWidth, outsideHalfWidth);
    }

    private static List<BlockPos> slicePositions(List<BlockPos> centerPath, int[] placementHeights, int index) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(placementHeights, "placementHeights");
        if (index < 0 || index >= centerPath.size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + centerPath.size());
        }
        if (placementHeights.length != centerPath.size()) {
            throw new IllegalArgumentException("placementHeights size must match centerPath size");
        }

        LinkedHashSet<BlockPos> columns = new LinkedHashSet<>(buildRibbonSlice(centerPath, index).columns());
        List<BlockPos> resolved = new ArrayList<>(columns.size());
        for (BlockPos column : columns) {
            int y = interpolatePlacementHeight(column.getX(), column.getZ(), centerPath, placementHeights);
            resolved.add(new BlockPos(column.getX(), y, column.getZ()));
        }
        return List.copyOf(resolved);
    }

    private static List<BlockPos> sliceFootprint(RoadCorridorPlan corridorPlan, int index) {
        if (corridorPlan == null || index < 0 || index >= corridorPlan.slices().size()) {
            return List.of();
        }
        RoadCorridorPlan.CorridorSlice slice = corridorPlan.slices().get(index);
        if (slice != null && slice.surfacePositions() != null && !slice.surfacePositions().isEmpty()) {
            LinkedHashSet<BlockPos> footprint = new LinkedHashSet<>();
            for (BlockPos pos : slice.surfacePositions()) {
                if (pos != null) {
                    footprint.add(new BlockPos(pos.getX(), 0, pos.getZ()));
                }
            }
            if (!footprint.isEmpty()) {
                return List.copyOf(footprint);
            }
        }
        return buildRibbonSlice(corridorPlan.centerPath(), index).columns();
    }

    private static List<BlockPos> slicePositionsAtFixedHeight(List<BlockPos> footprint, int y) {
        if (footprint == null || footprint.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> resolved = new ArrayList<>(footprint.size());
        for (BlockPos column : footprint) {
            if (column == null) {
                continue;
            }
            resolved.add(new BlockPos(column.getX(), y, column.getZ()));
        }
        return List.copyOf(resolved);
    }

    private static void addRibbonSide(LinkedHashSet<BlockPos> columns,
                                      BlockPos center,
                                      int normalX,
                                      int normalZ,
                                      int halfWidth,
                                      int sideSign) {
        int centerX = center.getX();
        int centerZ = center.getZ();
        int y = center.getY();
        int lastX = centerX;
        int lastZ = centerZ;
        for (int step = 1; step <= halfWidth; step++) {
            int targetX = centerX + (normalX * step * sideSign);
            int targetZ = centerZ + (normalZ * step * sideSign);
            addContinuousOffsetPath(columns, y, lastX, lastZ, targetX, targetZ);
            lastX = targetX;
            lastZ = targetZ;
        }
    }

    private static void addContinuousOffsetPath(LinkedHashSet<BlockPos> columns,
                                                int y,
                                                int fromX,
                                                int fromZ,
                                                int targetX,
                                                int targetZ) {
        int currentX = fromX;
        int currentZ = fromZ;
        while (currentX != targetX || currentZ != targetZ) {
            boolean moveX = currentX != targetX;
            boolean moveZ = currentZ != targetZ;
            if (moveX && moveZ) {
                currentX += Integer.compare(targetX, currentX);
                columns.add(new BlockPos(currentX, y, currentZ));
                currentZ += Integer.compare(targetZ, currentZ);
                columns.add(new BlockPos(currentX, y, currentZ));
                continue;
            }
            if (moveX) {
                currentX += Integer.compare(targetX, currentX);
            } else {
                currentZ += Integer.compare(targetZ, currentZ);
            }
            columns.add(new BlockPos(currentX, y, currentZ));
        }
    }

    private static RibbonBasis resolveRibbonBasis(List<BlockPos> centerPath, int index) {
        BlockPos surface = Objects.requireNonNull(centerPath.get(index), "centerPath contains null at index " + index);
        BlockPos previousSurface = index > 0
                ? Objects.requireNonNull(centerPath.get(index - 1), "centerPath contains null at index " + (index - 1))
                : surface;
        BlockPos nextSurface = index + 1 < centerPath.size()
                ? Objects.requireNonNull(centerPath.get(index + 1), "centerPath contains null at index " + (index + 1))
                : surface;

        int incomingX = Integer.compare(surface.getX() - previousSurface.getX(), 0);
        int incomingZ = Integer.compare(surface.getZ() - previousSurface.getZ(), 0);
        int outgoingX = Integer.compare(nextSurface.getX() - surface.getX(), 0);
        int outgoingZ = Integer.compare(nextSurface.getZ() - surface.getZ(), 0);

        int tangentX = Integer.compare(nextSurface.getX() - previousSurface.getX(), 0);
        int tangentZ = Integer.compare(nextSurface.getZ() - previousSurface.getZ(), 0);
        if (tangentX == 0 && tangentZ == 0) {
            if (outgoingX != 0 || outgoingZ != 0) {
                tangentX = outgoingX;
                tangentZ = outgoingZ;
            } else if (incomingX != 0 || incomingZ != 0) {
                tangentX = incomingX;
                tangentZ = incomingZ;
            } else {
                tangentX = 1;
            }
        }

        int normalX = -tangentZ;
        int normalZ = tangentX;
        if (normalX == 0 && normalZ == 0) {
            normalZ = 1;
        }
        int turnSign = Integer.signum(incomingX * outgoingZ - incomingZ * outgoingX);
        return new RibbonBasis(normalX, normalZ, incomingX, incomingZ, outgoingX, outgoingZ, turnSign);
    }

    private static int resolveOutsideWidening(int incomingX, int incomingZ, int outgoingX, int outgoingZ) {
        if ((incomingX == 0 && incomingZ == 0) || (outgoingX == 0 && outgoingZ == 0)) {
            return 0;
        }
        double incomingLength = Math.sqrt((incomingX * incomingX) + (incomingZ * incomingZ));
        double outgoingLength = Math.sqrt((outgoingX * outgoingX) + (outgoingZ * outgoingZ));
        if (incomingLength < 1.0E-9D || outgoingLength < 1.0E-9D) {
            return 0;
        }
        double dot = ((incomingX * outgoingX) + (incomingZ * outgoingZ)) / (incomingLength * outgoingLength);
        double angle = Math.acos(clamp(dot, -1.0D, 1.0D));
        if (angle >= VERY_SHARP_TURN_MIN_ANGLE_RADIANS) {
            return 2;
        }
        if (angle >= SHARP_TURN_MIN_ANGLE_RADIANS) {
            return 1;
        }
        return 0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isUsableCorridorPlan(RoadCorridorPlan corridorPlan) {
        if (corridorPlan == null || !corridorPlan.valid()) {
            return false;
        }
        List<BlockPos> centerPath = corridorPlan.centerPath();
        List<RoadCorridorPlan.CorridorSlice> slices = corridorPlan.slices();
        if (centerPath.isEmpty() || centerPath.size() != slices.size()) {
            return false;
        }
        for (int i = 0; i < slices.size(); i++) {
            RoadCorridorPlan.CorridorSlice slice = slices.get(i);
            if (slice == null || slice.index() != i) {
                return false;
            }
        }
        return true;
    }

    private static int[] corridorDeckHeights(RoadCorridorPlan corridorPlan) {
        int[] placementHeights = new int[corridorPlan.slices().size()];
        for (int i = 0; i < corridorPlan.slices().size(); i++) {
            placementHeights[i] = corridorPlan.slices().get(i).deckCenter().getY();
        }
        for (int i = 1; i < placementHeights.length; i++) {
            if (!usesSlopeTransition(corridorPlan, i - 1, i)) {
                continue;
            }
            int previous = placementHeights[i - 1];
            placementHeights[i] = clampHeightStep(placementHeights[i], previous);
        }
        for (int i = placementHeights.length - 2; i >= 0; i--) {
            if (!usesSlopeTransition(corridorPlan, i, i + 1)) {
                continue;
            }
            int next = placementHeights[i + 1];
            placementHeights[i] = clampHeightStep(placementHeights[i], next);
        }
        return placementHeights;
    }

    private static boolean usesSlopeTransition(RoadCorridorPlan corridorPlan, int leftIndex, int rightIndex) {
        if (corridorPlan == null
                || leftIndex < 0
                || rightIndex < 0
                || leftIndex >= corridorPlan.slices().size()
                || rightIndex >= corridorPlan.slices().size()) {
            return false;
        }
        return usesSlopeSemantics(corridorPlan.slices().get(leftIndex).segmentKind())
                || usesSlopeSemantics(corridorPlan.slices().get(rightIndex).segmentKind());
    }

    private static int clampHeightStep(int current, int neighbor) {
        if (current > neighbor + 1) {
            return neighbor + 1;
        }
        if (current < neighbor - 1) {
            return neighbor - 1;
        }
        return current;
    }

    private static boolean usesSlopeSemantics(RoadCorridorPlan.SegmentKind segmentKind) {
        return segmentKind == RoadCorridorPlan.SegmentKind.TOWN_CONNECTION
                || segmentKind == RoadCorridorPlan.SegmentKind.LAND_APPROACH
                || segmentKind == RoadCorridorPlan.SegmentKind.APPROACH_RAMP
                || segmentKind == RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH
                || segmentKind == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD;
    }

    private static boolean shouldUseCorridorStairState(RoadCorridorPlan corridorPlan,
                                                       int index,
                                                       int[] placementHeights) {
        RoadCorridorPlan.SegmentKind segmentKind = corridorPlan.slices().get(index).segmentKind();
        if (!usesSlopeSemantics(segmentKind)) {
            return false;
        }
        if (isStairSegment(placementHeights, index)) {
            return true;
        }
        int current = placementHeights[index];
        int previous = index > 0 ? placementHeights[index - 1] : current;
        int next = index + 1 < placementHeights.length ? placementHeights[index + 1] : current;
        int riseIn = current - previous;
        int riseOut = next - current;
        return Math.abs(riseIn) == 1 || Math.abs(riseOut) == 1;
    }

    private static void addGhost(LinkedHashMap<Long, GhostCandidate> ghostByPos,
                                 BlockPos pos,
                                 BlockState state,
                                 BlockPos sourceCenter,
                                 int sourceIndex) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sourceCenter, "sourceCenter");

        int dx = pos.getX() - sourceCenter.getX();
        int dz = pos.getZ() - sourceCenter.getZ();
        int distanceSq = (dx * dx) + (dz * dz);
        GhostCandidate incoming = new GhostCandidate(
                pos.immutable(),
                state,
                sourceIndex,
                distanceSq,
                isPreferredTransitionState(state)
        );
        long key = pos.asLong();
        GhostCandidate existing = ghostByPos.get(key);
        if (existing == null) {
            ghostByPos.put(key, incoming);
            return;
        }
        ghostByPos.put(key, pickPreferredCandidate(existing, incoming));
    }

    private static GhostCandidate pickPreferredCandidate(GhostCandidate first, GhostCandidate second) {
        if (first.stairState() != second.stairState()) {
            return second.stairState() ? second : first;
        }
        if (first.sourceDistanceSq() != second.sourceDistanceSq()) {
            return second.sourceDistanceSq() < first.sourceDistanceSq() ? second : first;
        }
        if (first.sourceIndex() != second.sourceIndex()) {
            return second.sourceIndex() < first.sourceIndex() ? second : first;
        }
        return first;
    }

    private static boolean touchesOrOverlaps(List<BlockPos> first, List<BlockPos> second) {
        for (BlockPos left : first) {
            for (BlockPos right : second) {
                int dx = Math.abs(left.getX() - right.getX());
                int dy = Math.abs(left.getY() - right.getY());
                int dz = Math.abs(left.getZ() - right.getZ());
                if (dx + dy + dz <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos nearestHorizontalMatch(BlockPos target, List<BlockPos> candidates) {
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BlockPos candidate : candidates) {
            int distance = horizontalDistance(target, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX()) + Math.abs(first.getZ() - second.getZ());
    }

    private static List<BlockPos> connectorPositions(BlockPos from, BlockPos to) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        int bridgeY = Math.max(from.getY(), to.getY());
        int x = from.getX();
        int z = from.getZ();
        while (x != to.getX()) {
            x += Integer.compare(to.getX(), x);
            positions.add(new BlockPos(x, bridgeY, z));
        }
        while (z != to.getZ()) {
            z += Integer.compare(to.getZ(), z);
            positions.add(new BlockPos(x, bridgeY, z));
        }
        int minY = Math.min(bridgeY, to.getY());
        int maxY = Math.max(bridgeY, to.getY());
        for (int y = minY; y <= maxY; y++) {
            positions.add(new BlockPos(to.getX(), y, to.getZ()));
        }
        return List.copyOf(positions);
    }

    private static GhostRoadBlock buildTransitionConnector(RoadCorridorPlan corridorPlan,
                                                           int previousIndex,
                                                           int currentIndex,
                                                           BlockPos previousPos,
                                                           BlockPos currentPos,
                                                           Function<BlockPos, BlockState> blockStateSupplier) {
        if (Math.abs(previousPos.getY() - currentPos.getY()) != 1 || horizontalDistance(previousPos, currentPos) != 1) {
            return null;
        }
        boolean previousLower = previousPos.getY() < currentPos.getY();
        BlockPos lowerPos = previousLower ? previousPos : currentPos;
        int lowerIndex = previousLower ? previousIndex : currentIndex;
        BlockPos higherPos = previousLower ? currentPos : previousPos;
        BlockState transitionState = slabStateForFamily(
                Objects.requireNonNull(blockStateSupplier.apply(lowerPos), "blockStateSupplier returned null for pos " + lowerPos)
        );
        return isPreferredTransitionState(transitionState)
                ? new GhostRoadBlock(lowerPos, transitionState)
                : null;
    }

    private static int[] samplePlacementHeights(List<BlockPos> centerPath) {
        int[] sampled = new int[centerPath.size()];
        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos pos = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i);
            sampled[i] = pos.getY() + 1;
        }
        return sampled;
    }

    private static boolean isStairSegment(int[] placementHeights, int index) {
        if (placementHeights.length < 3) {
            return false;
        }
        int current = placementHeights[index];
        int previous = index > 0 ? placementHeights[index - 1] : current;
        int next = index + 1 < placementHeights.length ? placementHeights[index + 1] : current;
        int riseIn = current - previous;
        int riseOut = next - current;
        if (riseIn != 0 && riseOut != 0) {
            return Integer.signum(riseIn) == Integer.signum(riseOut);
        }
        if (riseIn != 0 && riseOut == 0 && index > 1) {
            int earlier = placementHeights[index - 2];
            int priorRise = previous - earlier;
            return priorRise != 0 && Integer.signum(priorRise) == Integer.signum(riseIn);
        }
        return false;
    }

    private static BlockState resolveState(List<BlockPos> path,
                                           int[] placementHeights,
                                           int index,
                                           BlockPos currentPos,
                                           boolean stairSegment,
                                           Function<BlockPos, BlockState> blockStateSupplier) {
        BlockState state = Objects.requireNonNull(blockStateSupplier.apply(currentPos), "blockStateSupplier returned null for pos " + currentPos);
        if (isTurningSlopeSegment(path, index, placementHeights)) {
            return slabStateForFamily(state);
        }
        if (!stairSegment || !isWithinStairTravelBand(currentPos.getX(), currentPos.getZ(), path)) {
            return state;
        }
        if (isAmbiguousDiagonalClimb(path, index)) {
            return state;
        }
        if (shouldUseSlabTransition(currentPos.getX(), currentPos.getZ(), currentPos.getY(), path, placementHeights)) {
            return slabStateForFamily(state);
        }
        return fullBlockStateForFamily(state);
    }

    private static boolean isAmbiguousDiagonalClimb(List<BlockPos> path, int index) {
        if (path == null || path.isEmpty() || index < 0 || index >= path.size()) {
            return false;
        }
        BlockPos current = path.get(index);
        BlockPos previous = index > 0 ? path.get(index - 1) : current;
        BlockPos next = index + 1 < path.size() ? path.get(index + 1) : current;
        return isDiagonalHorizontalStep(previous, current) || isDiagonalHorizontalStep(current, next);
    }

    private static boolean isDiagonalHorizontalStep(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return false;
        }
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        return dx > 0 && dz > 0;
    }

    private static boolean isTurningSlopeSegment(List<BlockPos> path, int index, int[] placementHeights) {
        if (path == null || placementHeights == null || index <= 0 || index >= path.size() - 1 || placementHeights.length != path.size()) {
            return false;
        }
        BlockPos previous = path.get(index - 1);
        BlockPos current = path.get(index);
        BlockPos next = path.get(index + 1);
        int incomingX = Integer.compare(current.getX() - previous.getX(), 0);
        int incomingZ = Integer.compare(current.getZ() - previous.getZ(), 0);
        int outgoingX = Integer.compare(next.getX() - current.getX(), 0);
        int outgoingZ = Integer.compare(next.getZ() - current.getZ(), 0);
        if ((incomingX == outgoingX && incomingZ == outgoingZ)
                || (incomingX == 0 && incomingZ == 0)
                || (outgoingX == 0 && outgoingZ == 0)) {
            return false;
        }
        int riseIn = placementHeights[index] - placementHeights[index - 1];
        int riseOut = placementHeights[index + 1] - placementHeights[index];
        return riseIn != 0 || riseOut != 0;
    }

    private static BlockState slabStateForFamily(BlockState state) {
        if (state == null) {
            return Blocks.AIR.defaultBlockState();
        }
        if (state.is(Blocks.STONE_BRICKS) || state.is(Blocks.STONE_BRICK_SLAB) || state.is(Blocks.STONE_BRICK_STAIRS)) {
            return Blocks.STONE_BRICK_SLAB.defaultBlockState();
        }
        if (state.is(Blocks.SMOOTH_SANDSTONE_SLAB) || state.is(Blocks.SMOOTH_SANDSTONE_STAIRS)) {
            return Blocks.SMOOTH_SANDSTONE_SLAB.defaultBlockState();
        }
        if (state.is(Blocks.MUD_BRICK_SLAB) || state.is(Blocks.MUD_BRICK_STAIRS)) {
            return Blocks.MUD_BRICK_SLAB.defaultBlockState();
        }
        if (state.is(Blocks.SPRUCE_SLAB) || state.is(Blocks.SPRUCE_STAIRS)) {
            return Blocks.SPRUCE_SLAB.defaultBlockState();
        }
        return state;
    }

    private static BlockState fullBlockStateForFamily(BlockState state) {
        if (state == null) {
            return Blocks.AIR.defaultBlockState();
        }
        if (state.is(Blocks.STONE_BRICK_SLAB) || state.is(Blocks.STONE_BRICK_STAIRS)) {
            return Blocks.STONE_BRICKS.defaultBlockState();
        }
        if (state.is(Blocks.SMOOTH_SANDSTONE_SLAB) || state.is(Blocks.SMOOTH_SANDSTONE_STAIRS)) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (state.is(Blocks.MUD_BRICK_SLAB) || state.is(Blocks.MUD_BRICK_STAIRS)) {
            return Blocks.MUD_BRICKS.defaultBlockState();
        }
        if (state.is(Blocks.SPRUCE_SLAB) || state.is(Blocks.SPRUCE_STAIRS)) {
            return Blocks.SPRUCE_PLANKS.defaultBlockState();
        }
        return state;
    }

    private static boolean isPreferredTransitionState(BlockState state) {
        return state != null
                && (state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || state.is(Blocks.MUD_BRICK_SLAB)
                || state.is(Blocks.SPRUCE_SLAB));
    }

    private static int[] smoothPlacementHeights(int[] baseHeights) {
        int[] smoothed = baseHeights.clone();
        for (int i = 1; i < smoothed.length; i++) {
            int y = smoothed[i];
            int previous = smoothed[i - 1];
            y = clampHeightStep(y, previous);
            if (i >= 3) {
                int threeBack = smoothed[i - 3];
                y = Math.min(y, threeBack + MAX_SLOPE_STEP_PER_THREE_SEGMENTS);
                y = Math.max(y, threeBack - MAX_SLOPE_STEP_PER_THREE_SEGMENTS);
            }
            smoothed[i] = y;
        }

        for (int i = smoothed.length - 2; i >= 0; i--) {
            int y = smoothed[i];
            int next = smoothed[i + 1];
            y = clampHeightStep(y, next);
            if (i + 3 < smoothed.length) {
                int threeAhead = smoothed[i + 3];
                y = Math.min(y, threeAhead + MAX_SLOPE_STEP_PER_THREE_SEGMENTS);
                y = Math.max(y, threeAhead - MAX_SLOPE_STEP_PER_THREE_SEGMENTS);
            }
            smoothed[i] = y;
        }
        return smoothed;
    }

    private static int[] flattenTurningSlopeHeights(List<BlockPos> centerPath, int[] placementHeights) {
        if (centerPath == null || placementHeights == null || centerPath.size() != placementHeights.length || placementHeights.length < 3) {
            return placementHeights == null ? new int[0] : placementHeights.clone();
        }
        int[] adjusted = placementHeights.clone();
        for (int i = 1; i < adjusted.length - 1; i++) {
            if (!isHorizontalTurn(centerPath, i)) {
                continue;
            }
            int previous = adjusted[i - 1];
            int current = adjusted[i];
            int next = adjusted[i + 1];
            if (current != previous || next != current) {
                adjusted[i] = previous;
            }
        }
        return adjusted;
    }

    private static List<GhostRoadBlock> enforceTurningSlopeSlabStates(List<BlockPos> centerPath,
                                                                      int[] placementHeights,
                                                                      List<GhostRoadBlock> ghostBlocks) {
        if (centerPath == null || placementHeights == null || ghostBlocks == null || centerPath.size() != placementHeights.length || ghostBlocks.isEmpty()) {
            return ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
        }
        LinkedHashMap<Long, Integer> turningColumns = new LinkedHashMap<>();
        for (int i = 1; i < centerPath.size() - 1; i++) {
            if (!isTurningSlopeSegment(centerPath, i, placementHeights)) {
                continue;
            }
            BlockPos pos = centerPath.get(i);
            turningColumns.put(RoadCoreExclusion.columnKey(pos.getX(), pos.getZ()), placementHeights[i]);
        }
        if (turningColumns.isEmpty()) {
            return List.copyOf(ghostBlocks);
        }
        ArrayList<GhostRoadBlock> adjusted = new ArrayList<>(ghostBlocks.size());
        for (GhostRoadBlock block : ghostBlocks) {
            if (block == null || block.pos() == null || block.state() == null) {
                continue;
            }
            Integer targetY = turningColumns.get(RoadCoreExclusion.columnKey(block.pos().getX(), block.pos().getZ()));
            if (targetY != null && Math.abs(block.pos().getY() - targetY) <= 1) {
                adjusted.add(new GhostRoadBlock(block.pos(), slabStateForFamily(block.state())));
                continue;
            }
            adjusted.add(block);
        }
        return List.copyOf(adjusted);
    }

    private static boolean isHorizontalTurn(List<BlockPos> path, int index) {
        if (path == null || index <= 0 || index >= path.size() - 1) {
            return false;
        }
        BlockPos previous = path.get(index - 1);
        BlockPos current = path.get(index);
        BlockPos next = path.get(index + 1);
        int incomingX = Integer.compare(current.getX() - previous.getX(), 0);
        int incomingZ = Integer.compare(current.getZ() - previous.getZ(), 0);
        int outgoingX = Integer.compare(next.getX() - current.getX(), 0);
        int outgoingZ = Integer.compare(next.getZ() - current.getZ(), 0);
        return !((incomingX == outgoingX && incomingZ == outgoingZ)
                || (incomingX == 0 && incomingZ == 0)
                || (outgoingX == 0 && outgoingZ == 0));
    }

    private static int[] applyBridgeProfiles(int[] baseHeights, List<RoadBridgePlanner.BridgeProfile> bridgeProfiles) {
        int[] adjusted = baseHeights.clone();
        if (bridgeProfiles.isEmpty() || adjusted.length == 0) {
            return adjusted;
        }
        for (RoadBridgePlanner.BridgeProfile profile : bridgeProfiles) {
            if (profile == null) {
                continue;
            }
            if (profile.navigableWaterBridge()) {
                applyNavigableBridgeProfile(adjusted, baseHeights, profile);
                continue;
            }
            if (profile.kind() == RoadBridgePlanner.BridgeKind.ARCHED) {
                applyArchedProfile(adjusted, profile.startIndex(), profile.endIndex());
            }
        }
        return adjusted;
    }

    private static int[] applyBridgeSpanPlans(int[] baseHeights,
                                              List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        int[] adjusted = baseHeights.clone();
        if (bridgePlans == null || bridgePlans.isEmpty() || adjusted.length == 0) {
            return adjusted;
        }
        for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
            if (plan == null || !plan.valid()) {
                continue;
            }
            applyBridgeSpanPlan(adjusted, plan);
        }
        return adjusted;
    }

    private static void applyBridgeSpanPlan(int[] placementHeights,
                                            RoadBridgePlanner.BridgeSpanPlan plan) {
        if (placementHeights.length == 0 || plan == null) {
            return;
        }
        if (plan.mode() == RoadBridgePlanner.BridgeMode.ARCH_SPAN) {
            int start = clampIndex(plan.startIndex(), placementHeights.length);
            int end = clampIndex(plan.endIndex(), placementHeights.length);
            if (end < start) {
                return;
            }
            int startDeckY = bridgeSegmentBoundaryDeckY(plan, start, placementHeights[start], true);
            int endDeckY = bridgeSegmentBoundaryDeckY(plan, end, placementHeights[end], false);
            applyExplicitArchSpan(placementHeights, start, end, startDeckY, endDeckY);
            return;
        }
        for (RoadBridgePlanner.BridgeDeckSegment segment : plan.deckSegments()) {
            if (segment == null) {
                continue;
            }
            switch (segment.type()) {
                case ARCHED_SPAN -> {
                    int start = clampIndex(segment.startIndex(), placementHeights.length);
                    int end = clampIndex(segment.endIndex(), placementHeights.length);
                    applyExplicitArchSpan(placementHeights, start, end, segment.startDeckY(), segment.endDeckY());
                }
                case APPROACH_UP, APPROACH_DOWN -> applyLinearBridgeSegment(placementHeights, segment);
                case BRIDGE_HEAD_PLATFORM -> applyLevelBridgeSegment(placementHeights, segment, segment.startDeckY());
                case MAIN_LEVEL -> applyLevelBridgeSegment(placementHeights, segment, plan.mainDeckY());
            }
        }
    }

    private static int[] propagateBridgeApproachHeights(int[] baseHeights,
                                                        List<RoadBridgePlanner.BridgeProfile> bridgeProfiles) {
        int[] adjusted = baseHeights.clone();
        if (adjusted.length == 0 || bridgeProfiles == null || bridgeProfiles.isEmpty()) {
            return adjusted;
        }
        boolean[] bridgeColumns = new boolean[adjusted.length];
        for (RoadBridgePlanner.BridgeProfile profile : bridgeProfiles) {
            if (profile == null) {
                continue;
            }
            int start = Math.max(0, Math.min(profile.startIndex(), adjusted.length - 1));
            int end = Math.max(0, Math.min(profile.endIndex(), adjusted.length - 1));
            for (int i = start; i <= end; i++) {
                bridgeColumns[i] = true;
            }
        }
        for (int i = 1; i < adjusted.length; i++) {
            if (bridgeColumns[i]) {
                continue;
            }
            adjusted[i] = Math.max(adjusted[i], adjusted[i - 1] - 1);
        }
        for (int i = adjusted.length - 2; i >= 0; i--) {
            if (bridgeColumns[i]) {
                continue;
            }
            adjusted[i] = Math.max(adjusted[i], adjusted[i + 1] - 1);
        }
        return adjusted;
    }

    private static int[] constrainToTerrainEnvelope(int[] placementHeights,
                                                    int[] sampledHeights,
                                                    List<RoadBridgePlanner.BridgeProfile> bridgeProfiles) {
        int[] constrained = placementHeights.clone();
        boolean[] bridgeColumns = new boolean[constrained.length];
        boolean[] terrainLockedColumns = steepTerrainLockMask(sampledHeights);
        for (RoadBridgePlanner.BridgeProfile profile : bridgeProfiles) {
            if (profile == null) {
                continue;
            }
            int start = Math.max(0, Math.min(profile.startIndex(), bridgeColumns.length - 1));
            int end = Math.max(0, Math.min(profile.endIndex(), bridgeColumns.length - 1));
            for (int i = start; i <= end; i++) {
                bridgeColumns[i] = true;
            }
        }
        for (int i = 0; i < constrained.length; i++) {
            if (bridgeColumns[i] || !terrainLockedColumns[i]) {
                continue;
            }
            int minDeckHeight = sampledHeights[i];
            int maxDeckHeight = sampledHeights[i] + 1;
            constrained[i] = Math.max(minDeckHeight, Math.min(maxDeckHeight, constrained[i]));
        }
        return constrained;
    }

    private static int[] constrainToTerrainEnvelopeFromSpanPlans(int[] placementHeights,
                                                                 int[] sampledHeights,
                                                                 List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        int[] constrained = placementHeights.clone();
        boolean[] bridgeColumns = bridgeInfluenceColumnsFromSpanPlans(placementHeights, sampledHeights, bridgePlans);
        boolean[] terrainLockedColumns = steepTerrainLockMask(sampledHeights);
        for (int i = 0; i < constrained.length; i++) {
            if (bridgeColumns[i] || !terrainLockedColumns[i]) {
                continue;
            }
            int minDeckHeight = sampledHeights[i];
            int maxDeckHeight = sampledHeights[i] + 1;
            constrained[i] = Math.max(minDeckHeight, Math.min(maxDeckHeight, constrained[i]));
        }
        return constrained;
    }

    private static boolean[] bridgeInfluenceColumnsFromSpanPlans(int[] placementHeights,
                                                                 int[] sampledHeights,
                                                                 List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        boolean[] influenced = new boolean[placementHeights.length];
        if (placementHeights.length == 0 || sampledHeights.length != placementHeights.length || bridgePlans == null || bridgePlans.isEmpty()) {
            return influenced;
        }
        for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
            if (plan == null || !plan.valid()) {
                continue;
            }
            int start = clampIndex(plan.startIndex(), placementHeights.length);
            int end = clampIndex(plan.endIndex(), placementHeights.length);
            for (int i = start; i <= end; i++) {
                influenced[i] = true;
            }
            int left = start - 1;
            while (left >= 0
                    && placementHeights[left] > sampledHeights[left]
                    && placementHeights[left] >= placementHeights[left + 1] - 1) {
                influenced[left] = true;
                left--;
            }
            int right = end + 1;
            while (right < placementHeights.length
                    && placementHeights[right] > sampledHeights[right]
                    && placementHeights[right] >= placementHeights[right - 1] - 1) {
                influenced[right] = true;
                right++;
            }
        }
        return influenced;
    }

    private static int[] propagateBridgeApproachHeightsFromSpanPlans(int[] baseHeights,
                                                                     List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        int[] adjusted = baseHeights.clone();
        if (adjusted.length == 0 || bridgePlans == null || bridgePlans.isEmpty()) {
            return adjusted;
        }
        boolean[] bridgeColumns = new boolean[adjusted.length];
        for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
            if (plan == null || !plan.valid()) {
                continue;
            }
            int start = clampIndex(plan.startIndex(), adjusted.length);
            int end = clampIndex(plan.endIndex(), adjusted.length);
            for (int i = start; i <= end; i++) {
                bridgeColumns[i] = true;
            }
        }
        for (int i = 1; i < adjusted.length; i++) {
            if (bridgeColumns[i]) {
                continue;
            }
            adjusted[i] = Math.max(adjusted[i], adjusted[i - 1] - 1);
        }
        for (int i = adjusted.length - 2; i >= 0; i--) {
            if (bridgeColumns[i]) {
                continue;
            }
            adjusted[i] = Math.max(adjusted[i], adjusted[i + 1] - 1);
        }
        return adjusted;
    }

    private static boolean[] steepTerrainLockMask(int[] sampledHeights) {
        boolean[] locked = new boolean[sampledHeights.length];
        if (sampledHeights.length < 3) {
            return locked;
        }
        int runStart = -1;
        int previousDirection = 0;
        int runSegments = 0;
        for (int i = 1; i < sampledHeights.length; i++) {
            int rise = sampledHeights[i] - sampledHeights[i - 1];
            int direction = Integer.signum(rise);
            boolean steep = Math.abs(rise) >= 2;
            if (steep && direction != 0 && (previousDirection == 0 || direction == previousDirection)) {
                if (runStart < 0) {
                    runStart = i - 1;
                    runSegments = 1;
                } else {
                    runSegments++;
                }
                previousDirection = direction;
                continue;
            }
            if (runStart >= 0 && runSegments >= 2) {
                markLockedRange(locked, runStart, i - 1);
            }
            runStart = steep ? i - 1 : -1;
            runSegments = steep ? 1 : 0;
            previousDirection = steep ? direction : 0;
        }
        if (runStart >= 0 && runSegments >= 2) {
            markLockedRange(locked, runStart, sampledHeights.length - 1);
        }
        return locked;
    }

    private static void markLockedRange(boolean[] locked, int startInclusive, int endInclusive) {
        for (int i = Math.max(0, startInclusive); i <= Math.min(locked.length - 1, endInclusive); i++) {
            locked[i] = true;
        }
    }

    private static void applyNavigableBridgeProfile(int[] placementHeights,
                                                    int[] baseHeights,
                                                    RoadBridgePlanner.BridgeProfile profile) {
        if (placementHeights.length == 0 || baseHeights == null || baseHeights.length != placementHeights.length || profile == null) {
            return;
        }
        int clampedStart = Math.max(0, Math.min(profile.startIndex(), placementHeights.length - 1));
        int clampedEnd = Math.max(0, Math.min(profile.endIndex(), placementHeights.length - 1));
        if (clampedEnd < clampedStart) {
            return;
        }
        int leftBoundaryHeight = resolveBoundaryHeight(baseHeights, clampedStart - 1, clampedStart);
        int rightBoundaryHeight = resolveBoundaryHeight(baseHeights, clampedEnd + 1, clampedEnd);
        RampBudget rampBudget = resolveNavigableRampBudget(
                profile.deckHeight(),
                leftBoundaryHeight,
                rightBoundaryHeight,
                clampedEnd - clampedStart + 1
        );
        for (int i = clampedStart; i <= clampedEnd; i++) {
            int targetHeight = profile.deckHeight();
            int leftDistance = i - clampedStart;
            int rightDistance = clampedEnd - i;
            if (leftDistance < rampBudget.leftSegments()) {
                targetHeight = Math.min(profile.deckHeight(), leftBoundaryHeight + leftDistance);
            } else if (rightDistance < rampBudget.rightSegments()) {
                targetHeight = Math.min(profile.deckHeight(), rightBoundaryHeight + rightDistance);
            }
            placementHeights[i] = Math.max(placementHeights[i], targetHeight);
        }
    }

    static int[] applyNavigableBridgeProfileForTest(int[] baseHeights, RoadBridgePlanner.BridgeProfile profile) {
        int[] adjusted = baseHeights.clone();
        applyNavigableBridgeProfile(adjusted, baseHeights, profile);
        return adjusted;
    }

    private static int resolveBoundaryHeight(int[] baseHeights, int preferredIndex, int fallbackIndex) {
        if (preferredIndex >= 0 && preferredIndex < baseHeights.length) {
            return baseHeights[preferredIndex];
        }
        int clampedFallback = Math.max(0, Math.min(baseHeights.length - 1, fallbackIndex));
        return baseHeights[clampedFallback];
    }

    private static RampBudget resolveNavigableRampBudget(int deckHeight,
                                                         int leftBoundaryHeight,
                                                         int rightBoundaryHeight,
                                                         int spanLength) {
        if (spanLength <= 2) {
            return new RampBudget(0, 0);
        }
        int maxRampBudget = Math.max(0, spanLength - 1);
        int leftSegments = Math.max(0, Math.min(DEFAULT_NAVIGABLE_BRIDGE_RAMP_SEGMENTS, deckHeight - leftBoundaryHeight));
        int rightSegments = Math.max(0, Math.min(DEFAULT_NAVIGABLE_BRIDGE_RAMP_SEGMENTS, deckHeight - rightBoundaryHeight));
        if ((leftSegments + rightSegments) > maxRampBudget) {
            return new RampBudget(0, 0);
        }
        while ((leftSegments + rightSegments) > maxRampBudget) {
            if (leftSegments >= rightSegments && leftSegments > 0) {
                leftSegments--;
            } else if (rightSegments > 0) {
                rightSegments--;
            } else {
                break;
            }
        }
        return new RampBudget(leftSegments, rightSegments);
    }

    private record RampBudget(int leftSegments, int rightSegments) {
    }

    private static void applyArchedProfile(int[] placementHeights, int startIndex, int endIndex) {
        if (placementHeights.length == 0) {
            return;
        }
        int clampedStart = Math.max(0, Math.min(startIndex, placementHeights.length - 1));
        int clampedEnd = Math.max(0, Math.min(endIndex, placementHeights.length - 1));
        if (clampedEnd < clampedStart) {
            return;
        }
        int spanLength = clampedEnd - clampedStart + 1;
        if (spanLength < 2) {
            return;
        }

        int crownHeight = resolveArchedCrownHeight(spanLength);
        if (crownHeight <= 0) {
            return;
        }
        int startDeckHeight = placementHeights[clampedStart];
        int endDeckHeight = placementHeights[clampedEnd];
        int baselineDeckHeight = Math.round((startDeckHeight + endDeckHeight) / 2.0F);
        int maxDistanceFromEdge = (spanLength - 1) / 2;

        for (int i = clampedStart; i <= clampedEnd; i++) {
            int localIndex = i - clampedStart;
            int distanceFromNearestEdge = Math.min(localIndex, spanLength - 1 - localIndex);
            double t = spanLength <= 1 ? 0.0D : (double) localIndex / (double) (spanLength - 1);
            int interpolatedDeckHeight = (int) Math.round(startDeckHeight + t * (endDeckHeight - startDeckHeight));
            int slopeOffset = interpolatedDeckHeight - baselineDeckHeight;
            int lift = maxDistanceFromEdge <= 0
                    ? 0
                    : (int) Math.round((double) crownHeight * (double) distanceFromNearestEdge / (double) maxDistanceFromEdge);
            placementHeights[i] = baselineDeckHeight + slopeOffset + lift;
        }
    }

    private static void applyLinearBridgeSegment(int[] placementHeights,
                                                 RoadBridgePlanner.BridgeDeckSegment segment) {
        int start = clampIndex(segment.startIndex(), placementHeights.length);
        int end = clampIndex(segment.endIndex(), placementHeights.length);
        if (end < start) {
            return;
        }
        if (start == end) {
            placementHeights[start] = Math.max(segment.startDeckY(), segment.endDeckY());
            return;
        }
        int run = end - start;
        int startDeckY = segment.startDeckY();
        int endDeckY = segment.endDeckY();
        for (int i = start; i <= end; i++) {
            int offset = i - start;
            int target;
            if (endDeckY >= startDeckY) {
                int rise = endDeckY - startDeckY;
                target = startDeckY + (int) Math.floor((double) offset * (double) rise / (double) run);
            } else {
                int drop = startDeckY - endDeckY;
                target = endDeckY + (int) Math.floor((double) (run - offset) * (double) drop / (double) run);
            }
            placementHeights[i] = target;
        }
    }

    private static void applyExplicitArchSpan(int[] placementHeights,
                                              int start,
                                              int end,
                                              int startDeckY,
                                              int endDeckY) {
        if (end < start || placementHeights.length == 0) {
            return;
        }
        placementHeights[start] = Math.max(placementHeights[start], startDeckY);
        placementHeights[end] = Math.max(placementHeights[end], endDeckY);
        int spanLength = end - start + 1;
        if (spanLength == 3) {
            int midpoint = start + 1;
            int crestY = Math.max(startDeckY, endDeckY) + 1;
            placementHeights[midpoint] = Math.max(placementHeights[midpoint], crestY);
            return;
        }
        applyArchedProfile(placementHeights, start, end);
    }

    private static void applyLevelBridgeSegment(int[] placementHeights,
                                                RoadBridgePlanner.BridgeDeckSegment segment,
                                                int mainDeckY) {
        int start = clampIndex(segment.startIndex(), placementHeights.length);
        int end = clampIndex(segment.endIndex(), placementHeights.length);
        if (end < start) {
            return;
        }
        int target = Math.max(mainDeckY, Math.max(segment.startDeckY(), segment.endDeckY()));
        for (int i = start; i <= end; i++) {
            placementHeights[i] = target;
        }
    }

    private static int bridgeSegmentBoundaryDeckY(RoadBridgePlanner.BridgeSpanPlan plan,
                                                  int index,
                                                  int fallback,
                                                  boolean startBoundary) {
        if (plan == null || plan.deckSegments().isEmpty()) {
            return fallback;
        }
        RoadBridgePlanner.BridgeDeckSegment segment = startBoundary
                ? plan.deckSegments().get(0)
                : plan.deckSegments().get(plan.deckSegments().size() - 1);
        if (segment == null) {
            return fallback;
        }
        int deckY = startBoundary ? segment.startDeckY() : segment.endDeckY();
        if (deckY > 0) {
            return deckY;
        }
        for (RoadBridgePlanner.BridgePierNode node : plan.nodes()) {
            if (node != null && node.pathIndex() == index) {
                return node.deckY();
            }
        }
        return fallback;
    }

    private static int clampIndex(int index, int length) {
        return Math.max(0, Math.min(length - 1, index));
    }

    private static int resolveArchedCrownHeight(int spanLength) {
        if (spanLength >= 6) {
            return ARCHED_CROWN_HEIGHT_LONG;
        }
        if (spanLength >= 4) {
            return ARCHED_CROWN_HEIGHT_SHORT;
        }
        return 0;
    }

    private static Projection findNearestProjection(int x, int z, List<BlockPos> centerPath) {
        int bestSegment = 0;
        double bestT = 0.0D;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < centerPath.size() - 1; i++) {
            BlockPos start = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i);
            BlockPos end = Objects.requireNonNull(centerPath.get(i + 1), "centerPath contains null at index " + (i + 1));
            double ax = start.getX();
            double az = start.getZ();
            double bx = end.getX();
            double bz = end.getZ();
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D
                    ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lengthSq));
            double projectedX = ax + t * dx;
            double projectedZ = az + t * dz;
            double distanceSq = (x - projectedX) * (x - projectedX) + (z - projectedZ) * (z - projectedZ);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestSegment = i;
                bestT = t;
            }
        }
        return new Projection(bestSegment, bestT, bestDistanceSq);
    }

    private static int interpolateHeight(int segmentIndex, double t, int[] placementHeights) {
        int startY = placementHeights[segmentIndex];
        int endY = placementHeights[segmentIndex + 1];
        return (int) Math.round(startY + t * (endY - startY));
    }

    private record RibbonBasis(int normalX, int normalZ, int incomingX, int incomingZ, int outgoingX, int outgoingZ, int turnSign) {
    }

    public record RoadGeometryPlan(List<GhostRoadBlock> ghostBlocks, List<RoadBuildStep> buildSteps) {
        public RoadGeometryPlan {
            ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
            buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        }
    }

    private record Projection(int segmentIndex, double t, double distanceSq) {
    }

    private record GhostCandidate(BlockPos pos,
                                  BlockState state,
                                  int sourceIndex,
                                  int sourceDistanceSq,
                                  boolean stairState) {
    }

    public enum RoadBuildPhase {
        SUPPORT,
        DECK,
        DECOR
    }

    public record GhostRoadBlock(BlockPos pos, BlockState state) {
        public GhostRoadBlock {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }

    public record RoadBuildStep(int order, BlockPos pos, BlockState state, RoadBuildPhase phase) {
        public RoadBuildStep(int order, BlockPos pos, BlockState state) {
            this(order, pos, state, RoadBuildPhase.DECK);
        }

        public RoadBuildStep {
            if (order < 0) {
                throw new IllegalArgumentException("order must be non-negative");
            }
            pos = Objects.requireNonNull(pos, "pos").immutable();
            state = Objects.requireNonNull(state, "state");
            phase = phase == null ? RoadBuildPhase.DECK : phase;
        }
    }

    public record SlopeProfile(boolean hasStairSegments, int longestSteepRun, int maxRise) {
    }

    public record RibbonSlice(List<BlockPos> columns, int insideHalfWidth, int outsideHalfWidth) {
        public RibbonSlice {
            columns = columns == null ? List.of() : List.copyOf(columns);
            if (insideHalfWidth < 0) {
                throw new IllegalArgumentException("insideHalfWidth must be non-negative");
            }
            if (outsideHalfWidth < 0) {
                throw new IllegalArgumentException("outsideHalfWidth must be non-negative");
            }
        }

        public int totalWidth() {
            return insideHalfWidth + outsideHalfWidth + 1;
        }
    }
}
