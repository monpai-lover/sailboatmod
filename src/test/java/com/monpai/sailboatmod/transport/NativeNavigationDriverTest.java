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

    @Test void finalLandApproachDoesNotParkOutsideTheActualDestinationZone() {
        var terminal = new Vec3(56.5, 5.5, 44.5);
        var parking = new Vec3(51.5, 5.05, 39.5);
        // Actual fix67 outbound sample: target index 44, four items loaded and B's west edge at x=50.5.
        var position = new Vec3(47.2889417659245, 7.4816384212198335, 40.563185561446254);
        double radius = NativeNavigationDriver.terminalArrivalRadius(4.54, parking.subtract(terminal), -6, 6, -7, -2);
        var approach = driveFinal(LogisticsCarrier.Kind.LAND, position, parking, radius);
        assertFalse(approach.arrived());
        assertTrue(approach.throttle() > 0);
        assertEquals(1, radius);
        var stopped = driveFinal(LogisticsCarrier.Kind.LAND, new Vec3(50.75, 7.48, 39.5), parking, radius);
        assertTrue(stopped.arrived());
        assertEquals(15, stopped.brake());
    }

    @Test void finalWaterApproachDoesNotParkOutsideTheActualHarborZone() {
        var terminal = new Vec3(56.5, 1.5, 15.5);
        var berth = new Vec3(56.5, 0, 7.5);
        double radius = NativeNavigationDriver.terminalArrivalRadius(5.073, berth.subtract(terminal), -6, 6, -11, -6);
        var approach = driveFinal(LogisticsCarrier.Kind.WATER, new Vec3(54.5, 0.25, 4.3), berth, radius);
        assertFalse(approach.arrived());
        assertTrue(approach.throttle() > 0);
        assertEquals(2, radius);
    }

    @Test void exactTerminalBoundaryIsValidButAnOutsideWaypointIsRejected() {
        var terminal = new Vec3(56.5, 5.5, 44.5);
        var boundary = new Vec3(50.5, 5.05, 39.5);
        double radius = NativeNavigationDriver.terminalArrivalRadius(4.54, boundary.subtract(terminal), -6, 6, -7, -2);
        assertEquals(0, radius);
        var stillOutside = driveFinal(LogisticsCarrier.Kind.LAND, new Vec3(50.49, 7.48, 39.5), boundary, radius);
        assertFalse(stillOutside.arrived());
        assertTrue(stillOutside.throttle() > 0);
        assertTrue(driveFinal(LogisticsCarrier.Kind.LAND, new Vec3(50.5, 7.48, 39.5), boundary, radius).arrived());
        assertTrue(NativeNavigationDriver.terminalArrivalRadius(4.54, new Vec3(50.49, 5.05, 39.5).subtract(terminal), -6, 6, -7, -2) < 0);
    }

    @Test void finalTerminalBrakesOnZoneEntryEvenWhenTheBoundaryWaypointIsNotExact() {
        var boundary = new Vec3(50.5, 5.05, 39.5);
        var inside = new Vec3(50.500001, 7.48, 39.5);
        var outside = new Vec3(50.499999, 7.48, 39.5);
        var movingInside = terminalInput(inside, boundary, new Vec3(0.4, 0, 0), 0, true);
        var braking = NativeNavigationDriver.drive(movingInside, true);
        assertEquals(0, braking.throttle());
        assertEquals(15, braking.brake());
        assertEquals(0, braking.forwardGear());
        assertEquals(0, braking.reverseGear());
        assertFalse(braking.arrived());
        assertEquals("braking", braking.reason());

        var slowInside = terminalInput(inside, boundary, new Vec3(0.25, 0, 0), 0, true);
        assertTrue(NativeNavigationDriver.drive(slowInside, true).arrived());

        var movingOutside = terminalInput(outside, boundary, new Vec3(0.4, 0, 0), 0, true);
        var approaching = NativeNavigationDriver.drive(movingOutside, false);
        assertFalse(approaching.arrived());
        assertTrue(approaching.throttle() > 0);
    }

    @Test void finalTerminalCannotBypassTerrainReadinessOrAnInvalidWaypoint() {
        var position = new Vec3(50.500001, 7.48, 39.5);
        var boundary = new Vec3(50.5, 5.05, 39.5);
        var unknown = NativeNavigationDriver.drive(terminalInput(position, boundary, Vec3.ZERO, 0, false), true);
        assertTrue(unknown.waiting());
        assertFalse(unknown.arrived());
        var invalid = NativeNavigationDriver.drive(terminalInput(position, boundary, Vec3.ZERO, -0.1, true), true);
        assertTrue(invalid.waiting());
        assertFalse(invalid.arrived());
    }

    private static NativeNavigationDriver.Input terminalInput(Vec3 position, Vec3 target, Vec3 velocity,
                                                                double radius, boolean terrainReady) {
        return new NativeNavigationDriver.Input(LogisticsCarrier.Kind.LAND, position, new Vec3(1, 0, 0),
                velocity, target, 1.4, radius, terrainReady, false, true);
    }

    private static NativeNavigationDriver.Command driveFinal(LogisticsCarrier.Kind kind, Vec3 position, Vec3 target, double radius) {
        return NativeNavigationDriver.drive(new NativeNavigationDriver.Input(kind, position, new Vec3(1, 0, 0),
                Vec3.ZERO, target, 1.4, radius, true, false, true));
    }

    @Test void intermediateWaypointKeepsPowerWhilePassingItsArrivalCircle() {
        for (var kind : new LogisticsCarrier.Kind[]{LogisticsCarrier.Kind.LAND, LogisticsCarrier.Kind.WATER}) {
            var input = input(kind, new Vec3(0, 0, -1), new Vec3(0, 0, -1), true, false, true);
            var passing = NativeNavigationDriver.drive(input, false, false);
            assertTrue(passing.throttle() > 0);
            assertEquals(0, passing.brake());
            assertFalse(passing.arrived());
            assertEquals(15, passing.forwardGear());
            assertTrue(passing.throttle() >= 10, "Nearby intermediate point must not be treated as final approach");
        }
    }

    @Test void passThroughPreservesReverseGearAndTerrainAndManualGates() {
        var reverse = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.LAND, new Vec3(0, 0, 2),
                Vec3.ZERO, true, false, true), false, false);
        assertEquals(15, reverse.reverseGear());
        assertEquals(0, reverse.forwardGear());
        assertEquals(0, reverse.brake());
        assertTrue(reverse.throttle() > 0);
        var waiting = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, Vec3.ZERO,
                Vec3.ZERO, false, false, false), false, false);
        assertEquals(15, waiting.brake());
        assertTrue(waiting.waiting());
        var manual = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.WATER, Vec3.ZERO,
                Vec3.ZERO, true, true, false), false, false);
        assertFalse(manual.engaged());
        assertEquals(0, manual.brake());
        assertEquals(0, manual.throttle());
    }

    @Test void explicitFinalTargetStillBrakesAndRequiresActualStop() {
        var input = input(LogisticsCarrier.Kind.LAND, new Vec3(0, 0, -1), new Vec3(0, 0, -1), true, false, true);
        var finalApproach = NativeNavigationDriver.drive(input, false, true);
        assertEquals(15, finalApproach.brake());
        assertEquals(0, finalApproach.throttle());
        assertFalse(finalApproach.arrived());
        var zoneEntry = NativeNavigationDriver.drive(input(LogisticsCarrier.Kind.LAND, new Vec3(0, 0, -100),
                Vec3.ZERO, true, false, true), true, true);
        assertTrue(zoneEntry.arrived());
    }

    @Test void invertedTransmissionCanStopAtFifteenWithoutChangingDriverUnits() {
        assertEquals(15, NativeNavigationDriver.transmissionSignal(0, true));
        assertEquals(0, NativeNavigationDriver.transmissionSignal(15, true));
        assertEquals(11, NativeNavigationDriver.transmissionSignal(11, false));
        assertEquals(0, NativeNavigationDriver.transmissionSignal(-10, false));
        assertEquals(0, NativeNavigationDriver.transmissionSignal(99, true));
    }
}
