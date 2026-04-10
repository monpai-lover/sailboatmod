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

    private RoadGeometryPlanner() {
    }

    public static RoadGeometryPlan plan(List<BlockPos> centerPath, Function<BlockPos, BlockState> blockStateSupplier) {
        return plan(centerPath, blockStateSupplier, List.of());
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

        LinkedHashMap<Long, GhostRoadBlock> ghostByPos = new LinkedHashMap<>();
        for (int i = 0; i < path.size(); i++) {
            boolean stairSegment = isStairSegment(sampledHeights, i);
            List<BlockPos> slicePositions = slicePositions(path, placementHeights, i);
            for (BlockPos pos : slicePositions) {
                BlockState state = resolveState(path, placementHeights, i, pos, stairSegment, blockStateSupplier);
                addGhost(ghostByPos, pos, state);
            }
        }

        List<GhostRoadBlock> ghostBlocks = List.copyOf(ghostByPos.values());
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
        int[] smoothed = smoothPlacementHeights(samplePlacementHeights(centerPath));
        return applyBridgeProfiles(smoothed, bridgeProfiles);
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

    private static List<BlockPos> slicePositions(List<BlockPos> centerPath, int[] placementHeights, int index) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(placementHeights, "placementHeights");
        if (index < 0 || index >= centerPath.size()) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + centerPath.size());
        }
        if (placementHeights.length != centerPath.size()) {
            throw new IllegalArgumentException("placementHeights size must match centerPath size");
        }

        LinkedHashSet<BlockPos> columns = sliceColumns(centerPath, index);
        List<BlockPos> resolved = new ArrayList<>(columns.size());
        for (BlockPos column : columns) {
            int y = interpolatePlacementHeight(column.getX(), column.getZ(), centerPath, placementHeights);
            resolved.add(new BlockPos(column.getX(), y, column.getZ()));
        }
        return List.copyOf(resolved);
    }

    private static void addGhost(LinkedHashMap<Long, GhostRoadBlock> ghostByPos,
                                 BlockPos pos,
                                 BlockState state) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        ghostByPos.putIfAbsent(pos.asLong(), new GhostRoadBlock(pos.immutable(), state));
    }

    private static LinkedHashSet<BlockPos> sliceColumns(List<BlockPos> centerPath, int index) {
        BlockPos surface = Objects.requireNonNull(centerPath.get(index), "centerPath contains null at index " + index);
        BlockPos current = new BlockPos(surface.getX(), 0, surface.getZ());
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(current);

        BlockPos previousSurface = index > 0 ? centerPath.get(index - 1) : surface;
        BlockPos nextSurface = index + 1 < centerPath.size() ? centerPath.get(index + 1) : surface;
        int dx = Integer.compare(nextSurface.getX(), previousSurface.getX());
        int dz = Integer.compare(nextSurface.getZ(), previousSurface.getZ());
        addCrossSection(positions, current, dx, dz);

        if (index > 0 && index + 1 < centerPath.size()) {
            int prevDx = Integer.compare(surface.getX(), previousSurface.getX());
            int prevDz = Integer.compare(surface.getZ(), previousSurface.getZ());
            int nextDx = Integer.compare(nextSurface.getX(), surface.getX());
            int nextDz = Integer.compare(nextSurface.getZ(), surface.getZ());
            if (prevDx != nextDx || prevDz != nextDz) {
                addTurnShoulders(positions, current);
            }
        }
        return positions;
    }

    private static void addCrossSection(LinkedHashSet<BlockPos> positions, BlockPos current, int dx, int dz) {
        if (dx != 0 && dz == 0) {
            positions.add(current.north());
            positions.add(current.south());
            positions.add(current.north(2));
            positions.add(current.south(2));
            return;
        }
        if (dz != 0 && dx == 0) {
            positions.add(current.east());
            positions.add(current.west());
            positions.add(current.east(2));
            positions.add(current.west(2));
            return;
        }
        positions.add(current.north());
        positions.add(current.south());
        positions.add(current.east());
        positions.add(current.west());
        positions.add(current.north(2));
        positions.add(current.south(2));
        positions.add(current.east(2));
        positions.add(current.west(2));
    }

    private static void addTurnShoulders(LinkedHashSet<BlockPos> positions, BlockPos current) {
        positions.add(current.north());
        positions.add(current.south());
        positions.add(current.east());
        positions.add(current.west());
        positions.add(current.north(2));
        positions.add(current.south(2));
        positions.add(current.east(2));
        positions.add(current.west(2));
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
        return riseIn != 0 && riseOut != 0 && Integer.signum(riseIn) == Integer.signum(riseOut);
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
        Direction facing = stairFacing(path, index);
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

    private static Direction stairFacing(List<BlockPos> path, int index) {
        BlockPos current = path.get(index);
        BlockPos next = index + 1 < path.size() ? path.get(index + 1) : current;
        BlockPos previous = index > 0 ? path.get(index - 1) : current;
        int dx = Integer.compare(next.getX(), previous.getX());
        int dz = Integer.compare(next.getZ(), previous.getZ());
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH;
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
            if (profile == null || profile.kind() != RoadBridgePlanner.BridgeKind.ARCHED) {
                continue;
            }
            applyArchedProfile(adjusted, profile.startIndex(), profile.endIndex());
        }
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

    public record RoadGeometryPlan(List<GhostRoadBlock> ghostBlocks, List<RoadBuildStep> buildSteps) {
        public RoadGeometryPlan {
            ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
            buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        }
    }

    private record Projection(int segmentIndex, double t, double distanceSq) {
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
}
