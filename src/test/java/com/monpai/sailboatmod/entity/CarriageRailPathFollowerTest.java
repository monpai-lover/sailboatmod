package com.monpai.sailboatmod.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageRailPathFollowerTest {
    @Test
    void railStepSnapsSidewaysDriftBackOntoRoadCenterline() {
        List<Vec3> route = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(1.5D, 65.05D, 0.5D),
                new Vec3(2.5D, 65.05D, 0.5D)
        );

        CarriageRailPathFollower.StepResult result = CarriageRailPathFollower.step(
                route,
                new Vec3(0.5D, 65.05D, 1.25D),
                1,
                0.4D
        );

        assertTrue(result.active());
        assertEquals(1, result.targetIndex());
        assertEquals(0.9D, result.position().x, 1.0E-6D);
        assertEquals(65.05D, result.position().y, 1.0E-6D);
        assertEquals(0.5D, result.position().z, 1.0E-6D);
        assertEquals(-90.0F, result.yaw(), 1.0E-4F);
    }

    @Test
    void railStepAdvancesAcrossDenseRoadWaypointsWithoutSkippingThePath() {
        List<Vec3> route = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(1.5D, 65.05D, 0.5D),
                new Vec3(2.5D, 65.05D, 0.5D)
        );

        CarriageRailPathFollower.StepResult result = CarriageRailPathFollower.step(
                route,
                new Vec3(1.45D, 65.05D, 0.5D),
                1,
                0.2D
        );

        assertTrue(result.active());
        assertEquals(2, result.targetIndex());
        assertEquals(1.65D, result.position().x, 1.0E-6D);
        assertEquals(0.5D, result.position().z, 1.0E-6D);
    }

    @Test
    void railStepUsesCurrentSegmentTangentYawWhenCrossingACorner() {
        List<Vec3> route = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(1.5D, 65.05D, 0.5D),
                new Vec3(1.5D, 65.05D, 1.5D)
        );

        CarriageRailPathFollower.StepResult result = CarriageRailPathFollower.step(
                route,
                new Vec3(1.4D, 65.05D, 0.5D),
                1,
                0.4D
        );

        assertTrue(result.active());
        assertEquals(2, result.targetIndex());
        assertEquals(1.5D, result.position().x, 1.0E-6D);
        assertEquals(0.8D, result.position().z, 1.0E-6D);
        assertEquals(0.0F, result.yaw(), 1.0E-4F);
    }

    @Test
    void railStepStopsWhenCarriageIsTooFarFromRail() {
        List<Vec3> route = List.of(
                new Vec3(0.5D, 65.05D, 0.5D),
                new Vec3(1.5D, 65.05D, 0.5D)
        );

        CarriageRailPathFollower.StepResult result = CarriageRailPathFollower.step(
                route,
                new Vec3(0.5D, 65.05D, 8.5D),
                1,
                0.4D
        );

        assertFalse(result.active());
    }
}
