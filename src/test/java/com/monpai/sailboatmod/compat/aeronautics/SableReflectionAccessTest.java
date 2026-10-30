package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SableReflectionAccessTest {
    private static final Object TEST_LEVEL = new Object();
    private SableReflectionAccess access() throws ReflectiveOperationException {
        return access(true);
    }
    private SableReflectionAccess access(boolean simulated) throws ReflectiveOperationException {
        return SableReflectionAccess.bind(name -> {
            if (!simulated && name.startsWith("dev.simulated_team.")) throw new ClassNotFoundException(name);
            return lookup(name);
        });
    }
    private Class<?> lookup(String name) throws ClassNotFoundException {
        return switch (name) {
            case "dev.ryanhcode.sable.Sable" -> FakeSable.class;
            case "dev.ryanhcode.sable.sublevel.ServerSubLevel" -> FakeBody.class;
            case "dev.ryanhcode.sable.companion.math.Pose3dc" -> FakePose.class;
            case "dev.ryanhcode.sable.companion.math.BoundingBox3dc" -> FakeBounds.class;
            case "dev.ryanhcode.sable.api.physics.mass.MassData" -> FakeMass.class;
            case "dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity" -> FakeThrottle.class;
            case "dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity" -> FakeSteering.class;
            default -> throw new ClassNotFoundException(name);
        };
    }

    @Test
    void airOnlyStackCanReadPoseAndCargoMembershipWithoutSimulatedControls() throws Exception {
        SableReflectionAccess access = access(false);
        FakeBody body = new FakeBody();
        assertTrue(access.snapshot(body, BlockPos.ZERO, Direction.NORTH, "minecraft:overworld").available());
        assertTrue(access.sameLiveStructure(body, body));
        assertFalse(access.sameLiveStructure(body, new FakeBody()));
        assertEquals("optional_mod_unavailable", access.throttle(body, body, new FakeThrottle(), 10).reason());
        assertEquals("optional_mod_unavailable", access.steering(body, body, new FakeSteering(), 10).reason());
    }

    @Test
    void changedNativeActuatorApiDoesNotDisablePhysicalIdentityAndSnapshot() throws Exception {
        SableReflectionAccess access = SableReflectionAccess.bind(name -> name.contains("SteeringWheelBlockEntity") ? Object.class : lookup(name));
        FakeBody body = new FakeBody();
        assertTrue(access.snapshot(body, BlockPos.ZERO, Direction.NORTH, "minecraft:overworld").available());
        assertTrue(access.sameLiveStructure(body, body));
        assertEquals("optional_mod_unavailable", access.steering(body, body, new FakeSteering(), 10).reason());
    }

    @Test
    void resolvesRealWorldPoseFacingVelocityBoundsAndMassWithoutMutatingTheBody() throws Exception {
        FakeBody body = new FakeBody();
        PhysicalVehicleView view = access().snapshot(body, new BlockPos(1000, 65, 2000), Direction.NORTH, "minecraft:overworld");

        assertEquals(body.getUniqueId(), view.physicalId());
        assertEquals("minecraft:overworld", view.dimensionId());
        assertEquals(new Vec3(20.5, 65.5, -30.5), view.worldPosition(), "stored plot coordinates are transformed into world coordinates");
        assertEquals(new Vec3(1, 0, 0), view.worldForward(), "controller facing must rotate with the assembled structure");
        assertEquals(new Vec3(3, 0, -4), view.linearVelocity());
        assertEquals(new AABB(15, 60, -35, 25, 70, -25), view.worldBounds());
        assertEquals(24, view.mass());
        assertTrue(view.available());
        body.latestLinearVelocity.set(0, 0, 0);
        assertEquals(new Vec3(3, 0, -4), view.linearVelocity(), "the view is an immutable snapshot");
    }

    @Test
    void zeroOrInvalidWholeStructureMassDoesNotPermitAutomaticControl() throws Exception {
        FakeBody body = new FakeBody();
        for (double mass : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            body.mass.value = mass;
            assertFalse(access().snapshot(body, BlockPos.ZERO, Direction.NORTH, "minecraft:overworld").available());
        }
    }

    @Test
    void removedOrDifferentStructuresCannotShareCargoOrNativeControlTargets() throws Exception {
        SableReflectionAccess access = access();
        FakeBody controller = new FakeBody();
        FakeBody other = new FakeBody();
        assertTrue(access.sameLiveStructure(controller, controller));
        assertFalse(access.sameLiveStructure(controller, other));
        other.id = controller.id;
        other.level = new Object();
        assertFalse(access.sameLiveStructure(controller, other), "matching UUIDs in another level are not membership");
        controller.removed = true;
        assertFalse(access.sameLiveStructure(controller, controller));
        assertFalse(access.sameLiveStructure(controller, null));
    }

    @Test
    void nativeControlsUseOnlyExplicitSameStructureTargetsAndRejectInvalidSignals() throws Exception {
        SableReflectionAccess access = access();
        FakeBody controller = new FakeBody();
        FakeThrottle throttle = new FakeThrottle();
        FakeSteering steering = new FakeSteering();
        assertTrue(access.throttle(controller, controller, throttle, 12).success());
        assertEquals(12, throttle.signal);
        assertTrue(access.steering(controller, controller, steering, -22.5F).success());
        assertEquals(-22.5F, steering.angle);
        assertFalse(access.throttle(controller, new FakeBody(), throttle, 8).success());
        assertFalse(access.throttle(controller, controller, throttle, -1).success());
        assertFalse(access.throttle(controller, controller, throttle, 16).success());
        assertFalse(access.steering(controller, controller, steering, Float.NaN).success());
        assertFalse(access.steering(controller, controller, new Object(), 10).success());
        assertEquals(12, throttle.signal, "failed bindings do not mutate an actuator");
    }

    @Test
    void manualSteeringAndNativeApiFailuresAreReportedAsFailures() throws Exception {
        SableReflectionAccess access = access();
        FakeBody body = new FakeBody();
        FakeSteering steering = new FakeSteering();
        steering.held = true;
        assertEquals("manual_control", access.steering(body, body, steering, 10).reason());
        FakeThrottle throttle = new FakeThrottle();
        throttle.fail = true;
        assertFalse(access.throttle(body, body, throttle, 10).success());
    }

    @Test
    void unchangedThrottleDoesNotSpamNativeUpdatesAndManualChangesPauseControl() throws Exception {
        SableReflectionAccess access = access();
        FakeBody body = new FakeBody();
        FakeThrottle throttle = new FakeThrottle();
        assertTrue(access.throttle(body, body, throttle, 10).success());
        assertTrue(access.throttle(body, body, throttle, 10).success());
        assertEquals(1, throttle.calls, "setSignal plays a click and synchronizes the lever, so only changes should invoke it");
        throttle.signal = 5;
        assertEquals("manual_control", access.throttle(body, body, throttle, 10).reason());
        assertEquals(5, throttle.signal, "an external lever change is not silently overwritten");
    }

    @Test
    void absentOrChangedOptionalApisFailExplicitly() {
        assertThrows(ClassNotFoundException.class, () -> SableReflectionAccess.bind(name -> { throw new ClassNotFoundException(name); }));
    }

    public static final class FakeSable { public static final FakeHelper HELPER = new FakeHelper(); }
    public static final class FakeHelper { public FakeBody getContaining(BlockEntity entity) { return null; } }
    public static final class FakeBody {
        UUID id = UUID.randomUUID(); Object level = TEST_LEVEL; boolean removed;
        public final Vector3d latestLinearVelocity = new Vector3d(3, 0, -4);
        final FakeMass mass = new FakeMass();
        public UUID getUniqueId() { return id; }
        public Object getLevel() { return level; }
        public boolean isRemoved() { return removed; }
        public FakePose logicalPose() { return new FakePose(); }
        public FakeBounds boundingBox() { return new FakeBounds(); }
        public FakeMass getMassTracker() { return mass; }
    }
    public static final class FakePose {
        public Vec3 transformPosition(Vec3 local) { return new Vec3(local.z - 1980, local.y, 970 - local.x); }
        public Vec3 transformNormal(Vec3 local) { return new Vec3(-local.z, local.y, local.x); }
    }
    public static final class FakeBounds { public AABB toMojang() { return new AABB(15, 60, -35, 25, 70, -25); } }
    public static final class FakeMass { double value = 24; public double getMass() { return value; } }
    public static final class FakeThrottle {
        int signal = -1; boolean fail; int calls;
        public int getState() { return signal; }
        public void setSignal(int signal) { if (fail) throw new IllegalStateException("native control unavailable"); this.signal = signal; calls++; }
    }
    public static final class FakeSteering {
        public boolean held; float angle;
        public void updateTargetAngle(float angle) { this.angle = angle; }
    }
}
