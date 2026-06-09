package com.monpai.sailboatmod.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostStationScreenDataTest {
    @Test
    void selectedDestinationClampsWhenReachableTownListShrinks() {
        int selected = PostStationBlockEntity.clampSelectedIndexForTest(5, 2);

        assertEquals(1, selected);
    }

    @Test
    void autoReturnDefaultsOnForNewDispatchUi() {
        assertTrue(PostStationBlockEntity.defaultAutoReturnOnDispatchForTest());
    }

    @Test
    void postStationDispatchRequiresRoadRouteInsteadOfTerrainFallback() {
        assertFalse(PostStationBlockEntity.allowTerrainFallbackForDispatchForTest());
    }

    @Test
    void postStationDispatchRejectsVehiclesThatAreBusyLoadedManualOrUnauthorized() {
        assertTrue(PostStationBlockEntity.isVehicleEligibleForPostStationDispatchForTest(
                true, true, false, false, false, true));

        assertFalse(PostStationBlockEntity.isVehicleEligibleForPostStationDispatchForTest(
                true, true, true, false, false, true));
        assertFalse(PostStationBlockEntity.isVehicleEligibleForPostStationDispatchForTest(
                true, true, false, true, false, true));
        assertFalse(PostStationBlockEntity.isVehicleEligibleForPostStationDispatchForTest(
                true, true, false, false, true, true));
        assertFalse(PostStationBlockEntity.isVehicleEligibleForPostStationDispatchForTest(
                true, true, false, false, false, false));
    }

    @Test
    void selectedVisibleVehicleMustBeDispatchableInsteadOfRemappingToNextDispatchableVehicle() {
        assertFalse(PostStationBlockEntity.selectedVisibleVehicleCanDispatchForTest(
                java.util.List.of(false, true),
                0
        ));
        assertTrue(PostStationBlockEntity.selectedVisibleVehicleCanDispatchForTest(
                java.util.List.of(false, true),
                1
        ));
    }

    @Test
    void postStationMarketDispatchUsesSameManualControlEligibility() {
        assertFalse(PostStationBlockEntity.isVehicleEligibleForPostStationMarketDispatchForTest(
                true, true, false, false, true, true));
    }

    @Test
    void arrivalParkingPointIsStableInsideZoneAwayFromStationAndPrefersRoadSurface() {
        BlockPos station = new BlockPos(10, 64, 10);
        BlockPos source = new BlockPos(-20, 64, 10);
        List<PostStationBlockEntity.ArrivalParkingCandidate> candidates = List.of(
                new PostStationBlockEntity.ArrivalParkingCandidate(new BlockPos(11, 64, 10), true, true),
                new PostStationBlockEntity.ArrivalParkingCandidate(new BlockPos(12, 64, 13), false, true),
                new PostStationBlockEntity.ArrivalParkingCandidate(new BlockPos(7, 64, 8), true, true),
                new PostStationBlockEntity.ArrivalParkingCandidate(new BlockPos(14, 64, 10), true, true)
        );

        BlockPos first = PostStationBlockEntity.selectArrivalParkingGround(
                station, -4, 4, -4, 4, source, candidates);
        BlockPos second = PostStationBlockEntity.selectArrivalParkingGround(
                station, -4, 4, -4, 4, source, candidates);

        assertEquals(first, second);
        assertNotEquals(new BlockPos(11, 64, 10), first);
        assertTrue(first.equals(new BlockPos(7, 64, 8)) || first.equals(new BlockPos(14, 64, 10)));
        assertTrue(PostStationBlockEntity.isInsidePostStationZone(station, Vec3.atCenterOf(first)));
    }

    @Test
    void arrivalParkingPointFallsBackToDriveableGroundWhenNoRoadSurfaceExists() {
        BlockPos station = new BlockPos(10, 64, 10);
        BlockPos source = new BlockPos(-20, 64, 10);
        BlockPos ground = new BlockPos(12, 64, 13);

        BlockPos selected = PostStationBlockEntity.selectArrivalParkingGround(
                station,
                -4,
                4,
                -4,
                4,
                source,
                List.of(new PostStationBlockEntity.ArrivalParkingCandidate(ground, false, true))
        );

        assertEquals(ground, selected);
    }
}
