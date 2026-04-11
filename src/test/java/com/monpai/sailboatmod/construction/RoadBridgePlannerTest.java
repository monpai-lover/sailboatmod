package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadBridgePlannerTest {
    @Test
    void navigableBridgeProfileKeepsFiveBlockClearanceAboveWater() {
        RoadBridgePlanner.BridgeProfile profile = RoadBridgePlanner.navigableProfileForTest(10, 20, 64);

        assertEquals(69, profile.deckHeight());
    }

    @Test
    void classifiesNoneWhenNoUnsupportedColumns() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0)
                ),
                index -> false,
                index -> 64
        );

        assertEquals(RoadBridgePlanner.BridgeKind.NONE, kind);
    }

    @Test
    void classifiesFlatWhenUnsupportedColumnsAreThreeOrFewer() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0)
                ),
                index -> true,
                index -> 62
        );

        assertEquals(RoadBridgePlanner.BridgeKind.FLAT, kind);
    }

    @Test
    void classifiesFlatWhenTerrainDropIsBelowSixEvenForLongSpan() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0),
                        new BlockPos(3, 70, 0),
                        new BlockPos(4, 70, 0),
                        new BlockPos(5, 70, 0)
                ),
                index -> true,
                index -> 66
        );

        assertEquals(RoadBridgePlanner.BridgeKind.FLAT, kind);
    }

    @Test
    void classifiesLongUnsafeSpanAsArchedBridge() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0),
                        new BlockPos(3, 70, 0),
                        new BlockPos(4, 70, 0),
                        new BlockPos(5, 70, 0)
                ),
                index -> true,
                index -> 62
        );

        assertEquals(RoadBridgePlanner.BridgeKind.ARCHED, kind);
    }
}
