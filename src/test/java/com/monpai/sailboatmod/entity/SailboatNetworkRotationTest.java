package com.monpai.sailboatmod.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the production receiver methods in tick order, without a world or sockets. */
class SailboatNetworkRotationTest {
    private static Unsafe unsafe;
    private static Method networkTick;
    private static Method turnLimit;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
        networkTick = SailboatEntity.class.getDeclaredMethod("tickNetworkLerp");
        networkTick.setAccessible(true);
        turnLimit = SailboatEntity.class.getDeclaredMethod("limitTurnRate");
        turnLimit.setAccessible(true);
    }

    @Test
    void occupiedRemoteBoatKeepsTheYawAppliedByNetworkInterpolation() throws Exception {
        TestBoat boat = boat(true, false);
        boat.lerpTo(10, 64, 20, 90, 0, 3);
        networkTick.invoke(boat);
        float interpolated = boat.getYRot();
        assertTrue(interpolated > 0.85F, "exercise a real network turn above the old manual limit");
        turnLimit.invoke(boat);
        assertEquals(interpolated, boat.getYRot(), 1.0E-5,
                "a remote passenger/observer must not re-limit the replicated heading");
    }

    @ParameterizedTest
    @ValueSource(floats = {1.4F, -1.4F})
    void sustainedQuantizedTurnsHaveBoundedLagAndConvergeWhenPacketsStop(float turn) throws Exception {
        TestBoat boat = boat(true, false);
        float finalYaw = 0;
        double maximumError = 0;
        for (int tick = 1; tick <= 240; tick++) {
            // Vanilla movement packets encode heading in a signed byte.
            finalYaw = packedYaw(tick * turn);
            boat.lerpTo(tick * 0.2, 64, tick * 0.3, finalYaw, 0, 3);
            receiverTick(boat);
            maximumError = Math.max(maximumError, Math.abs(Mth.wrapDegrees(finalYaw - boat.getYRot())));
        }
        assertTrue(maximumError < 16, "10-tick smoothing may lag, but the error must not accumulate: " + maximumError);
        // No more heading packets: the final target must still be reached, not discarded.
        for (int tick = 0; tick < 10; tick++) receiverTick(boat);
        assertEquals(0, Mth.wrapDegrees(finalYaw - boat.getYRot()), 1.0E-4);
        assertEquals(48, boat.getX(), 1.0E-8);
        assertEquals(72, boat.getZ(), 1.0E-8);
    }

    @Test
    void positionOnlyPacketPreservesPendingYawAndPitch() throws Exception {
        TestBoat boat = boat(true, false);
        boat.lerpTo(100, 64, 50, 90, 12, 3);
        receiverTick(boat);
        // This is ClientPacketListener.handleMoveEntity's position-only branch.
        boat.lerpTo(110, 65, 60, boat.lerpTargetYRot(), boat.lerpTargetXRot(), 3);
        for (int tick = 0; tick < 10; tick++) receiverTick(boat);
        assertEquals(90, boat.getYRot(), 1.0E-5);
        assertEquals(12, boat.getXRot(), 1.0E-5);
        assertEquals(110, boat.getX(), 1.0E-8);
        assertEquals(65, boat.getY(), 1.0E-8);
        assertEquals(60, boat.getZ(), 1.0E-8);
    }

    @Test
    void rotationOnlyPacketPreservesPendingPosition() throws Exception {
        TestBoat boat = boat(true, false);
        boat.lerpTo(100, 64, 50, 90, 12, 3);
        receiverTick(boat);
        // This is ClientPacketListener.handleMoveEntity's rotation-only branch.
        boat.lerpTo(boat.lerpTargetX(), boat.lerpTargetY(), boat.lerpTargetZ(), 120, 8, 3);
        for (int tick = 0; tick < 10; tick++) receiverTick(boat);
        assertEquals(100, boat.getX(), 1.0E-8);
        assertEquals(64, boat.getY(), 1.0E-8);
        assertEquals(50, boat.getZ(), 1.0E-8);
        assertEquals(120, boat.getYRot(), 1.0E-5);
    }

    @ParameterizedTest
    @ValueSource(floats = {359.8F, -359.8F})
    void renderHeadingCrossesTheFullTurnOnTheShortArc(float previousYaw) throws Exception {
        TestBoat boat = boat(true, false);
        boat.yRotO = previousYaw;
        boat.setYRot(previousYaw > 0 ? 0.2F : -0.2F);
        assertEquals(0, Mth.wrapDegrees(boat.getViewYRot(0.5F)), 1.0E-4,
                "the actual renderer uses getViewYRot; midpoint must not face backwards");
    }

    @Test
    void localDriverPredictionStillIgnoresReceivedPose() throws Exception {
        TestBoat boat = boat(true, true);
        boat.setPos(3, 64, 5);
        boat.setYRot(20);
        boat.lerpTo(100, 70, 50, 90, 12, 3);
        networkTick.invoke(boat);
        assertEquals(3, boat.getX(), 1.0E-8);
        assertEquals(20, boat.getYRot(), 1.0E-5);
        assertEquals(3, boat.lerpTargetX(), 1.0E-8);
        assertEquals(20, boat.lerpTargetYRot(), 1.0E-5);
    }

    @Test
    void becomingTheLocalDriverKeepsTheLastReplicatedHeading() throws Exception {
        TestBoat boat = boat(true, false);
        boat.lerpTo(10, 64, 20, 90, 0, 3);
        for (int tick = 0; tick < 10; tick++) receiverTick(boat);
        assertEquals(90, boat.getYRot(), 1.0E-5);
        boat.local = true;
        boat.setYRot(90.5F);
        turnLimit.invoke(boat);
        assertEquals(90.5F, boat.getYRot(), 1.0E-5,
                "switching seats must not clamp against a stale pre-network heading");
    }

    @Test
    void autopilotStillAppliesItsAuthoritativePoseImmediately() throws Exception {
        TestBoat boat = boat(true, false);
        boat.autopilot = true;
        boat.lerpTo(100, 64, 50, 90, 12, 3);
        receiverTick(boat);
        assertEquals(100, boat.getX(), 1.0E-8);
        assertEquals(90, boat.getYRot(), 1.0E-5);
        assertEquals(100, boat.lerpTargetX(), 1.0E-8);
    }

    @Test
    void serverAndLocalDriverKeepTheirExistingTurnLimit() throws Exception {
        for (TestBoat boat : new TestBoat[]{boat(false, false), boat(true, true)}) {
            boat.setYRot(20);
            turnLimit.invoke(boat);
            assertEquals(0.85F, boat.getYRot(), 1.0E-5);
        }
    }

    private static float packedYaw(float degrees) {
        return (byte) Mth.floor(degrees * 256.0F / 360.0F) * 360.0F / 256.0F;
    }

    private static void receiverTick(TestBoat boat) throws Exception {
        boat.yRotO = boat.getYRot();
        networkTick.invoke(boat);
        turnLimit.invoke(boat);
    }

    private static TestBoat boat(boolean client, boolean local) throws Exception {
        // Only Level.isClientSide is read in these receiver methods. Never access fake world state.
        ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
        Field side = Level.class.getDeclaredField("isClientSide");
        side.setAccessible(true);
        side.setBoolean(level, client);
        TestBoat boat = new TestBoat(level);
        boat.local = local;
        boat.captain = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
        return boat;
    }

    private static final class TestBoat extends SailboatEntity {
        private boolean local;
        private boolean autopilot;
        private LivingEntity captain;

        TestBoat(Level level) { super(EntityType.BOAT, level); }
        @Override public boolean isControlledByLocalInstance() { return local && !autopilot; }
        @Override public boolean isAutopilotActive() { return autopilot; }
        @Override public LivingEntity getControllingPassenger() { return autopilot ? null : captain; }
    }
}
