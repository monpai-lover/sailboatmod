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
                placementHeights[i] = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i).getY() + 1;
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
        Set<Integer> supportRequiredIndexes = collectSupportRequiredIndexes(bridgeRanges, size);

        boolean valid = true;
        List<BlockPos> deckCenters = new ArrayList<>(size);
        List<RoadCorridorPlan.SegmentKind> segmentKinds = new ArrayList<>(size);
        List<List<BlockPos>> supportPositionsByIndex = new ArrayList<>(size);
        List<List<BlockPos>> pierLightPositionsByIndex = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos center = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i);
            BlockPos deckCenter = new BlockPos(center.getX(), placementHeights[i], center.getZ()).immutable();
            RoadCorridorPlan.SegmentKind segmentKind = classify(i, bridgeIndexes, bridgeHeadIndexes, navigableIndexes);
            boolean supportRequired = supportRequiredIndexes.contains(i)
                    && segmentKind == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
            List<BlockPos> supportPositions = List.of();
            List<BlockPos> pierLightPositions = List.of();
            if (supportRequiredIndexes.contains(i) && navigableIndexes.contains(i)) {
                valid = false;
            } else if (supportRequired) {
                supportPositions = buildSupportPositions(deckCenter);
                pierLightPositions = List.of(supportPositions.get(supportPositions.size() - 1).below());
            }
            deckCenters.add(deckCenter);
            segmentKinds.add(segmentKind);
            supportPositionsByIndex.add(supportPositions);
            pierLightPositionsByIndex.add(pierLightPositions);
        }

        List<RoadCorridorPlan.CorridorSlice> slices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slices.add(new RoadCorridorPlan.CorridorSlice(
                    i,
                    deckCenters.get(i),
                    segmentKinds.get(i),
                    buildSurfacePositions(centerPath, i, placementHeights),
                    List.of(),
                    List.of(),
                    supportPositionsByIndex.get(i),
                    pierLightPositionsByIndex.get(i)
            ));
        }
        return new RoadCorridorPlan(centerPath, slices, buildNavigationChannel(centerPath, navigableIndexes), valid);
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
                                                         Set<Integer> bridgeIndexes,
                                                         Set<Integer> bridgeHeadIndexes,
                                                         Set<Integer> navigableIndexes) {
        if (navigableIndexes.contains(index)) {
            return RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN;
        }
        if (bridgeHeadIndexes.contains(index)) {
            return RoadCorridorPlan.SegmentKind.BRIDGE_HEAD;
        }
        if (bridgeIndexes.contains(index)) {
            return RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
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

    private static Set<Integer> collectSupportRequiredIndexes(List<RoadPlacementPlan.BridgeRange> bridgeRanges, int pathSize) {
        Set<Integer> supportIndexes = new HashSet<>();
        if (bridgeRanges == null || bridgeRanges.isEmpty() || pathSize <= 0) {
            return supportIndexes;
        }
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.startIndex());
            int end = Math.min(pathSize - 1, range.endIndex());
            for (int i = start; i <= end; i += SUPPORT_INTERVAL) {
                supportIndexes.add(i);
            }
        }
        return supportIndexes;
    }

    private static List<BlockPos> buildSupportPositions(BlockPos deckCenter) {
        List<BlockPos> supportPositions = new ArrayList<>(SUPPORT_DEPTH);
        for (int depth = 1; depth <= SUPPORT_DEPTH; depth++) {
            supportPositions.add(deckCenter.below(depth));
        }
        return List.copyOf(supportPositions);
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

    private static RoadCorridorPlan.NavigationChannel buildNavigationChannel(List<BlockPos> centerPath, Set<Integer> navigableIndexes) {
        if (centerPath.isEmpty() || navigableIndexes.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int index : navigableIndexes) {
            if (index < 0 || index >= centerPath.size()) {
                continue;
            }
            BlockPos deckCenter = centerPath.get(index).above();
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
