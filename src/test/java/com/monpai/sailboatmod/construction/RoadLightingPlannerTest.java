package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadLightingPlannerTest {

    @Test
    void skipsLampWhenBridgeRangeContainsLampIndex() {
        List<BlockPos> centerPath = straightPath(8);
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(2, 2));

        List<BlockPos> lamps = RoadLightingPlanner.planLampPosts(centerPath, bridgeRanges, null);

        assertTrue(lamps.isEmpty());
    }

    @Test
    void skipsLampWhenProtectedColumnMatchesCandidate() {
        List<BlockPos> centerPath = straightPath(8);
        BlockPos lampCandidate = centerPath.get(2).north(4);
        List<BlockPos> protectedColumns = List.of(lampCandidate);

        List<BlockPos> lamps = RoadLightingPlanner.planLampPosts(centerPath, null, protectedColumns);

        assertTrue(lamps.isEmpty());
    }

    @Test
    void placesLampWhenNoRestrictionsApply() {
        List<BlockPos> centerPath = straightPath(8);
        BlockPos expectedLamp = centerPath.get(2).north(4);

        List<BlockPos> lamps = RoadLightingPlanner.planLampPosts(centerPath, null, null);

        assertEquals(List.of(expectedLamp), lamps);
    }

    @Test
    void navigableBridgeLightingAvoidsCentralBoatLane() {
        List<BlockPos> centerPath = straightPath(8);
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(2, 4));

        List<BlockPos> lights = RoadLightingPlanner.navigableBridgeLightsForTest(centerPath, bridgeRanges);

        assertTrue(lights.stream().noneMatch(pos -> pos.equals(centerPath.get(2).below())));
    }

    private static List<BlockPos> straightPath(int length) {
        List<BlockPos> path = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            path.add(new BlockPos(i * 5, 64, 0));
        }
        return List.copyOf(path);
    }
}
