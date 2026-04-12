package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

public final class RoadCorridorPlanner {
    private static final int SUPPORT_INTERVAL = 3;
    private static final int SUPPORT_DEPTH = 3;

    private record SupportPlacementPlan(Set<Integer> supportIndexes, boolean valid) {
    }

    private RoadCorridorPlanner() {
    }

    public static RoadCorridorPlan plan(List<BlockPos> centerPath) {
        return plan(centerPath, List.of(), List.of());
    }

    public static RoadCorridorPlan plan(List<BlockPos> centerPath,
                                        List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                        List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges) {
        int[] placementHeights = new int[centerPath == null ? 0 : centerPath.size()];
        if (centerPath != null) {
            for (int i = 0; i < centerPath.size(); i++) {
                placementHeights[i] = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i).getY();
            }
        }
        return plan(centerPath, bridgeRanges, navigableWaterBridgeRanges, placementHeights);
    }

    public static RoadCorridorPlan plan(List<BlockPos> centerPath,
                                        List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                        List<RoadPlacementPlan.BridgeRange> navigableWaterBridgeRanges,
                                        int[] placementHeights) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(placementHeights, "placementHeights");
        int size = centerPath.size();
        if (placementHeights.length != size) {
            throw new IllegalArgumentException("placementHeights size must match centerPath size");
        }
        Set<Integer> bridgeIndexes = expandIndexes(bridgeRanges, size);
        Set<Integer> requestedNavigableIndexes = expandIndexes(navigableWaterBridgeRanges, size);
        List<RoadPlacementPlan.BridgeRange> mainChannelRanges = detectContiguousSubranges(
                centerPath,
                bridgeRanges,
                requestedNavigableIndexes::contains
        );
        Set<Integer> navigableIndexes = expandIndexes(mainChannelRanges, size);
        Set<Integer> bridgeHeadIndexes = collectBridgeHeadIndexes(bridgeRanges, size);
        SupportPlacementPlan supportPlacementPlan = collectSupportRequiredIndexes(
                bridgeRanges,
                bridgeHeadIndexes,
                navigableIndexes,
                size
        );
        Set<Integer> supportRequiredIndexes = supportPlacementPlan.supportIndexes();

        boolean valid = supportPlacementPlan.valid();
        List<BlockPos> deckCenters = new ArrayList<>(size);
        List<RoadCorridorPlan.SegmentKind> segmentKinds = new ArrayList<>(size);
        List<List<BlockPos>> railingLightPositionsByIndex = new ArrayList<>(size);
        List<List<BlockPos>> supportPositionsByIndex = new ArrayList<>(size);
        List<List<BlockPos>> pierLightPositionsByIndex = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos center = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i);
            BlockPos deckCenter = new BlockPos(center.getX(), placementHeights[i], center.getZ()).immutable();
            RoadCorridorPlan.SegmentKind segmentKind = classify(i, placementHeights, bridgeIndexes, bridgeHeadIndexes, navigableIndexes);
            List<BlockPos> railingLightPositions = buildRailingLightPositions(centerPath, i, deckCenter, segmentKind);
            boolean supportRequired = supportRequiredIndexes.contains(i)
                    && segmentKind == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
            List<BlockPos> supportPositions = List.of();
            List<BlockPos> pierLightPositions = List.of();
            if (supportRequired) {
                supportPositions = buildSupportPositions(deckCenter);
                pierLightPositions = List.of(supportPositions.get(supportPositions.size() - 1).below());
            }
            deckCenters.add(deckCenter);
            segmentKinds.add(segmentKind);
            railingLightPositionsByIndex.add(railingLightPositions);
            supportPositionsByIndex.add(supportPositions);
            pierLightPositionsByIndex.add(pierLightPositions);
        }

        List<List<BlockPos>> surfacePositionsByIndex = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            surfacePositionsByIndex.add(buildSurfacePositions(centerPath, i, placementHeights));
        }
        for (int i = 0; i < size - 1; i++) {
            RoadCorridorPlan.SegmentKind currentKind = segmentKinds.get(i);
            RoadCorridorPlan.SegmentKind nextKind = segmentKinds.get(i + 1);
            if (requiresBridgeheadOverlapRepair(currentKind, nextKind)) {
                if (usesTerrainEnvelope(currentKind) && !usesTerrainEnvelope(nextKind)) {
                    surfacePositionsByIndex.set(i + 1, ensureTransitionOverlap(surfacePositionsByIndex.get(i), surfacePositionsByIndex.get(i + 1)));
                } else if (!usesTerrainEnvelope(currentKind) && usesTerrainEnvelope(nextKind)) {
                    surfacePositionsByIndex.set(i, ensureTransitionOverlap(surfacePositionsByIndex.get(i + 1), surfacePositionsByIndex.get(i)));
                }
                continue;
            }
            if (requiresAdjacentSliceClosureRepair(
                    surfacePositionsByIndex.get(i),
                    surfacePositionsByIndex.get(i + 1),
                    placementHeights[i],
                    placementHeights[i + 1]
            )) {
                surfacePositionsByIndex.set(i + 1, ensureTransitionOverlap(surfacePositionsByIndex.get(i), surfacePositionsByIndex.get(i + 1)));
            }
        }

        List<RoadCorridorPlan.CorridorSlice> slices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            List<BlockPos> surfacePositions = surfacePositionsByIndex.get(i);
            slices.add(new RoadCorridorPlan.CorridorSlice(
                    i,
                    deckCenters.get(i),
                    segmentKinds.get(i),
                    surfacePositions,
                    buildExcavationPositions(surfacePositions, segmentKinds.get(i)),
                    buildClearancePositions(surfacePositions, segmentKinds.get(i)),
                    railingLightPositionsByIndex.get(i),
                    supportPositionsByIndex.get(i),
                    pierLightPositionsByIndex.get(i)
            ));
        }
        return new RoadCorridorPlan(centerPath, slices, buildNavigationChannel(deckCenters, navigableIndexes), valid);
    }

    public static List<RoadPlacementPlan.BridgeRange> detectContiguousSubranges(List<BlockPos> centerPath,
                                                                                 List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                                 IntPredicate selectedAt) {
        if (centerPath == null || centerPath.isEmpty() || bridgeRanges == null || bridgeRanges.isEmpty() || selectedAt == null) {
            return List.of();
        }
        List<RoadPlacementPlan.BridgeRange> ranges = new ArrayList<>();
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.startIndex());
            int end = Math.min(centerPath.size() - 1, range.endIndex());
            int selectedStart = -1;
            for (int i = start; i <= end; i++) {
                if (selectedAt.test(i)) {
                    if (selectedStart < 0) {
                        selectedStart = i;
                    }
                } else if (selectedStart >= 0) {
                    ranges.add(new RoadPlacementPlan.BridgeRange(selectedStart, i - 1));
                    selectedStart = -1;
                }
            }
            if (selectedStart >= 0) {
                ranges.add(new RoadPlacementPlan.BridgeRange(selectedStart, end));
            }
        }
        return List.copyOf(ranges);
    }

    private static RoadCorridorPlan.SegmentKind classify(int index,
                                                         int[] placementHeights,
                                                         Set<Integer> bridgeIndexes,
                                                         Set<Integer> bridgeHeadIndexes,
                                                         Set<Integer> navigableIndexes) {
        if (navigableIndexes.contains(index)) {
            return RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN;
        }
        if (bridgeIndexes.contains(index)) {
            if (isElevatedApproach(index, placementHeights, bridgeIndexes)) {
                return RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH;
            }
            if (bridgeHeadIndexes.contains(index)) {
                return RoadCorridorPlan.SegmentKind.BRIDGE_HEAD;
            }
            return RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
        }
        if (isRampTransition(index, placementHeights, bridgeIndexes)) {
            return RoadCorridorPlan.SegmentKind.APPROACH_RAMP;
        }
        return RoadCorridorPlan.SegmentKind.LAND_APPROACH;
    }

    private static Set<Integer> expandIndexes(List<RoadPlacementPlan.BridgeRange> ranges, int pathSize) {
        Set<Integer> indexes = new HashSet<>();
        if (ranges == null || ranges.isEmpty() || pathSize <= 0) {
            return indexes;
        }
        for (RoadPlacementPlan.BridgeRange range : ranges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.startIndex());
            int end = Math.min(pathSize - 1, range.endIndex());
            for (int i = start; i <= end; i++) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static Set<Integer> collectBridgeHeadIndexes(List<RoadPlacementPlan.BridgeRange> bridgeRanges, int pathSize) {
        Set<Integer> bridgeHeads = new HashSet<>();
        if (bridgeRanges == null || bridgeRanges.isEmpty() || pathSize <= 0) {
            return bridgeHeads;
        }
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, Math.min(pathSize - 1, range.startIndex()));
            int end = Math.max(0, Math.min(pathSize - 1, range.endIndex()));
            bridgeHeads.add(start);
            bridgeHeads.add(end);
        }
        return bridgeHeads;
    }

    private static SupportPlacementPlan collectSupportRequiredIndexes(List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                                      Set<Integer> bridgeHeadIndexes,
                                                                      Set<Integer> navigableIndexes,
                                                                      int pathSize) {
        Set<Integer> supportIndexes = new HashSet<>();
        if (bridgeRanges == null || bridgeRanges.isEmpty() || pathSize <= 0) {
            return new SupportPlacementPlan(supportIndexes, true);
        }
        boolean valid = true;
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.startIndex());
            int end = Math.min(pathSize - 1, range.endIndex());
            List<Integer> supportableInterior = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                if (bridgeHeadIndexes.contains(i) || navigableIndexes.contains(i)) {
                    continue;
                }
                supportableInterior.add(i);
            }
            if (supportableInterior.isEmpty()) {
                continue;
            }
            int lastPlaced = Integer.MIN_VALUE / 4;
            for (int index : supportableInterior) {
                if (index - lastPlaced < SUPPORT_INTERVAL) {
                    continue;
                }
                supportIndexes.add(index);
                lastPlaced = index;
            }
        }
        return new SupportPlacementPlan(Set.copyOf(supportIndexes), valid);
    }

    private static List<BlockPos> buildSupportPositions(BlockPos deckCenter) {
        List<BlockPos> supportPositions = new ArrayList<>(SUPPORT_DEPTH);
        for (int depth = 1; depth <= SUPPORT_DEPTH; depth++) {
            supportPositions.add(deckCenter.below(depth));
        }
        return List.copyOf(supportPositions);
    }

    private static List<BlockPos> buildRailingLightPositions(List<BlockPos> centerPath,
                                                             int index,
                                                             BlockPos deckCenter,
                                                             RoadCorridorPlan.SegmentKind segmentKind) {
        if (deckCenter == null || centerPath == null || centerPath.isEmpty() || !supportsBridgeRailings(segmentKind)) {
            return List.of();
        }
        BlockPos current = centerPath.get(index);
        BlockPos previous = index > 0 ? centerPath.get(index - 1) : current;
        BlockPos next = index + 1 < centerPath.size() ? centerPath.get(index + 1) : current;
        int dx = Integer.compare(next.getX() - previous.getX(), 0);
        int dz = Integer.compare(next.getZ() - previous.getZ(), 0);

        int sideX = 0;
        int sideZ = 0;
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            sideZ = 1;
        } else if (dz != 0) {
            sideX = 1;
        } else {
            sideX = 1;
        }

        return List.of(
                new BlockPos(deckCenter.getX() + sideX, deckCenter.getY(), deckCenter.getZ() + sideZ),
                new BlockPos(deckCenter.getX() - sideX, deckCenter.getY(), deckCenter.getZ() - sideZ)
        );
    }

    private static List<BlockPos> buildSurfacePositions(List<BlockPos> centerPath, int index, int[] deckHeights) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        for (BlockPos column : RoadGeometryPlanner.buildRibbonSlice(centerPath, index).columns()) {
            int y = RoadGeometryPlanner.interpolatePlacementHeight(
                    column.getX(),
                    column.getZ(),
                    centerPath,
                    deckHeights
            );
            positions.add(new BlockPos(column.getX(), y, column.getZ()));
        }
        return List.copyOf(positions);
    }

    private static boolean isElevatedApproach(int index, int[] placementHeights, Set<Integer> bridgeIndexes) {
        Integer landBaseline = landSideBaseline(index, placementHeights, bridgeIndexes);
        return landBaseline != null && placementHeights[index] > landBaseline + 1;
    }

    private static Integer landSideBaseline(int index, int[] placementHeights, Set<Integer> bridgeIndexes) {
        if (isBridgeRangeStart(index, bridgeIndexes) && index > 0 && !bridgeIndexes.contains(index - 1)) {
            return placementHeights[index - 1];
        }
        if (isBridgeRangeEnd(index, bridgeIndexes) && index + 1 < placementHeights.length && !bridgeIndexes.contains(index + 1)) {
            return placementHeights[index + 1];
        }
        return null;
    }

    private static boolean isRampTransition(int index, int[] placementHeights, Set<Integer> bridgeIndexes) {
        if (index + 1 < placementHeights.length && isBridgeRangeStart(index + 1, bridgeIndexes)) {
            return Math.abs(placementHeights[index + 1] - placementHeights[index]) > 1;
        }
        if (index > 0 && isBridgeRangeEnd(index - 1, bridgeIndexes)) {
            return Math.abs(placementHeights[index] - placementHeights[index - 1]) > 1;
        }
        return false;
    }

    private static boolean isBridgeRangeStart(int index, Set<Integer> bridgeIndexes) {
        return bridgeIndexes.contains(index) && (index == 0 || !bridgeIndexes.contains(index - 1));
    }

    private static boolean isBridgeRangeEnd(int index, Set<Integer> bridgeIndexes) {
        return bridgeIndexes.contains(index) && !bridgeIndexes.contains(index + 1);
    }

    private static boolean requiresBridgeheadOverlapRepair(RoadCorridorPlan.SegmentKind current,
                                                           RoadCorridorPlan.SegmentKind next) {
        if (current == next || usesTerrainEnvelope(current) == usesTerrainEnvelope(next)) {
            return false;
        }
        return isBridgeTransitionKind(current) && isBridgeTransitionKind(next);
    }

    private static boolean isBridgeTransitionKind(RoadCorridorPlan.SegmentKind segmentKind) {
        return segmentKind == RoadCorridorPlan.SegmentKind.APPROACH_RAMP
                || segmentKind == RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH
                || segmentKind == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD
                || segmentKind == RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN
                || segmentKind == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
    }

    private static boolean requiresAdjacentSliceClosureRepair(List<BlockPos> current,
                                                              List<BlockPos> next,
                                                              int currentDeckHeight,
                                                              int nextDeckHeight) {
        if (hasSurfaceOverlap(current, next)) {
            return false;
        }
        return Math.abs(currentDeckHeight - nextDeckHeight) >= 1;
    }

    private static List<BlockPos> ensureTransitionOverlap(List<BlockPos> source, List<BlockPos> target) {
        LinkedHashSet<BlockPos> overlapped = new LinkedHashSet<>(target);
        if (hasSurfaceOverlap(source, target)) {
            return List.copyOf(overlapped);
        }
        overlapped.addAll(transitionOverlapPositions(source, target));
        return List.copyOf(overlapped);
    }

    private static List<BlockPos> transitionOverlapPositions(List<BlockPos> source, List<BlockPos> target) {
        LinkedHashSet<BlockPos> overlap = new LinkedHashSet<>();
        for (BlockPos sourcePos : source) {
            if (isAdjacentToAny(sourcePos, target)) {
                overlap.add(sourcePos.immutable());
            }
        }
        return List.copyOf(overlap);
    }

    private static boolean hasSurfaceOverlap(List<BlockPos> current, List<BlockPos> next) {
        for (BlockPos pos : current) {
            if (next.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdjacentToAny(BlockPos candidate, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (Math.abs(candidate.getY() - pos.getY()) > 2) {
                continue;
            }
            int horizontalDistance = Math.abs(candidate.getX() - pos.getX()) + Math.abs(candidate.getZ() - pos.getZ());
            if (horizontalDistance == 1) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> buildExcavationPositions(List<BlockPos> surfacePositions,
                                                           RoadCorridorPlan.SegmentKind segmentKind) {
        if (!usesTerrainEnvelope(segmentKind)) {
            return List.of();
        }
        LinkedHashSet<BlockPos> excavation = new LinkedHashSet<>();
        for (BlockPos surface : surfacePositions) {
            excavation.add(surface.below().immutable());
        }
        return List.copyOf(excavation);
    }

    private static List<BlockPos> buildClearancePositions(List<BlockPos> surfacePositions,
                                                          RoadCorridorPlan.SegmentKind segmentKind) {
        if (!usesTerrainEnvelope(segmentKind)) {
            return List.of();
        }
        LinkedHashSet<BlockPos> clearance = new LinkedHashSet<>();
        for (BlockPos surface : surfacePositions) {
            clearance.add(surface.above().immutable());
            clearance.add(surface.above(2).immutable());
        }
        return List.copyOf(clearance);
    }

    private static boolean usesTerrainEnvelope(RoadCorridorPlan.SegmentKind segmentKind) {
        return segmentKind == RoadCorridorPlan.SegmentKind.LAND_APPROACH
                || segmentKind == RoadCorridorPlan.SegmentKind.APPROACH_RAMP
                || segmentKind == RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH
                || segmentKind == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD;
    }

    private static boolean supportsBridgeRailings(RoadCorridorPlan.SegmentKind segmentKind) {
        return segmentKind == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD
                || segmentKind == RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH
                || segmentKind == RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN
                || segmentKind == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
    }

    private static RoadCorridorPlan.NavigationChannel buildNavigationChannel(List<BlockPos> deckCenters, Set<Integer> navigableIndexes) {
        if (deckCenters.isEmpty() || navigableIndexes.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int index : navigableIndexes) {
            if (index < 0 || index >= deckCenters.size()) {
                continue;
            }
            BlockPos deckCenter = deckCenters.get(index);
            minX = Math.min(minX, deckCenter.getX());
            minY = Math.min(minY, deckCenter.getY() - 4);
            minZ = Math.min(minZ, deckCenter.getZ());
            maxX = Math.max(maxX, deckCenter.getX());
            maxY = Math.max(maxY, deckCenter.getY() + 2);
            maxZ = Math.max(maxZ, deckCenter.getZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return null;
        }
        return new RoadCorridorPlan.NavigationChannel(
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ)
        );
    }
}
