package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftSurfaceServoTest {
    final Object controller = new Object(), world = new Object(), body = new Object();
    final UUID owner = UUID.randomUUID(), bodyId = UUID.randomUUID();
    RailAircraftSurfaceServo servo = new RailAircraftSurfaceServo();
    RailAircraftSurfaceServo.Token claim(double angle) {
        var result = servo.claim(controller, world, body, owner, bodyId, 42, angle, 0, 100, false);
        assertTrue(result.accepted(), result.reason()); return result.token();
    }
    @Test void finiteServoUsesShortestBoundedAngleAndStopsAtItsTarget() {
        var token = claim(359);
        assertTrue(servo.target(controller, world, body, token, 20, 60, 100).success());
        assertEquals(3, servo.step(359), 1e-9);
        assertEquals(0.5, servo.step(19.5), 1e-9);
        assertEquals(0, servo.step(20), 1e-9);
        assertEquals(-3, servo.step(25), 1e-9);
        assertFalse(servo.target(controller, world, body, token, 46, 60, 101).success());
        assertFalse(servo.target(controller, world, body, token, 20, 181, 101).success());
        assertFalse(servo.target(controller, world, body, token, Double.NaN, 60, 101).success());
    }
    @Test void duplicateClaimDoesNotResetTargetButAnotherOwnerCannotStealTheBearing() {
        var token = claim(0);
        servo.target(controller, world, body, token, 15, 40, 100);
        assertEquals(token, servo.claim(controller, world, body, owner, bodyId, 42, 0, 0, 101, true).token());
        assertEquals(15, servo.view(0).targetAngleDegrees(), 1e-9);
        assertEquals("surface_owned", servo.claim(new Object(), world, body, UUID.randomUUID(), bodyId,
                43, 0, 0, 101, false).reason());
        assertEquals("surface_controller_mismatch", servo.claim(new Object(), world, body, owner, bodyId,
                43, 0, 0, 101, false).reason());
        assertEquals("surface_controller_still_loaded", servo.claim(new Object(), world, body, owner, bodyId,
                42, 0, 0, 101, true).reason());
    }
    @Test void staleOrUnloadedOwnerHoldsActualAngleAndNeverFallsBackToMotorRpm() {
        var token = claim(3);
        servo.target(controller, world, body, token, 20, 60, 100);
        servo.beforeTick(103, 7, true);
        assertTrue(servo.locked()); assertEquals(0, servo.step(7), 1e-9);
        assertEquals("surface_command_stale", servo.view(7).reason());
        assertTrue(servo.target(controller, world, body, token, 10, 60, 104).success());
        servo.beforeTick(104, 8, false);
        assertEquals(0, servo.step(8), 1e-9);
        assertFalse(servo.owns(controller, world, body, token));
        var rebound = servo.claim(controller, world, body, owner, bodyId, 42, 8, 0, 105, false);
        assertTrue(rebound.accepted()); assertNotEquals(token, rebound.token());
        assertFalse(servo.target(controller, world, body, token, 15, 60, 105).success());
    }
    @Test void releaseSlewsToNeutralAndKeepsTheLockUntilExplicitClear() {
        var token = claim(12);
        assertTrue(servo.neutral(controller, world, body, token).success());
        assertTrue(servo.returningToNeutral(), "An explicit release may finish neutral return after the motion lease ends");
        servo.beforeTick(999, 12, true);
        assertEquals(-3, servo.step(12), 1e-9);
        assertEquals(0, servo.step(0), 1e-9);
        assertTrue(servo.locked());
        assertFalse(servo.clear(new Object(), world, body, token));
        assertTrue(servo.clear(controller, world, body, token)); assertFalse(servo.locked());
    }
    @Test void serverReloadHoldsSavedActualAngleAndRequiresFreshSameOwnerRebind() {
        var token = claim(0); servo.target(controller, world, body, token, 24, 75, 101);
        CompoundTag tag = new CompoundTag(); servo.write(tag);
        RailAircraftSurfaceServo loaded = new RailAircraftSurfaceServo(); loaded.read(tag, false, 6);
        assertTrue(loaded.locked()); assertEquals(0, loaded.step(6), 1e-9);
        assertFalse(loaded.owns(controller, world, body, token));
        var rebound = loaded.claim(controller, world, body, owner, bodyId, 42, 6, 0, 102, false);
        assertTrue(rebound.accepted()); assertTrue(rebound.token().generation() > token.generation());
        RailAircraftSurfaceServo client = new RailAircraftSurfaceServo(); client.read(tag, true, 6);
        assertEquals(3.75, client.step(6), 1e-9);
        assertEquals(24, client.view(6).targetAngleDegrees(), 1e-9);
    }
    @Test void malformedSavedStateFailsClosedWithAFiniteHold() {
        CompoundTag root = new CompoundTag(), invalid = new CompoundTag();
        invalid.putBoolean("Locked", true); invalid.putDouble("Target", Double.NaN);
        root.put(RailAircraftSurfaceServo.TAG, invalid);
        servo.read(root, false, 9);
        assertTrue(servo.locked()); assertEquals(0, servo.step(9), 1e-9);
        assertFalse(servo.claim(controller, world, body, owner, bodyId, 42, 9, 0, 100, false).accepted());
    }
    @Test void nonzeroNeutralAllowsOnlyFortyFiveDegreesAroundItsShortestArc() {
        var acquired = servo.claim(controller, world, body, owner, bodyId, 42, 270, 90, 100, false);
        assertTrue(acquired.accepted());
        assertTrue(servo.target(controller, world, body, acquired.token(), 110, 90, 100).success());
        assertFalse(servo.target(controller, world, body, acquired.token(), 136, 90, 100).success());
        assertEquals(-4.5, servo.step(270), 1e-9);
        assertTrue(servo.neutral(controller, world, body, acquired.token()).success());
        assertEquals(90, servo.view(270).targetAngleDegrees(), 1e-9);
        RailAircraftSurfaceServo wrapped = new RailAircraftSurfaceServo();
        var other = wrapped.claim(controller, world, body, owner, bodyId, 42, -175, 170, 100, false);
        assertTrue(other.accepted());
        assertTrue(wrapped.target(controller, world, body, other.token(), -170, 60, 100).success());
        assertFalse(wrapped.target(controller, world, body, other.token(), -140, 60, 100).success());
    }
    @Test void rebuiltBearingCannotReuseAnOldSameAddressTokenAndSavedFloorCannotGoBackwards() {
        var old = claim(0);
        RailAircraftSurfaceServo rebuilt = new RailAircraftSurfaceServo();
        var replacement = rebuilt.claim(controller, world, body, owner, bodyId, 42, 0, 0, 101, false);
        assertTrue(replacement.accepted()); assertNotEquals(old, replacement.token());
        assertFalse(rebuilt.target(controller, world, body, old, 10, 60, 101).success());
        CompoundTag saved = new CompoundTag(); rebuilt.write(saved);
        long floor = replacement.token().generation() + 1000;
        saved.getCompound(RailAircraftSurfaceServo.TAG).putLong("Generation", floor);
        RailAircraftSurfaceServo loaded = new RailAircraftSurfaceServo(); loaded.read(saved, false, 0);
        var renewed = loaded.claim(controller, world, body, owner, bodyId, 42, 0, 0, 102, false);
        assertTrue(renewed.accepted()); assertTrue(renewed.token().generation() > floor);
        saved.getCompound(RailAircraftSurfaceServo.TAG).putLong("Generation", Long.MAX_VALUE);
        RailAircraftSurfaceServo exhausted = new RailAircraftSurfaceServo(); exhausted.read(saved, false, 0);
        assertEquals("surface_generation_exhausted", exhausted.claim(controller, world, body, owner, bodyId, 42, 0, 0, 103, false).reason());
    }
}
