package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

public final class RoadBridgePlanner {

    private static final int BRIDGE_RANGE_MERGE_GAP = 4;
    private static final int MIN_BRIDGE_RANGE_LENGTH = 3;
    private static final int MAX_ARCH_SPAN_COLUMNS = 7;
    private static final int MIN_UNSUPPORTED_FOR_ARCH = 4;
    private static final int MIN_DROP_FOR_ARCH = 6;
    private static final int MAX_PIER_ANCHOR_GAP = 3;
    private static final int NAVIGABLE_WATER_CLEARANCE = 5;
    private static final int EXTRA_CENTER_PIER_MIN_GAP = 18;

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
        BRIDGE_HEAD_PLATFORM,
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
        BlockPos startPos = Objects.requireNonNull(centerPath.get(start), "centerPath contains null at start");
        int startDeckY = Math.max(startPos.getY() + 1, terrainYAt.applyAsInt(start) + 1);
        BlockPos endPos = Objects.requireNonNull(centerPath.get(end), "centerPath contains null at end");
        int endDeckY = Math.max(endPos.getY() + 1, terrainYAt.applyAsInt(end) + 1);
        int maxWaterSurface = Integer.MIN_VALUE;
        int waterborneStart = -1;
        int waterborneEnd = -1;
        for (int i = start; i <= end; i++) {
            if (!isWaterborneColumn(i, terrainYAt, waterSurfaceYAt)) {
                continue;
            }
            if (waterborneStart < 0) {
                waterborneStart = i;
            }
            waterborneEnd = i;
            maxWaterSurface = Math.max(maxWaterSurface, waterSurfaceYAt.applyAsInt(i));
        }
        int navigableClearanceDeckY = maxWaterSurface == Integer.MIN_VALUE
                ? Math.max(startDeckY, endDeckY)
                : maxWaterSurface + NAVIGABLE_WATER_CLEARANCE;
        int mainDeckY = Math.max(navigableClearanceDeckY, Math.max(startDeckY, endDeckY));

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
            if (foundationSupportedAt.test(i) && !navigableAt.test(i)) {
                supportableInterior.add(i);
            }
        }

        StructuredPierSelection selection = selectStructuredPierAnchors(
                start,
                end,
                navigableStart,
                navigableEnd,
                supportableInterior
        );

        boolean valid = selection.leftTransitionAnchor() >= 0 || selection.rightTransitionAnchor() >= 0;
        List<BridgePierNode> nodes = new ArrayList<>();
        int mainStartIndex = resolveMainStartIndex(
                start,
                end,
                navigableStart,
                selection.leftTransitionAnchor()
        );
        int mainEndIndex = resolveMainEndIndex(
                start,
                end,
                navigableEnd,
                selection.rightTransitionAnchor()
        );
        if (waterborneStart > start && waterborneEnd < end) {
            mainStartIndex = Math.min(mainStartIndex, waterborneStart);
            mainEndIndex = Math.max(mainEndIndex, waterborneEnd);
        }
        List<BridgeDeckSegment> deckSegments = buildStructuredPierDeckSegments(
                start,
                end,
                startDeckY,
                endDeckY,
                mainDeckY,
                mainStartIndex,
                mainEndIndex
        );
        int[] deckProfile = materializeDeckProfile(
                start,
                end,
                startDeckY,
                endDeckY,
                mainDeckY,
                deckSegments
        );

        nodes.add(new BridgePierNode(
                start,
                new BlockPos(startPos.getX(), startDeckY, startPos.getZ()),
                new BlockPos(startPos.getX(), terrainYAt.applyAsInt(start), startPos.getZ()),
                startDeckY,
                BridgeNodeRole.ABUTMENT
        ));

        for (int anchor : selection.orderedAnchors()) {
            BlockPos anchorPos = Objects.requireNonNull(centerPath.get(anchor), "centerPath contains null at anchor " + anchor);
            BridgeNodeRole role = selection.channelAnchors().contains(anchor) ? BridgeNodeRole.CHANNEL_PIER : BridgeNodeRole.PIER;
            int anchorDeck = deckProfile[anchor - start];
            nodes.add(new BridgePierNode(
                    anchor,
                    new BlockPos(anchorPos.getX(), anchorDeck, anchorPos.getZ()),
                    new BlockPos(anchorPos.getX(), terrainYAt.applyAsInt(anchor), anchorPos.getZ()),
                    anchorDeck,
                    role
            ));
        }

        nodes.add(new BridgePierNode(
                end,
                new BlockPos(endPos.getX(), endDeckY, endPos.getZ()),
                new BlockPos(endPos.getX(), terrainYAt.applyAsInt(end), endPos.getZ()),
                endDeckY,
                BridgeNodeRole.ABUTMENT
        ));

        if (deckSegments.isEmpty()) {
            deckSegments = List.of(new BridgeDeckSegment(start, end, BridgeDeckSegmentType.MAIN_LEVEL, mainDeckY, mainDeckY));
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

    private static boolean isWaterborneColumn(int index,
                                              IntUnaryOperator terrainYAt,
                                              IntUnaryOperator waterSurfaceYAt) {
        return waterSurfaceYAt.applyAsInt(index) > terrainYAt.applyAsInt(index);
    }

    private static StructuredPierSelection selectStructuredPierAnchors(int start,
                                                                       int end,
                                                                       int navigableStart,
                                                                       int navigableEnd,
                                                                       List<Integer> supportableInterior) {
        if (supportableInterior == null || supportableInterior.isEmpty()) {
            return new StructuredPierSelection(-1, -1, List.of(), Set.of());
        }
        List<Integer> ordered = supportableInterior.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (ordered.isEmpty()) {
            return new StructuredPierSelection(-1, -1, List.of(), Set.of());
        }

        int leftTransition = navigableStart >= 0
                ? findNearestAnchorAtOrBefore(ordered, navigableStart - 1)
                : ordered.get(0);
        int rightTransition = navigableEnd >= 0
                ? findNearestAnchorAtOrAfter(ordered, navigableEnd + 1)
                : ordered.get(ordered.size() - 1);

        LinkedHashSet<Integer> anchors = new LinkedHashSet<>();
        LinkedHashSet<Integer> channelAnchors = new LinkedHashSet<>();
        if (leftTransition >= 0) {
            anchors.add(leftTransition);
            if (navigableStart >= 0) {
                channelAnchors.add(leftTransition);
            }
        }
        if (rightTransition >= 0 && rightTransition != leftTransition) {
            anchors.add(rightTransition);
            if (navigableEnd >= 0) {
                channelAnchors.add(rightTransition);
            }
        }

        List<Integer> between = ordered.stream()
                .filter(index -> index > leftTransition && index < rightTransition)
                .toList();
        if ((rightTransition - leftTransition) > EXTRA_CENTER_PIER_MIN_GAP && !between.isEmpty()) {
            int midpoint = (leftTransition + rightTransition) / 2;
            int centerAnchor = nearestSupportableByDistance(between, midpoint);
            if (centerAnchor >= 0) {
                anchors.add(centerAnchor);
            }
        }

        return new StructuredPierSelection(
                leftTransition,
                rightTransition,
                anchors.stream().sorted().toList(),
                Set.copyOf(channelAnchors)
        );
    }

    private static int resolveMainStartIndex(int start,
                                             int end,
                                             int navigableStart,
                                             int leftTransitionAnchor) {
        if (navigableStart >= 0) {
            return Math.max(start, Math.min(end, navigableStart));
        }
        if (leftTransitionAnchor >= 0) {
            return Math.max(start, Math.min(end, leftTransitionAnchor));
        }
        return start;
    }

    private static int resolveMainEndIndex(int start,
                                           int end,
                                           int navigableEnd,
                                           int rightTransitionAnchor) {
        if (navigableEnd >= 0) {
            return Math.max(start, Math.min(end, navigableEnd));
        }
        if (rightTransitionAnchor >= 0) {
            return Math.max(start, Math.min(end, rightTransitionAnchor));
        }
        return end;
    }

    private static List<Integer> mergeStructuredApproachAnchors(int start,
                                                                int end,
                                                                int navigableStart,
                                                                int navigableEnd,
                                                                int startDeckY,
                                                                int endDeckY,
                                                                int mainDeckY,
                                                                List<Integer> supportableInterior,
                                                                List<Integer> interiorAnchors) {
        if (supportableInterior == null || supportableInterior.isEmpty() || interiorAnchors == null || interiorAnchors.isEmpty() || navigableStart < 0 || navigableEnd < 0) {
            return interiorAnchors == null ? List.of() : interiorAnchors;
        }
        LinkedHashSet<Integer> anchors = new LinkedHashSet<>(interiorAnchors);
        if (supportableInterior.contains(navigableStart)) {
            anchors.add(navigableStart);
        }
        if (supportableInterior.contains(navigableEnd)) {
            anchors.add(navigableEnd);
        }
        addStructuredRampAnchors(anchors, supportableInterior, start, navigableStart, Math.max(0, mainDeckY - startDeckY), true);
        addStructuredRampAnchors(anchors, supportableInterior, end, navigableEnd, Math.max(0, mainDeckY - endDeckY), false);
        return anchors.stream()
                .filter(index -> index > start && index < end)
                .distinct()
                .sorted()
                .toList();
    }

    private static void addStructuredRampAnchors(LinkedHashSet<Integer> anchors,
                                                 List<Integer> supportableInterior,
                                                 int edgeIndex,
                                                 int plateauBoundary,
                                                 int deckDelta,
                                                 boolean ascending) {
        if (anchors == null || supportableInterior == null || supportableInterior.isEmpty() || deckDelta <= 0 || plateauBoundary < 0) {
            return;
        }
        int min = ascending ? edgeIndex + 1 : plateauBoundary + 1;
        int max = ascending ? plateauBoundary - 1 : edgeIndex - 1;
        for (int step = 1; step < deckDelta; step++) {
            int target = ascending ? edgeIndex + (step * 2) : edgeIndex - (step * 2);
            int anchor = nearestSupportableInRange(supportableInterior, target, min, max, ascending);
            if (anchor >= 0) {
                anchors.add(anchor);
            }
        }
        if (supportableInterior.contains(plateauBoundary)) {
            anchors.add(plateauBoundary);
        }
    }

    private static int nearestSupportableInRange(List<Integer> supportableInterior,
                                                 int target,
                                                 int min,
                                                 int max,
                                                 boolean ascending) {
        int match = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index : supportableInterior) {
            if (index < min || index > max) {
                continue;
            }
            int distance = Math.abs(index - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                match = index;
                continue;
            }
            if (distance == bestDistance && match >= 0) {
                if (ascending && index > match) {
                    match = index;
                } else if (!ascending && index < match) {
                    match = index;
                }
            }
        }
        return match;
    }

    private static Map<Integer, Integer> buildStructuredAnchorDeckHeights(List<Integer> interiorAnchors,
                                                                          int leftMainAnchor,
                                                                          int rightMainAnchor,
                                                                          int startDeckY,
                                                                          int endDeckY,
                                                                          int mainDeckY) {
        Map<Integer, Integer> anchorDeckY = new HashMap<>();
        if (interiorAnchors == null || interiorAnchors.isEmpty()) {
            return anchorDeckY;
        }
        if (leftMainAnchor < 0 || rightMainAnchor < 0) {
            for (int anchor : interiorAnchors) {
                anchorDeckY.put(anchor, mainDeckY);
            }
            return anchorDeckY;
        }

        List<Integer> leftRampAnchors = interiorAnchors.stream()
                .filter(index -> index <= leftMainAnchor)
                .toList();
        int leftSteps = leftRampAnchors.size();
        int leftDelta = Math.max(0, mainDeckY - startDeckY);
        for (int i = 0; i < leftRampAnchors.size(); i++) {
            int rise = scaledRampRise(i + 1, leftSteps, leftDelta);
            if (leftDelta > 0 && i < leftRampAnchors.size() - 1) {
                rise = Math.max(1, rise);
            }
            int target = startDeckY + rise;
            anchorDeckY.put(leftRampAnchors.get(i), Math.min(mainDeckY, target));
        }

        for (int anchor : interiorAnchors) {
            if (anchor >= leftMainAnchor && anchor <= rightMainAnchor) {
                anchorDeckY.put(anchor, mainDeckY);
            }
        }

        List<Integer> rightRampAnchors = interiorAnchors.stream()
                .filter(index -> index >= rightMainAnchor)
                .toList();
        int rightSteps = rightRampAnchors.size();
        int rightDelta = Math.max(0, mainDeckY - endDeckY);
        for (int i = 0; i < rightRampAnchors.size(); i++) {
            int drop = scaledRampRise(i, rightSteps, rightDelta);
            if (rightDelta > 0 && i > 0) {
                drop = Math.max(1, drop);
            }
            int target = mainDeckY - drop;
            anchorDeckY.put(rightRampAnchors.get(i), Math.max(endDeckY, target));
        }
        return anchorDeckY;
    }

    private static int scaledRampRise(int completedSteps, int totalSteps, int deckDelta) {
        if (completedSteps <= 0 || totalSteps <= 0 || deckDelta <= 0) {
            return 0;
        }
        return (int) Math.floor((double) completedSteps * (double) deckDelta / (double) totalSteps);
    }

    private static List<BridgeDeckSegment> buildStructuredPierDeckSegments(int start,
                                                                           int end,
                                                                           int startDeckY,
                                                                           int endDeckY,
                                                                           int mainDeckY,
                                                                           int mainStartIndex,
                                                                           int mainEndIndex) {
        if (mainEndIndex < mainStartIndex) {
            return List.of(new BridgeDeckSegment(start, end, BridgeDeckSegmentType.MAIN_LEVEL, mainDeckY, mainDeckY));
        }
        ArrayList<BridgeDeckSegment> segments = new ArrayList<>();
        appendStructuredApproachSegments(
                segments,
                start,
                Math.min(mainStartIndex, end),
                startDeckY,
                mainDeckY,
                true
        );
        if (mainEndIndex >= mainStartIndex) {
            segments.add(new BridgeDeckSegment(
                    Math.max(start, mainStartIndex),
                    Math.min(end, mainEndIndex),
                    BridgeDeckSegmentType.MAIN_LEVEL,
                    mainDeckY,
                    mainDeckY
            ));
        }
        appendStructuredApproachSegments(
                segments,
                end,
                Math.max(start, mainEndIndex),
                endDeckY,
                mainDeckY,
                false
        );
        return List.copyOf(segments);
    }

    private static void appendStructuredApproachSegments(List<BridgeDeckSegment> segments,
                                                         int edgeIndex,
                                                         int mainBoundaryIndex,
                                                         int edgeDeckY,
                                                         int mainDeckY,
                                                         boolean ascending) {
        if (segments == null) {
            return;
        }
        if (ascending ? mainBoundaryIndex <= edgeIndex : mainBoundaryIndex >= edgeIndex) {
            return;
        }
        int rampStart = edgeIndex;
        int rampEnd = mainBoundaryIndex;
        int deckDelta = Math.max(0, mainDeckY - edgeDeckY);
        int rampColumns = Math.abs(rampEnd - rampStart);
        if (deckDelta <= 0 || rampColumns <= 0) {
            return;
        }
        if (rampColumns < deckDelta) {
            segments.add(new BridgeDeckSegment(
                    Math.min(rampStart, rampEnd),
                    Math.max(rampStart, rampEnd),
                    ascending ? BridgeDeckSegmentType.APPROACH_UP : BridgeDeckSegmentType.APPROACH_DOWN,
                    ascending ? edgeDeckY : mainDeckY,
                    ascending ? mainDeckY : edgeDeckY
            ));
            return;
        }

        int previousIndex = rampStart;
        int previousDeckY = edgeDeckY;
        for (int step = 1; step <= deckDelta; step++) {
            int calculatedBoundary = ascending
                    ? rampStart + (int) Math.ceil((double) step * (double) rampColumns / (double) deckDelta)
                    : rampStart - (int) Math.ceil((double) step * (double) rampColumns / (double) deckDelta);
            int boundaryIndex = ascending
                    ? Math.min(rampEnd, Math.max(previousIndex + 1, calculatedBoundary))
                    : Math.max(rampEnd, Math.min(previousIndex - 1, calculatedBoundary));
            int targetDeckY = edgeDeckY + step;
            int segmentStartIndex = Math.min(previousIndex, boundaryIndex);
            int segmentEndIndex = Math.max(previousIndex, boundaryIndex);
            int segmentStartDeckY = previousIndex <= boundaryIndex ? previousDeckY : targetDeckY;
            int segmentEndDeckY = previousIndex <= boundaryIndex ? targetDeckY : previousDeckY;
            segments.add(new BridgeDeckSegment(
                    segmentStartIndex,
                    segmentEndIndex,
                    ascending ? BridgeDeckSegmentType.APPROACH_UP : BridgeDeckSegmentType.APPROACH_DOWN,
                    segmentStartDeckY,
                    segmentEndDeckY
            ));
            previousIndex = boundaryIndex;
            previousDeckY = targetDeckY;
        }
    }

    private static int[] materializeDeckProfile(int start,
                                                int end,
                                                int startDeckY,
                                                int endDeckY,
                                                int mainDeckY,
                                                List<BridgeDeckSegment> deckSegments) {
        int[] profile = new int[end - start + 1];
        java.util.Arrays.fill(profile, Integer.MIN_VALUE);
        profile[0] = startDeckY;
        profile[profile.length - 1] = endDeckY;
        if (deckSegments == null || deckSegments.isEmpty()) {
            for (int i = 0; i < profile.length; i++) {
                if (profile[i] == Integer.MIN_VALUE) {
                    profile[i] = mainDeckY;
                }
            }
            return profile;
        }
        for (BridgeDeckSegment segment : deckSegments) {
            if (segment == null) {
                continue;
            }
            applySegmentToProfile(profile, start, segment);
        }
        for (int i = 0; i < profile.length; i++) {
            if (profile[i] == Integer.MIN_VALUE) {
                profile[i] = mainDeckY;
            }
        }
        return profile;
    }

    private static void applySegmentToProfile(int[] profile,
                                              int profileStart,
                                              BridgeDeckSegment segment) {
        int start = Math.max(profileStart, segment.startIndex());
        int end = Math.min(profileStart + profile.length - 1, segment.endIndex());
        if (end < start) {
            return;
        }
        int localStart = start - profileStart;
        int localEnd = end - profileStart;
        if (start == end) {
            profile[localStart] = Math.max(segment.startDeckY(), segment.endDeckY());
            return;
        }
        int run = end - start;
        for (int absolute = start; absolute <= end; absolute++) {
            int offset = absolute - start;
            int target = segment.startDeckY() + (int) Math.floor((double) offset * (double) (segment.endDeckY() - segment.startDeckY()) / (double) run);
            profile[absolute - profileStart] = target;
        }
    }

    private static List<Integer> preferGentleApproachAnchorColumns(int start,
                                                                   int end,
                                                                   int startDeckY,
                                                                   int endDeckY,
                                                                   int mainDeckY,
                                                                   List<Integer> supportableInterior) {
        if (supportableInterior == null || supportableInterior.isEmpty()) {
            return List.of();
        }
        int spanLength = end - start;
        int leftBuffer = Math.max(0, Math.min(mainDeckY - startDeckY, Math.max(0, spanLength / 2)));
        int rightBuffer = Math.max(0, Math.min(mainDeckY - endDeckY, Math.max(0, spanLength / 2)));
        if (leftBuffer == 0 && rightBuffer == 0) {
            return List.copyOf(supportableInterior);
        }
        int preferredStart = start + leftBuffer;
        int preferredEnd = end - rightBuffer;
        if (preferredEnd < preferredStart) {
            return List.of();
        }
        return supportableInterior.stream()
                .filter(index -> index >= preferredStart && index <= preferredEnd)
                .toList();
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

    private static int nearestSupportableByDistance(List<Integer> supportableInterior, int target) {
        int match = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index : supportableInterior) {
            int distance = Math.abs(index - target);
            if (distance < bestDistance || (distance == bestDistance && index < match)) {
                bestDistance = distance;
                match = index;
            }
        }
        return match;
    }

    private record StructuredPierSelection(int leftTransitionAnchor,
                                           int rightTransitionAnchor,
                                           List<Integer> orderedAnchors,
                                           Set<Integer> channelAnchors) {
    }
}
