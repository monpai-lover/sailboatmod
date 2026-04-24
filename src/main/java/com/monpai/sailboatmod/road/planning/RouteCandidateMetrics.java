package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

public record RouteCandidateMetrics(int pathBlocks, int bridgeBlocks, double bridgeCoverageRatio) {
    private static final double BRIDGE_DOMINANT_RATIO = 0.35D;

    public static RouteCandidateMetrics from(List<BlockPos> path, List<BridgeSpan> spans) {
        int pathBlocks = path == null ? 0 : path.size();
        int bridgeBlocks = spans == null ? 0 : spans.stream()
                .filter(Objects::nonNull)
                .mapToInt(span -> Math.max(0, span.length()))
                .sum();
        double ratio = pathBlocks == 0 ? 0.0D : bridgeBlocks / (double) pathBlocks;
        return new RouteCandidateMetrics(pathBlocks, bridgeBlocks, ratio);
    }

    public boolean bridgeDominant() {
        return bridgeCoverageRatio >= BRIDGE_DOMINANT_RATIO;
    }

    public static boolean containsLongWaterBridge(List<BridgeSpan> spans, int maxAllowedLength) {
        return spans != null && spans.stream()
                .anyMatch(span -> span != null && span.waterGap() && span.length() > maxAllowedLength);
    }
}