package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RoadPlannerOverlayStyleTest {
    @Test
    void bridgeLineAndBridgeNodeUseDifferentColors() {
        assertNotEquals(RoadPlannerOverlayStyle.bridgeLineColor(), RoadPlannerOverlayStyle.bridgeNodeColor());
        assertNotEquals(RoadPlannerOverlayStyle.roadLineColor(), RoadPlannerOverlayStyle.bridgeLineColor());
        assertNotEquals(RoadPlannerOverlayStyle.tunnelLineColor(), RoadPlannerOverlayStyle.bridgeLineColor());
    }
}
