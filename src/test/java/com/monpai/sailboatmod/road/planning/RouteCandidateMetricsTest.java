package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.model.BridgeGapKind;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BridgeSpanKind;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCandidateMetricsTest {
    @Test
    void bridgeDominanceRequiresMeaningfulBridgeCoverage() {
        List<BlockPos> path = IntStream.range(0, 100)
                .mapToObj(i -> new BlockPos(i, 64, 0))
                .toList();
        BridgeSpan tinyBridge = new BridgeSpan(10, 12, 63, 58,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);
        BridgeSpan longBridge = new BridgeSpan(10, 70, 63, 42,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);

        assertFalse(RouteCandidateMetrics.from(path, List.of(tinyBridge)).bridgeDominant());
        assertTrue(RouteCandidateMetrics.from(path, List.of(longBridge)).bridgeDominant());
    }

    @Test
    void detourEligibilityIgnoresRegularLandRavineWhenFilteringWaterLength() {
        BridgeSpan landRavine = new BridgeSpan(1, 20, 0, 55,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.LAND_RAVINE_GAP);

        assertFalse(RouteCandidateMetrics.containsLongWaterBridge(List.of(landRavine), 8));
    }
}