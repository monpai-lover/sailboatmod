package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

public final class RoadBridgePlanner {

    private static final int BRIDGE_RANGE_MERGE_GAP = 4;
    private static final int MIN_BRIDGE_RANGE_LENGTH = 3;
    private static final int MAX_ARCH_SPAN_COLUMNS = 6;
    private static final int MIN_UNSUPPORTED_FOR_ARCH = 4;
    private static final int MIN_DROP_FOR_ARCH = 6;
    private static final int MAX_PIER_ANCHOR_GAP = 3;
    private static final int NAVIGABLE_WATER_CLEARANCE = 5;

    private RoadBridgePlanner() {
    }

    public static BridgeKind classify(List<BlockPos> centerPath,
                                      IntPredicate unsupportedAt,
                                      IntUnaryOperator terrainYAt) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(unsupportedAt, "unsupportedAt");
        Objects.requireNonNull(terrainYAt, "terrainYAt");

        int unsupportedCount = 0;
        int maxTerrainDrop = 0;
        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos pathPos = Objects.requireNonNull(centerPath.get(i), "centerPath contains null at index " + i);
            if (!unsupportedAt.test(i)) {
                continue;
            }
            unsupportedCount++;
            int deckY = pathPos.getY() + 1;
            int terrainDrop = deckY - terrainYAt.applyAsInt(i);
            if (terrainDrop > maxTerrainDrop) {
                maxTerrainDrop = terrainDrop;
            }
        }

        if (unsupportedCount == 0) {
            return BridgeKind.NONE;
        }
        if (unsupportedCount < MIN_UNSUPPORTED_FOR_ARCH || maxTerrainDrop < MIN_DROP_FOR_ARCH) {
            return BridgeKind.FLAT;
        }
        return BridgeKind.ARCHED;
    }

    public static List<BridgeProfile> classifyRanges(List<BlockPos> centerPath,
                                                     List<RoadPlacementPlan.BridgeRange> bridgeRanges,
                                                     IntPredicate unsupportedAt,
                                                     IntUnaryOperator terrainYAt) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(bridgeRanges, "bridgeRanges");
        Objects.requireNonNull(unsupportedAt, "unsupportedAt");
        Objects.requireNonNull(terrainYAt, "terrainYAt");
        if (centerPath.isEmpty() || bridgeRanges.isEmpty()) {
            return List.of();
        }

        List<BridgeProfile> profiles = new ArrayList<>(bridgeRanges.size());
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, range.startIndex());
            int end = Math.min(centerPath.size() - 1, range.endIndex());
            if (end < start) {
                continue;
            }
            List<BlockPos> span = centerPath.subList(start, end + 1);
            BridgeKind kind = classify(
                    span,
                    localIndex -> unsupportedAt.test(start + localIndex),
                    localIndex -> terrainYAt.applyAsInt(start + localIndex)
            );
            profiles.add(new BridgeProfile(start, end, kind));
        }
        return List.copyOf(profiles);
    }

    public static BridgeProfile buildNavigableBridgeProfile(int startIndex, int endIndex, int waterSurfaceY) {
        return new BridgeProfile(startIndex, endIndex, BridgeKind.ARCHED, waterSurfaceY + NAVIGABLE_WATER_CLEARANCE, true);
    }

    public static BridgeSpanPlan planBridgeSpan(List<BlockPos> centerPath,
                                                RoadPlacementPlan.BridgeRange range,
                                                IntPredicate unsupportedAt,
                                                IntPredicate navigableAt,
                                                IntUnaryOperator terrainYAt,
                                                IntUnaryOperator waterSurfaceYAt,
                                                IntPredicate foundationSupportedAt) {
        Objects.requireNonNull(centerPath, "centerPath");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(unsupportedAt, "unsupportedAt");
        Objects.requireNonNull(navigableAt, "navigableAt");
        Objects.requireNonNull(terrainYAt, "terrainYAt");
        Objects.requireNonNull(waterSurfaceYAt, "waterSurfaceYAt");
        Objects.requireNonNull(foundationSupportedAt, "foundationSupportedAt");

        if (centerPath.isEmpty()) {
            return new BridgeSpanPlan(0, 0, BridgeMode.NONE, List.of(), List.of(), 0, false, false);
        }

        int start = Math.max(0, Math.min(centerPath.size() - 1, range.startIndex()));
        int end = Math.max(0, Math.min(centerPath.size() - 1, range.endIndex()));
        if (end < start) {
            return new BridgeSpanPlan(start, start, BridgeMode.NONE, List.of(), List.of(), 0, false, false);
        }

        if (canUseArchSpan(start, end, unsupportedAt)) {
            return buildArchSpan(centerPath, start, end, terrainYAt);
        }
        return buildPierBridge(centerPath, start, end, navigableAt, terrainYAt, waterSurfaceYAt, foundationSupportedAt);
    }

    static BridgeSpanPlan planBridgeSpanForTest(List<BlockPos> centerPath,
                                                RoadPlacementPlan.BridgeRange range,
                                                IntPredicate unsupportedAt,
                                                IntPredicate navigableAt,
                                                IntUnaryOperator terrainYAt,
                                                IntUnaryOperator waterSurfaceYAt,
                                                IntPredicate foundationSupportedAt) {
        return planBridgeSpan(centerPath, range, unsupportedAt, navigableAt, terrainYAt, waterSurfaceYAt, foundationSupportedAt);
    }

    public static List<RoadPlacementPlan.BridgeRange> normalizeRanges(List<RoadPlacementPlan.BridgeRange> ranges, int pathSize) {
        if (ranges == null || ranges.isEmpty() || pathSize <= 0) {
            return List.of();
        }
        List<RoadPlacementPlan.BridgeRange> ordered = new ArrayList<>(ranges.size());
        for (RoadPlacementPlan.BridgeRange range : ranges) {
            if (range == null) {
                continue;
            }
            int start = Math.max(0, Math.min(pathSize - 1, range.startIndex()));
            int end = Math.max(0, Math.min(pathSize - 1, range.endIndex()));
            if (end < start) {
                continue;
            }
            ordered.add(new RoadPlacementPlan.BridgeRange(start, end));
        }
        if (ordered.isEmpty()) {
            return List.of();
        }
        ordered.sort(Comparator.comparingInt(RoadPlacementPlan.BridgeRange::startIndex));
        List<RoadPlacementPlan.BridgeRange> merged = new ArrayList<>(ordered.size());
        int currentStart = ordered.get(0).startIndex();
        int currentEnd = ordered.get(0).endIndex();
        for (int i = 1; i < ordered.size(); i++) {
            RoadPlacementPlan.BridgeRange next = ordered.get(i);
            int gap = next.startIndex() - currentEnd - 1;
            if (gap <= BRIDGE_RANGE_MERGE_GAP) {
                currentEnd = Math.max(currentEnd, next.endIndex());
                continue;
            }
            if ((currentEnd - currentStart + 1) >= MIN_BRIDGE_RANGE_LENGTH) {
                merged.add(new RoadPlacementPlan.BridgeRange(currentStart, currentEnd));
            }
            currentStart = next.startIndex();
            currentEnd = next.endIndex();
        }
        if ((currentEnd - currentStart + 1) >= MIN_BRIDGE_RANGE_LENGTH) {
            merged.add(new RoadPlacementPlan.BridgeRange(currentStart, currentEnd));
        }
        return List.copyOf(merged);
    }

    public static List<Integer> distributePierAnchors(int startIndex, int endIndex, List<Integer> supportableIndexes) {
        if (supportableIndexes == null || supportableIndexes.isEmpty() || endIndex < startIndex) {
            return List.of();
        }
        List<Integer> ordered = supportableIndexes.stream()
                .filter(Objects::nonNull)
                .filter(index -> index >= startIndex && index <= endIndex)
                .distinct()
                .sorted()
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        int spanLength = endIndex - startIndex + 1;
        int anchorCount = Math.max(1, (int) Math.ceil((double) spanLength / (double) MAX_PIER_ANCHOR_GAP));
        anchorCount = Math.min(anchorCount, ordered.size());
        if (anchorCount == 1) {
            return List.of(ordered.get(ordered.size() / 2));
        }
        LinkedHashSet<Integer> anchors = new LinkedHashSet<>();
        for (int i = 0; i < anchorCount; i++) {
            int sampleIndex = (int) Math.round((double) i * (ordered.size() - 1) / (double) (anchorCount - 1));
            anchors.add(ordered.get(sampleIndex));
        }
        if (anchors.size() < anchorCount) {
            for (int index : ordered) {
                anchors.add(index);
                if (anchors.size() >= anchorCount) {
                    break;
                }
            }
        }
        return List.copyOf(anchors);
    }

    static BridgeProfile navigableProfileForTest(int startIndex, int endIndex, int waterSurfaceY) {
        return buildNavigableBridgeProfile(startIndex, endIndex, waterSurfaceY);
    }

    static List<RoadPlacementPlan.BridgeRange> normalizeRangesForTest(List<RoadPlacementPlan.BridgeRange> ranges, int pathSize) {
        return normalizeRanges(ranges, pathSize);
    }

    static List<Integer> distributePierAnchorsForTest(int startIndex, int endIndex, List<Integer> supportableIndexes) {
        return distributePierAnchors(startIndex, endIndex, supportableIndexes);
    }

    public enum BridgeKind {
        NONE,
        FLAT,
        ARCHED
    }

    public enum BridgeMode {
        NONE,
        ARCH_SPAN,
        PIER_BRIDGE
    }

    public enum BridgeNodeRole {
        ABUTMENT,
        PIER,
        CHANNEL_PIER
    }

    public enum BridgeDeckSegmentType {
        ARCHED_SPAN,
        APPROACH_UP,
        MAIN_LEVEL,
        APPROACH_DOWN
    }

    public record BridgeProfile(int startIndex, int endIndex, BridgeKind kind, int deckHeight, boolean navigableWaterBridge) {
        public BridgeProfile {
            if (startIndex < 0) {
                throw new IllegalArgumentException("startIndex must be non-negative");
            }
            if (endIndex < startIndex) {
                throw new IllegalArgumentException("endIndex must be >= startIndex");
            }
            kind = kind == null ? BridgeKind.NONE : kind;
            deckHeight = Math.max(0, deckHeight);
        }

        public BridgeProfile(int startIndex, int endIndex, BridgeKind kind) {
            this(startIndex, endIndex, kind, 0, false);
        }
    }

    public record BridgePierNode(int pathIndex,
                                 BlockPos worldPos,
                                 BlockPos foundationPos,
                                 int deckY,
                                 BridgeNodeRole role) {
        public BridgePierNode {
            if (pathIndex < 0) {
                throw new IllegalArgumentException("pathIndex must be non-negative");
            }
            worldPos = Objects.requireNonNull(worldPos, "worldPos").immutable();
            foundationPos = Objects.requireNonNull(foundationPos, "foundationPos").immutable();
            deckY = Math.max(0, deckY);
            role = Objects.requireNonNull(role, "role");
        }
    }

    public record BridgeDeckSegment(int startIndex,
                                    int endIndex,
                                    BridgeDeckSegmentType type,
                                    int startDeckY,
                                    int endDeckY) {
        public BridgeDeckSegment {
            if (startIndex < 0 || endIndex < startIndex) {
                throw new IllegalArgumentException("invalid bridge deck segment bounds");
            }
            type = Objects.requireNonNull(type, "type");
            startDeckY = Math.max(0, startDeckY);
            endDeckY = Math.max(0, endDeckY);
        }
    }

    public record BridgeSpanPlan(int startIndex,
                                 int endIndex,
                                 BridgeMode mode,
                                 List<BridgePierNode> nodes,
                                 List<BridgeDeckSegment> deckSegments,
                                 int mainDeckY,
                                 boolean navigableWaterBridge,
                                 boolean valid) {
        public BridgeSpanPlan {
            if (startIndex < 0 || endIndex < startIndex) {
                throw new IllegalArgumentException("invalid bridge span bounds");
            }
            mode = mode == null ? BridgeMode.NONE : mode;
            nodes = List.copyOf(nodes == null ? List.of() : nodes);
            deckSegments = List.copyOf(deckSegments == null ? List.of() : deckSegments);
            mainDeckY = Math.max(0, mainDeckY);
        }
    }

    private static boolean canUseArchSpan(int start, int end, IntPredicate unsupportedAt) {
        int unsupportedColumns = 0;
        for (int i = start; i <= end; i++) {
            if (unsupportedAt.test(i)) {
                unsupportedColumns++;
            }
        }
        return unsupportedColumns > 0 && unsupportedColumns <= MAX_ARCH_SPAN_COLUMNS;
    }

    private static BridgeSpanPlan buildArchSpan(List<BlockPos> centerPath,
                                                int start,
                                                int end,
                                                IntUnaryOperator terrainYAt) {
        BlockPos startPos = Objects.requireNonNull(centerPath.get(start), "centerPath contains null at start");
        BlockPos endPos = Objects.requireNonNull(centerPath.get(end), "centerPath contains null at end");
        int startDeckY = terrainYAt.applyAsInt(start) + 1;
        int endDeckY = terrainYAt.applyAsInt(end) + 1;
        List<BridgePierNode> nodes = List.of(
                new BridgePierNode(start, new BlockPos(startPos.getX(), startDeckY, startPos.getZ()),
                        new BlockPos(startPos.getX(), terrainYAt.applyAsInt(start), startPos.getZ()), startDeckY, BridgeNodeRole.ABUTMENT),
                new BridgePierNode(end, new BlockPos(endPos.getX(), endDeckY, endPos.getZ()),
                        new BlockPos(endPos.getX(), terrainYAt.applyAsInt(end), endPos.getZ()), endDeckY, BridgeNodeRole.ABUTMENT)
        );
        int mainDeckY = Math.max(startDeckY, endDeckY);
        return new BridgeSpanPlan(
                start,
                end,
                BridgeMode.ARCH_SPAN,
                nodes,
                List.of(new BridgeDeckSegment(start, end, BridgeDeckSegmentType.ARCHED_SPAN, startDeckY, endDeckY)),
                mainDeckY,
                false,
                true
        );
    }

    private static BridgeSpanPlan buildPierBridge(List<BlockPos> centerPath,
                                                  int start,
                                                  int end,
                                                  IntPredicate navigableAt,
                                                  IntUnaryOperator terrainYAt,
                                                  IntUnaryOperator waterSurfaceYAt,
                                                  IntPredicate foundationSupportedAt) {
        int navigableStart = -1;
        int navigableEnd = -1;
        for (int i = start; i <= end; i++) {
            if (navigableAt.test(i)) {
                if (navigableStart < 0) {
                    navigableStart = i;
                }
                navigableEnd = i;
            }
        }

        List<Integer> supportableInterior = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            boolean channelBoundary = i == navigableStart || i == navigableEnd;
            if (foundationSupportedAt.test(i) && (!navigableAt.test(i) || channelBoundary)) {
                supportableInterior.add(i);
            }
        }

        List<Integer> interiorAnchors = distributePierAnchors(start, end, supportableInterior);

        LinkedHashSet<Integer> channelAnchors = new LinkedHashSet<>();
        if (navigableStart >= 0) {
            int leftChannel = findNearestAnchorAtOrBefore(interiorAnchors, navigableStart - 1);
            int rightChannel = findNearestAnchorAtOrAfter(interiorAnchors, navigableEnd + 1);
            if (leftChannel >= 0) {
                channelAnchors.add(leftChannel);
            }
            if (rightChannel >= 0) {
                channelAnchors.add(rightChannel);
            }
        }

        boolean valid = !interiorAnchors.isEmpty();
        List<BridgePierNode> nodes = new ArrayList<>();

        BlockPos startPos = Objects.requireNonNull(centerPath.get(start), "centerPath contains null at start");
        int startDeckY = Math.max(startPos.getY() + 1, terrainYAt.applyAsInt(start) + 1);
        nodes.add(new BridgePierNode(
                start,
                new BlockPos(startPos.getX(), startDeckY, startPos.getZ()),
                new BlockPos(startPos.getX(), terrainYAt.applyAsInt(start), startPos.getZ()),
                startDeckY,
                BridgeNodeRole.ABUTMENT
        ));

        int maxWaterSurface = 0;
        for (int i = start; i <= end; i++) {
            maxWaterSurface = Math.max(maxWaterSurface, waterSurfaceYAt.applyAsInt(i));
        }
        int mainDeckY = Math.max(maxWaterSurface + NAVIGABLE_WATER_CLEARANCE, Math.max(startDeckY, Math.max(centerPath.get(end).getY() + 1, terrainYAt.applyAsInt(end) + 1)));

        for (int anchor : interiorAnchors) {
            BlockPos anchorPos = Objects.requireNonNull(centerPath.get(anchor), "centerPath contains null at anchor " + anchor);
            BridgeNodeRole role = channelAnchors.contains(anchor) ? BridgeNodeRole.CHANNEL_PIER : BridgeNodeRole.PIER;
            nodes.add(new BridgePierNode(
                    anchor,
                    new BlockPos(anchorPos.getX(), mainDeckY, anchorPos.getZ()),
                    new BlockPos(anchorPos.getX(), terrainYAt.applyAsInt(anchor), anchorPos.getZ()),
                    mainDeckY,
                    role
            ));
        }

        BlockPos endPos = Objects.requireNonNull(centerPath.get(end), "centerPath contains null at end");
        int endDeckY = Math.max(endPos.getY() + 1, terrainYAt.applyAsInt(end) + 1);
        nodes.add(new BridgePierNode(
                end,
                new BlockPos(endPos.getX(), endDeckY, endPos.getZ()),
                new BlockPos(endPos.getX(), terrainYAt.applyAsInt(end), endPos.getZ()),
                endDeckY,
                BridgeNodeRole.ABUTMENT
        ));

        List<BridgeDeckSegment> deckSegments = new ArrayList<>();
        if (!interiorAnchors.isEmpty()) {
            int firstAnchor = interiorAnchors.get(0);
            deckSegments.add(new BridgeDeckSegment(start, firstAnchor, BridgeDeckSegmentType.APPROACH_UP, startDeckY, mainDeckY));
            deckSegments.add(new BridgeDeckSegment(firstAnchor, interiorAnchors.get(interiorAnchors.size() - 1), BridgeDeckSegmentType.MAIN_LEVEL, mainDeckY, mainDeckY));
            deckSegments.add(new BridgeDeckSegment(interiorAnchors.get(interiorAnchors.size() - 1), end, BridgeDeckSegmentType.APPROACH_DOWN, mainDeckY, endDeckY));
        } else {
            deckSegments.add(new BridgeDeckSegment(start, end, BridgeDeckSegmentType.MAIN_LEVEL, mainDeckY, mainDeckY));
        }

        return new BridgeSpanPlan(
                start,
                end,
                BridgeMode.PIER_BRIDGE,
                nodes,
                deckSegments,
                mainDeckY,
                navigableStart >= 0,
                valid
        );
    }

    private static int findNearestAnchorAtOrBefore(List<Integer> anchors, int upperBound) {
        int match = -1;
        for (int anchor : anchors) {
            if (anchor <= upperBound) {
                match = anchor;
            }
        }
        return match;
    }

    private static int findNearestAnchorAtOrAfter(List<Integer> anchors, int lowerBound) {
        for (int anchor : anchors) {
            if (anchor >= lowerBound) {
                return anchor;
            }
        }
        return -1;
    }
}
