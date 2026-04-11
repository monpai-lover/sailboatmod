package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

public final class RoadBridgePlanner {

    private static final int MIN_UNSUPPORTED_FOR_ARCH = 4;
    private static final int MIN_DROP_FOR_ARCH = 6;
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

    static BridgeProfile navigableProfileForTest(int startIndex, int endIndex, int waterSurfaceY) {
        return buildNavigableBridgeProfile(startIndex, endIndex, waterSurfaceY);
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
