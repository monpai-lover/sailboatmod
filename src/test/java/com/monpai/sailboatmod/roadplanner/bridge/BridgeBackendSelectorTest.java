package com.monpai.sailboatmod.roadplanner.bridge;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BridgeBackendSelectorTest {
    @Test
    void selectsPierBackendOnlyForLongSpansOrCanyons() {
        BridgeBackendSelector selector = new BridgeBackendSelector();

        assertEquals(BridgeBackend.ROADWEAVER_SIMPLE, selector.select(RoadToolType.ROAD, 8, 2, false));
        assertEquals(BridgeBackend.ROADWEAVER_SIMPLE, selector.select(RoadToolType.ROAD, 8, 16, false));
        assertEquals(BridgeBackend.ROADWEAVER_SIMPLE, selector.select(RoadToolType.BRIDGE, 4, 1, false));
        assertEquals(BridgeBackend.ROADWEAVER_SIMPLE, selector.select(RoadToolType.BRIDGE, 32, 16, false));
        assertEquals(BridgeBackend.PIER_LARGE_BRIDGE, selector.select(RoadToolType.ROAD, 40, 2, false));
        assertEquals(BridgeBackend.PIER_LARGE_BRIDGE, selector.select(RoadToolType.ROAD, 8, 16, true));
        assertEquals(BridgeBackend.PIER_LARGE_BRIDGE, selector.select(RoadToolType.BRIDGE, 33, 1, false));
    }
}
