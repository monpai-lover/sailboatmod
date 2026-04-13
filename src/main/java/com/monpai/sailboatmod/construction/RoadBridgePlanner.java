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
}
