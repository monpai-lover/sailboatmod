package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class RoadGeometryPlanner {
    private static final int MAX_SLOPE_STEP_PER_TWO_SEGMENTS = 1;
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
        if (!slice.surfacePositions().isEmpty()) {
            return slice.surfacePositions();
        }
        return slicePositions(corridorPlan.centerPath(), corridorDeckHeights(corridorPlan), index);
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
            ghostBlocks.add(new GhostRoadBlock(
                    pos,
                    resolveState(corridorPlan.centerPath(), placementHeights, index, pos, stairSegment, blockStateSupplier)
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
        int[] bridged = applyBridgeProfiles(smoothed, bridgeProfiles);
        return constrainToTerrainEnvelope(bridged, sampledHeights, bridgeProfiles);
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
        return placementHeights;
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
                state.getBlock() instanceof StairBlock
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
        BlockState stairState = staircaseState(
                Objects.requireNonNull(blockStateSupplier.apply(lowerPos), "blockStateSupplier returned null for pos " + lowerPos),
                stairFacing(lowerPos, higherPos)
        );
        return stairState.getBlock() instanceof StairBlock
                ? new GhostRoadBlock(lowerPos, stairState)
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
        if (!stairSegment
                || shouldUseSlabTransition(currentPos.getX(), currentPos.getZ(), currentPos.getY(), path, placementHeights)
                || !isWithinStairTravelBand(currentPos.getX(), currentPos.getZ(), path)) {
            return state;
        }
        return staircaseState(state, stairFacing(path, placementHeights, index));
    }

    private static Direction stairFacing(List<BlockPos> path, int[] placementHeights, int index) {
        BlockPos current = path.get(index);
        int currentHeight = placementHeights[index];
        if (index > 0) {
            int previousHeight = placementHeights[index - 1];
            if (previousHeight != currentHeight) {
                BlockPos previous = path.get(index - 1);
                return previousHeight < currentHeight
                        ? stairFacing(previous, current)
                        : stairFacing(current, previous);
            }
        }
        if (index + 1 < path.size()) {
            int nextHeight = placementHeights[index + 1];
            if (nextHeight != currentHeight) {
                BlockPos next = path.get(index + 1);
                return currentHeight < nextHeight
                        ? stairFacing(current, next)
                        : stairFacing(next, current);
            }
        }

        BlockPos next = index + 1 < path.size() ? path.get(index + 1) : current;
        BlockPos previous = index > 0 ? path.get(index - 1) : current;
        int dx = Integer.compare(next.getX(), previous.getX());
        int dz = Integer.compare(next.getZ(), previous.getZ());
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.WEST : Direction.EAST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.NORTH : Direction.SOUTH;
        }
        return Direction.NORTH;
    }

    private static Direction stairFacing(BlockPos lower, BlockPos higher) {
        Direction uphill = directionBetween(lower, higher);
        return uphill.getOpposite();
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH;
    }

    private static BlockState staircaseState(BlockState state, Direction facing) {
        if (state.is(net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB)) {
            return net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, Half.BOTTOM);
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE_SLAB)) {
            return net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, Half.BOTTOM);
        }
        if (state.is(net.minecraft.world.level.block.Blocks.MUD_BRICK_SLAB)) {
            return net.minecraft.world.level.block.Blocks.MUD_BRICK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, Half.BOTTOM);
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SPRUCE_SLAB)) {
            return net.minecraft.world.level.block.Blocks.SPRUCE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, Half.BOTTOM);
        }
        return state;
    }

    private static int[] smoothPlacementHeights(int[] baseHeights) {
        int[] smoothed = baseHeights.clone();
        int step2 = Math.max(0, Math.min(8, MAX_SLOPE_STEP_PER_TWO_SEGMENTS));
        int halfLow = Math.max(0, step2 / 2);
        int halfHigh = Math.max(0, (step2 + 1) / 2);

        for (int i = 1; i < smoothed.length; i++) {
            int y = smoothed[i];
            if (i == 1) {
                int previous = smoothed[i - 1];
                if (y > previous + halfLow) {
                    y = previous + halfLow;
                }
                if (y < previous - halfLow) {
                    y = previous - halfLow;
                }
            } else {
                int previous = smoothed[i - 1];
                if (y > previous + halfHigh) {
                    y = previous + halfHigh;
                }
                if (y < previous - halfHigh) {
                    y = previous - halfHigh;
                }
                int twoBack = smoothed[i - 2];
                y = Math.min(y, twoBack + step2);
                y = Math.max(y, twoBack - step2);
            }
            smoothed[i] = y;
        }

        for (int i = smoothed.length - 2; i >= 0; i--) {
            int y = smoothed[i];
            if (i == smoothed.length - 2) {
                int next = smoothed[i + 1];
                if (y > next + halfLow) {
                    y = next + halfLow;
                }
                if (y < next - halfLow) {
                    y = next - halfLow;
                }
            } else {
                int next = smoothed[i + 1];
                if (y > next + halfHigh) {
                    y = next + halfHigh;
                }
                if (y < next - halfHigh) {
                    y = next - halfHigh;
                }
                int twoAhead = smoothed[i + 2];
                y = Math.min(y, twoAhead + step2);
                y = Math.max(y, twoAhead - step2);
            }
            smoothed[i] = y;
        }
        return smoothed;
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
                applyNavigableBridgeProfile(adjusted, profile);
                continue;
            }
            if (profile.kind() == RoadBridgePlanner.BridgeKind.ARCHED) {
                applyArchedProfile(adjusted, profile.startIndex(), profile.endIndex());
            }
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

    private static void applyNavigableBridgeProfile(int[] placementHeights, RoadBridgePlanner.BridgeProfile profile) {
        if (placementHeights.length == 0 || profile == null) {
            return;
        }
        int clampedStart = Math.max(0, Math.min(profile.startIndex(), placementHeights.length - 1));
        int clampedEnd = Math.max(0, Math.min(profile.endIndex(), placementHeights.length - 1));
        if (clampedEnd < clampedStart) {
            return;
        }
        for (int i = clampedStart; i <= clampedEnd; i++) {
            placementHeights[i] = Math.max(placementHeights[i], profile.deckHeight());
        }
    }

    static int[] applyNavigableBridgeProfileForTest(int[] baseHeights, RoadBridgePlanner.BridgeProfile profile) {
        int[] adjusted = baseHeights.clone();
        applyNavigableBridgeProfile(adjusted, profile);
        return adjusted;
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

    public record GhostRoadBlock(BlockPos pos, BlockState state) {
        public GhostRoadBlock {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }

    public record RoadBuildStep(int order, BlockPos pos, BlockState state) {
        public RoadBuildStep {
            if (order < 0) {
                throw new IllegalArgumentException("order must be non-negative");
            }
            pos = Objects.requireNonNull(pos, "pos").immutable();
            state = Objects.requireNonNull(state, "state");
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
