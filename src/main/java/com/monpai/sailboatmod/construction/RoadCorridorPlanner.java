package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

public final class RoadCorridorPlanner {
    private static final int SUPPORT_DEPTH = 3;
    private static final int RAILING_LIGHT_OFFSET = 3;
    private static final int LAND_STREETLIGHT_INTERVAL = 24;
    private static final int BRIDGE_LIGHT_INTERVAL = 5;
    private static final int MIN_BRIDGE_SPAN_FOR_PIERS = 7;
    private static final int TARGET_PIER_SPACING = 4;

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
                pierLightPositions = buildBridgeEdgeMarkerPositions(centerPath, i, deckCenter);
            }
            deckCenters.add(deckCenter);
            segmentKinds.add(segmentKind);
            railingLightPositionsByIndex.add(railingLightPositions);
            supportPositionsByIndex.add(supportPositions);
            pierLightPositionsByIndex.add(pierLightPositions);
        }
        ensureBridgeLightingCoverage(centerPath, bridgeRanges, segmentKinds, deckCenters, railingLightPositionsByIndex);

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

    public static RoadCorridorPlan plan(List<BlockPos> centerPath,
                                        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans,
                                        int[] placementHeights) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(bridgePlans, "bridgePlans");
        Objects.requireNonNull(placementHeights, "placementHeights");
        int size = centerPath.size();
        if (placementHeights.length != size) {
            throw new IllegalArgumentException("placementHeights size must match centerPath size");
        }

        Map<Integer, RoadBridgePlanner.BridgeSpanPlan> planByIndex = expandBridgePlans(bridgePlans, size);
        Map<Integer, RoadBridgePlanner.BridgePierNode> supportNodeByIndex = supportNodesByIndex(bridgePlans);
        List<BlockPos> deckCenters = new ArrayList<>(size);
        List<RoadCorridorPlan.CorridorSlice> slices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos center = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i);
            BlockPos deckCenter = new BlockPos(center.getX(), placementHeights[i], center.getZ()).immutable();
            deckCenters.add(deckCenter);
            RoadBridgePlanner.BridgeSpanPlan bridgePlan = planByIndex.get(i);
            RoadBridgePlanner.BridgeMode bridgeMode = bridgePlan == null ? RoadBridgePlanner.BridgeMode.NONE : bridgePlan.mode();
            RoadCorridorPlan.SegmentKind segmentKind = classify(i, bridgePlan, supportNodeByIndex, size);
            List<BlockPos> surfacePositions = buildSurfacePositions(centerPath, i, placementHeights);
            List<BlockPos> supportPositions = List.of();
            List<BlockPos> pierLightPositions = List.of();
            RoadBridgePlanner.BridgePierNode supportNode = supportNodeByIndex.get(i);
            if (bridgeMode == RoadBridgePlanner.BridgeMode.PIER_BRIDGE && supportNode != null) {
                supportPositions = buildSupportPositions(supportNode.foundationPos(), deckCenter.getY());
                pierLightPositions = buildBridgeEdgeMarkerPositions(centerPath, i, deckCenter);
            }
            slices.add(new RoadCorridorPlan.CorridorSlice(
                    i,
                    deckCenter,
                    segmentKind,
                    bridgeMode,
                    surfacePositions,
                    buildExcavationPositions(surfacePositions, segmentKind),
                    buildClearancePositions(surfacePositions, segmentKind),
                    buildRailingLightPositions(centerPath, i, deckCenter, segmentKind),
                    supportPositions,
                    pierLightPositions
            ));
        }
        return new RoadCorridorPlan(centerPath, slices, buildNavigationChannelFromPlans(deckCenters, bridgePlans), true);
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

    private static void ensureBridgeLightingCoverage(List<BlockPos> centerPath,
                                                     List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                     List<RoadCorridorPlan.SegmentKind> segmentKinds,
                                                     List<BlockPos> deckCenters,
                                                     List<List<BlockPos>> railingLightPositionsByIndex) {
        if (centerPath == null
                || centerPath.isEmpty()
                || bridgeRanges == null
                || bridgeRanges.isEmpty()
                || segmentKinds == null
                || deckCenters == null
                || railingLightPositionsByIndex == null) {
            return;
        }
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.startIndex());
            int end = Math.min(centerPath.size() - 1, range.endIndex());
            boolean alreadyLit = false;
            for (int i = start; i <= end; i++) {
                List<BlockPos> positions = railingLightPositionsByIndex.get(i);
                if (positions != null && !positions.isEmpty()) {
                    alreadyLit = true;
                    break;
                }
            }
            if (alreadyLit) {
                continue;
            }
            int fallbackIndex = (start + end) / 2;
            int navigableStart = -1;
            int navigableEnd = -1;
            for (int i = start; i <= end; i++) {
                if (segmentKinds.get(i) == RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN) {
                    if (navigableStart < 0) {
                        navigableStart = i;
                    }
                    navigableEnd = i;
                }
            }
            if (navigableStart >= 0) {
                fallbackIndex = (navigableStart + navigableEnd) / 2;
            }
            railingLightPositionsByIndex.set(
                    fallbackIndex,
                    buildBridgeEdgeMarkerPositions(centerPath, fallbackIndex, deckCenters.get(fallbackIndex))
            );
        }
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
            int spanLength = end - start + 1;
            if (spanLength < MIN_BRIDGE_SPAN_FOR_PIERS) {
                continue;
            }
            int navigableStart = -1;
            int navigableEnd = -1;
            for (int i = start; i <= end; i++) {
                if (navigableIndexes.contains(i)) {
                    if (navigableStart < 0) {
                        navigableStart = i;
                    }
                    navigableEnd = i;
                    continue;
                }
                if (navigableStart >= 0) {
                    addPierAnchorIndex(supportIndexes, navigableStart - 1, start, end, bridgeHeadIndexes, navigableIndexes);
                    addPierAnchorIndex(supportIndexes, navigableEnd + 1, start, end, bridgeHeadIndexes, navigableIndexes);
                    navigableStart = -1;
                    navigableEnd = -1;
                }
            }
            if (navigableStart >= 0) {
                addPierAnchorIndex(supportIndexes, navigableStart - 1, start, end, bridgeHeadIndexes, navigableIndexes);
                addPierAnchorIndex(supportIndexes, navigableEnd + 1, start, end, bridgeHeadIndexes, navigableIndexes);
            }
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
            for (int index : distributeDiscretePierAnchors(supportableInterior)) {
                supportIndexes.add(index);
            }
        }
        return new SupportPlacementPlan(Set.copyOf(supportIndexes), valid);
    }

    private static List<Integer> distributeDiscretePierAnchors(List<Integer> supportableInterior) {
        if (supportableInterior == null || supportableInterior.isEmpty()) {
            return List.of();
        }
        List<Integer> ordered = supportableInterior.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        int anchorCount = Math.max(1, (int) Math.round((double) ordered.size() / (double) TARGET_PIER_SPACING));
        anchorCount = Math.min(anchorCount, ordered.size());
        if (anchorCount == 1) {
            return List.of(ordered.get(ordered.size() / 2));
        }
        LinkedHashSet<Integer> anchors = new LinkedHashSet<>();
        for (int i = 0; i < anchorCount; i++) {
            int sampleIndex = (int) Math.round((double) i * (ordered.size() - 1) / (double) (anchorCount - 1));
            anchors.add(ordered.get(sampleIndex));
        }
        return List.copyOf(anchors);
    }

    private static void addPierAnchorIndex(Set<Integer> supportIndexes,
                                           int candidateIndex,
                                           int rangeStart,
                                           int rangeEnd,
                                           Set<Integer> bridgeHeadIndexes,
                                           Set<Integer> navigableIndexes) {
        if (candidateIndex < rangeStart || candidateIndex > rangeEnd) {
            return;
        }
        if (bridgeHeadIndexes.contains(candidateIndex) || navigableIndexes.contains(candidateIndex)) {
            return;
        }
        supportIndexes.add(candidateIndex);
    }

    private static List<BlockPos> buildSupportPositions(BlockPos deckCenter) {
        List<BlockPos> supportPositions = new ArrayList<>(SUPPORT_DEPTH);
        for (int depth = 1; depth <= SUPPORT_DEPTH; depth++) {
            supportPositions.add(deckCenter.below(depth));
        }
        return List.copyOf(supportPositions);
    }

    private static List<BlockPos> buildSupportPositions(BlockPos foundationPos, int deckY) {
        if (foundationPos == null || deckY <= foundationPos.getY() + 1) {
            return List.of();
        }
        List<BlockPos> supportPositions = new ArrayList<>(Math.max(0, deckY - foundationPos.getY() - 1));
        for (int y = foundationPos.getY() + 1; y < deckY; y++) {
            supportPositions.add(new BlockPos(foundationPos.getX(), y, foundationPos.getZ()));
        }
        return List.copyOf(supportPositions);
    }

    private static List<BlockPos> buildRailingLightPositions(List<BlockPos> centerPath,
                                                             int index,
                                                             BlockPos deckCenter,
                                                             RoadCorridorPlan.SegmentKind segmentKind) {
        if (deckCenter == null || centerPath == null || centerPath.isEmpty()) {
            return List.of();
        }
        if (segmentKind == RoadCorridorPlan.SegmentKind.LAND_APPROACH) {
            return buildLandStreetlightPositions(centerPath, index, deckCenter);
        }
        if (!supportsBridgeRailings(segmentKind)) {
            return List.of();
        }
        if (!shouldPlaceBridgeLight(index, segmentKind)) {
            return List.of();
        }
        int[] sideOffsets = resolveSideOffsets(centerPath, index);
        int sideX = sideOffsets[0];
        int sideZ = sideOffsets[1];

        return List.of(
                new BlockPos(deckCenter.getX() + (sideX * RAILING_LIGHT_OFFSET), deckCenter.getY(), deckCenter.getZ() + (sideZ * RAILING_LIGHT_OFFSET)),
                new BlockPos(deckCenter.getX() - (sideX * RAILING_LIGHT_OFFSET), deckCenter.getY(), deckCenter.getZ() - (sideZ * RAILING_LIGHT_OFFSET))
        );
    }

    private static List<BlockPos> buildLandStreetlightPositions(List<BlockPos> centerPath,
                                                                int index,
                                                                BlockPos deckCenter) {
        if (index <= 0 || index % LAND_STREETLIGHT_INTERVAL != 0) {
            return List.of();
        }
        int[] sideOffsets = resolveSideOffsets(centerPath, index);
        int sideX = sideOffsets[0];
        int sideZ = sideOffsets[1];
        int sideSign = ((index / LAND_STREETLIGHT_INTERVAL) % 2 == 0) ? 1 : -1;
        return List.of(new BlockPos(
                deckCenter.getX() + (sideX * RAILING_LIGHT_OFFSET * sideSign),
                deckCenter.getY(),
                deckCenter.getZ() + (sideZ * RAILING_LIGHT_OFFSET * sideSign)
        ));
    }

    private static List<BlockPos> buildBridgeEdgeMarkerPositions(List<BlockPos> centerPath,
                                                                 int index,
                                                                 BlockPos deckCenter) {
        int[] sideOffsets = resolveSideOffsets(centerPath, index);
        int sideX = sideOffsets[0];
        int sideZ = sideOffsets[1];
        return List.of(
                new BlockPos(deckCenter.getX() + (sideX * RAILING_LIGHT_OFFSET), deckCenter.getY(), deckCenter.getZ() + (sideZ * RAILING_LIGHT_OFFSET)),
                new BlockPos(deckCenter.getX() - (sideX * RAILING_LIGHT_OFFSET), deckCenter.getY(), deckCenter.getZ() - (sideZ * RAILING_LIGHT_OFFSET))
        );
    }

    private static int[] resolveSideOffsets(List<BlockPos> centerPath, int index) {
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
        return new int[] {sideX, sideZ};
    }

    private static boolean shouldPlaceBridgeLight(int index, RoadCorridorPlan.SegmentKind segmentKind) {
        if (segmentKind == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD) {
            return true;
        }
        if (segmentKind == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN) {
            return index % BRIDGE_LIGHT_INTERVAL == 0;
        }
        if (segmentKind == RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN) {
            return index % (BRIDGE_LIGHT_INTERVAL + 1) == 0;
        }
        return segmentKind == RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH
                && index % BRIDGE_LIGHT_INTERVAL == 0;
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
        return hasSurfaceAdjacency(current, next)
                || Math.abs(currentDeckHeight - nextDeckHeight) >= 1;
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

    private static boolean hasSurfaceAdjacency(List<BlockPos> current, List<BlockPos> next) {
        for (BlockPos pos : current) {
            if (isAdjacentToAny(pos, next)) {
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
        return segmentKind == RoadCorridorPlan.SegmentKind.APPROACH_RAMP
                || segmentKind == RoadCorridorPlan.SegmentKind.BRIDGE_HEAD
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

    private static Map<Integer, RoadBridgePlanner.BridgeSpanPlan> expandBridgePlans(List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans, int pathSize) {
        Map<Integer, RoadBridgePlanner.BridgeSpanPlan> planByIndex = new HashMap<>();
        if (bridgePlans == null || bridgePlans.isEmpty() || pathSize <= 0) {
            return planByIndex;
        }
        for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
            if (plan == null || !plan.valid()) {
                continue;
            }
            int start = Math.max(0, Math.min(pathSize - 1, plan.startIndex()));
            int end = Math.max(0, Math.min(pathSize - 1, plan.endIndex()));
            for (int i = start; i <= end; i++) {
                planByIndex.put(i, plan);
            }
        }
        return planByIndex;
    }

    private static Map<Integer, RoadBridgePlanner.BridgePierNode> supportNodesByIndex(List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        Map<Integer, RoadBridgePlanner.BridgePierNode> supportNodes = new HashMap<>();
        if (bridgePlans == null || bridgePlans.isEmpty()) {
            return supportNodes;
        }
        for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
            if (plan == null || !plan.valid()) {
                continue;
            }
            for (RoadBridgePlanner.BridgePierNode node : plan.nodes()) {
                if (node == null) {
                    continue;
                }
                if (node.role() == RoadBridgePlanner.BridgeNodeRole.PIER || node.role() == RoadBridgePlanner.BridgeNodeRole.CHANNEL_PIER) {
                    supportNodes.put(node.pathIndex(), node);
                }
            }
        }
        return supportNodes;
    }

    private static RoadCorridorPlan.SegmentKind classify(int index,
                                                         RoadBridgePlanner.BridgeSpanPlan bridgePlan,
                                                         Map<Integer, RoadBridgePlanner.BridgePierNode> supportNodeByIndex,
                                                         int pathSize) {
        if (bridgePlan == null) {
            return RoadCorridorPlan.SegmentKind.LAND_APPROACH;
        }
        int start = Math.max(0, Math.min(pathSize - 1, bridgePlan.startIndex()));
        int end = Math.max(0, Math.min(pathSize - 1, bridgePlan.endIndex()));
        if (index == start || index == end) {
            return RoadCorridorPlan.SegmentKind.BRIDGE_HEAD;
        }
        if (supportNodeByIndex.containsKey(index)) {
            return RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN;
        }
        if (bridgePlan.mode() == RoadBridgePlanner.BridgeMode.ARCH_SPAN) {
            return RoadCorridorPlan.SegmentKind.BRIDGE_HEAD;
        }
        return bridgePlan.navigableWaterBridge()
                ? RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN
                : RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH;
    }

    private static RoadCorridorPlan.NavigationChannel buildNavigationChannelFromPlans(List<BlockPos> deckCenters,
                                                                                      List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans) {
        if (deckCenters == null || deckCenters.isEmpty() || bridgePlans == null || bridgePlans.isEmpty()) {
            return null;
        }
        Set<Integer> navigableIndexes = new HashSet<>();
        for (RoadBridgePlanner.BridgeSpanPlan plan : bridgePlans) {
            if (plan == null || !plan.valid() || !plan.navigableWaterBridge()) {
                continue;
            }
            int start = Math.max(0, Math.min(deckCenters.size() - 1, plan.startIndex() + 1));
            int end = Math.max(0, Math.min(deckCenters.size() - 1, plan.endIndex() - 1));
            for (int i = start; i <= end; i++) {
                navigableIndexes.add(i);
            }
        }
        return buildNavigationChannel(deckCenters, navigableIndexes);
    }
}
