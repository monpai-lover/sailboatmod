package com.monpai.sailboatmod.transport;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeNavigationDriverTest {
    private static NativeNavigationDriver.Input input(LogisticsCarrier.Kind kind, Vec3 target, Vec3 velocity,
                                                      boolean terrain, boolean manual, boolean reverse) {
        return new NativeNavigationDriver.Input(kind, Vec3.ZERO, new Vec3(0, 0, -1), velocity,
                target, 4, 3, terrain, manual, reverse);
    }

    @Test void clearForwardRouteUsesNativeForwardPower() {
        var c = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(0, 0, -100), Vec3.ZERO, true, false, false));
        assertTrue(c.throttle() > 0); assertEquals(15, c.forwardGear()); assertEquals(0, c.brake());
        assertEquals(0, c.left()); assertEquals(0, c.right()); assertTrue(c.engaged());
    }

    @Test void leftAndRightAreRelativeToPhysicalForward() {
        var right = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(100, 0, 0), Vec3.ZERO, true, false, false));
        var left = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(-100, 0, 0), Vec3.ZERO, true, false, false));
        assertTrue(right.right() > 0); assertEquals(0, right.left());
        assertTrue(left.left() > 0); assertEquals(0, left.right());
    }

    @Test void landVehicleBrakesBeforeSharpTurn() {
        var c = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.LAND, new Vec3(30, 0, 0), new Vec3(0, 0, -3), true, false, false));
        assertTrue(c.brake() > 0); assertTrue(c.throttle() <= 3); assertFalse(c.arrived());
    }

    @Test void nearTargetIsNotArrivalUntilActuallyStopped() {
        var moving = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(1, 0, 0), new Vec3(0, 0, -2), true, false, false));
        assertFalse(moving.arrived()); assertEquals(15, moving.brake());
        var stopped = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(1, 0, 0), Vec3.ZERO, true, false, false));
        assertTrue(stopped.arrived()); assertEquals(0, stopped.throttle());
    }

    @Test void unknownTerrainBrakesAndDoesNotCompleteEvenAtTarget() {
        var c = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.LAND, Vec3.ZERO, Vec3.ZERO, false, false, false));
        assertTrue(c.waiting()); assertFalse(c.arrived()); assertEquals(15, c.brake()); assertEquals(0, c.throttle());
    }

    @Test void manualOverrideReleasesAllAutomaticOutputs() {
        var c = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(100, 0, 0), Vec3.ZERO, true, true, false));
        assertFalse(c.engaged()); assertEquals(0, c.throttle()); assertEquals(0, c.left()); assertEquals(0, c.right());
        assertEquals(0, c.brake()); assertEquals(0, c.forwardGear()); assertEquals(0, c.reverseGear());
    }

    @Test void reverseUsesActualGearAndInvertsSteering() {
        var c = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.LAND, new Vec3(10, 0, 80), Vec3.ZERO, true, false, true));
        assertEquals(15, c.reverseGear()); assertEquals(0, c.forwardGear()); assertTrue(c.throttle() > 0);
        assertTrue(c.throttle() <= 4); assertTrue(c.right() > 0); assertEquals(0, c.left());
    }

    @Test void reverseSteeringMovesTheRearTowardEitherOffAxisDestination() {
        for (int side : new int[]{-1, 1}) {
            Vec3 target = new Vec3(side * 20, 0, 40);
            var command = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.LAND, target, Vec3.ZERO, true, false, true));
            double steering = Math.toRadians((command.right() - command.left()) * 2.0);
            // One reverse bicycle-model step: negative velocity reverses the yaw response.
            double yaw = -2.0 / 2.0 * Math.tan(steering);
            Vec3 moved = new Vec3(-2 * Math.sin(yaw), 0, 2 * Math.cos(yaw));
            assertTrue(side * moved.x > 0, "rear must approach the chosen side");
            assertTrue(moved.distanceToSqr(target) < new Vec3(0, 0, 2).distanceToSqr(target));
        }
    }

    @Test void overspeedCutsPowerAndAppliesRealBraking() {
        var c = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, new Vec3(0, 0, -100), new Vec3(0, 0, -8), true, false, false));
        assertEquals(0, c.throttle()); assertTrue(c.brake() > 0); assertFalse(c.arrived());
    }

    @Test void invalidPoseNeverProducesDrivingPower() {
        var bad = new NativeNavigationDriver.Input(LogisticsCarrier.Kind.WATER, Vec3.ZERO, Vec3.ZERO,
                Vec3.ZERO, new Vec3(0, 0, -100), 4, 3, true, false, false);
        var c = NativeNavigationDriver.drive(bad);
        assertEquals(0, c.throttle()); assertEquals(15, c.brake()); assertTrue(c.waiting());
    }

    @Test void invertedTransmissionCanStopAtFifteenWithoutChangingDriverUnits() {
        assertEquals(15, NativeNavigationDriver.transmissionSignal(0, true));
        assertEquals(0, NativeNavigationDriver.transmissionSignal(15, true));
        assertEquals(11, NativeNavigationDriver.transmissionSignal(11, false));
        assertEquals(0, NativeNavigationDriver.transmissionSignal(-10, false));
        assertEquals(0, NativeNavigationDriver.transmissionSignal(99, true));
    }
}
