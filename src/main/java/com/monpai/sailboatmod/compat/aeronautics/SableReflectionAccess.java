package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reflection is confined here so the main mod never links optional Sable classes at startup. */
final class SableReflectionAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(SableReflectionAccess.class);
    private static final AtomicBoolean WARNED_CONTROL = new AtomicBoolean();

    @FunctionalInterface
    interface ClassLookup { Class<?> find(String name) throws ClassNotFoundException; }

    private final Object helper;
    private final Class<?> bodyType;
    private final Class<?> throttleType;
    private final Class<?> steeringType;
    private final Method containing, id, level, removed, pose, bounds, massTracker;
    private final Method transformPosition, transformNormal, toMojangBounds, getMass;
    private final Method setThrottle, getThrottleState, setSteering;
    private final Field velocity, steeringHeld;
    private final Map<Object, ThrottleLease> throttleLeases = new WeakHashMap<>();

    private record ThrottleLease(int command, int observedState) { }

    static SableReflectionAccess bind(ClassLookup lookup) throws ReflectiveOperationException {
        return new SableReflectionAccess(lookup);
    }

    private SableReflectionAccess(ClassLookup lookup) throws ReflectiveOperationException {
        Class<?> sable = lookup.find("dev.ryanhcode.sable.Sable");
        bodyType = lookup.find("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Class<?> poseType = lookup.find("dev.ryanhcode.sable.companion.math.Pose3dc");
        Class<?> boundsType = lookup.find("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
        Class<?> massType = lookup.find("dev.ryanhcode.sable.api.physics.mass.MassData");
        throttleType = lookup.find("dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity");
        steeringType = lookup.find("dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity");
        helper = sable.getField("HELPER").get(null);
        if (helper == null) throw new ReflectiveOperationException("Sable.HELPER is unavailable");
        containing = helper.getClass().getMethod("getContaining", BlockEntity.class);
        id = bodyType.getMethod("getUniqueId");
        level = bodyType.getMethod("getLevel");
        removed = bodyType.getMethod("isRemoved");
        pose = bodyType.getMethod("logicalPose");
        bounds = bodyType.getMethod("boundingBox");
        massTracker = bodyType.getMethod("getMassTracker");
        velocity = bodyType.getField("latestLinearVelocity");
        transformPosition = poseType.getMethod("transformPosition", Vec3.class);
        transformNormal = poseType.getMethod("transformNormal", Vec3.class);
        toMojangBounds = boundsType.getMethod("toMojang");
        getMass = massType.getMethod("getMass");
        setThrottle = throttleType.getMethod("setSignal", int.class);
        getThrottleState = throttleType.getMethod("getState");
        setSteering = steeringType.getMethod("updateTargetAngle", float.class);
        steeringHeld = steeringType.getField("held");
    }

    Object containing(BlockEntity entity) throws ReflectiveOperationException {
        Object body = containing.invoke(helper, entity);
        return isLive(body) ? body : null;
    }

    Object levelOf(Object body) throws ReflectiveOperationException {
        return level.invoke(body);
    }

    PhysicalVehicleView snapshot(Object body, BlockPos controllerPosition, Direction facing, String dimensionId) throws ReflectiveOperationException {
        Object currentPose = pose.invoke(body);
        Vec3 worldPosition = (Vec3) transformPosition.invoke(currentPose, Vec3.atCenterOf(controllerPosition));
        Vec3 forward = (Vec3) transformNormal.invoke(currentPose, Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 worldForward = finite(forward) && forward.lengthSqr() > 1.0E-12 ? forward.normalize() : Vec3.ZERO;
        Vector3dc currentVelocity = (Vector3dc) velocity.get(body);
        Vec3 linearVelocity = new Vec3(currentVelocity.x(), currentVelocity.y(), currentVelocity.z());
        AABB worldBounds = (AABB) toMojangBounds.invoke(bounds.invoke(body));
        double mass = mass(body);
        UUID physicalId = (UUID) id.invoke(body);
        boolean available = physicalId != null && isLive(body) && positiveMass(mass)
                && finite(worldPosition) && finite(linearVelocity) && worldForward.lengthSqr() > 0
                && finite(worldBounds);
        return new PhysicalVehicleView(physicalId, dimensionId, worldPosition, worldForward,
                linearVelocity, worldBounds, mass, available);
    }

    boolean sameLiveStructure(Object controllerBody, Object targetBody) throws ReflectiveOperationException {
        if (!isLive(controllerBody) || !isLive(targetBody)) return false;
        Object ownerLevel = level.invoke(controllerBody);
        Object ownerId = id.invoke(controllerBody);
        return ownerLevel != null && ownerLevel == level.invoke(targetBody)
                && ownerId != null && ownerId.equals(id.invoke(targetBody));
    }

    NativeControlResult throttle(Object controllerBody, Object targetBody, Object target, int signal) {
        if (signal < 0 || signal > 15) return NativeControlResult.failure("invalid_signal");
        try {
            String invalid = bindingFailure(controllerBody, targetBody, target, throttleType);
            if (invalid != null) return NativeControlResult.failure(invalid);
            int observed = ((Number) getThrottleState.invoke(target)).intValue();
            ThrottleLease previous = throttleLeases.get(target);
            if (previous != null && observed != previous.observedState()) {
                throttleLeases.remove(target);
                return NativeControlResult.failure("manual_control");
            }
            if (previous == null || previous.command() != signal) {
                setThrottle.invoke(target, signal);
                throttleLeases.put(target, new ThrottleLease(signal,
                        ((Number) getThrottleState.invoke(target)).intValue()));
            }
            return NativeControlResult.applied();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return controlFailure(failure);
        }
    }

    NativeControlResult steering(Object controllerBody, Object targetBody, Object target, float angleDegrees) {
        if (!Float.isFinite(angleDegrees)) return NativeControlResult.failure("invalid_angle");
        try {
            String invalid = bindingFailure(controllerBody, targetBody, target, steeringType);
            if (invalid != null) return NativeControlResult.failure(invalid);
            if (steeringHeld.getBoolean(target)) return NativeControlResult.failure("manual_control");
            setSteering.invoke(target, angleDegrees);
            return NativeControlResult.applied();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return controlFailure(failure);
        }
    }

    private String bindingFailure(Object controllerBody, Object targetBody, Object target, Class<?> expected)
            throws ReflectiveOperationException {
        if (!sameLiveStructure(controllerBody, targetBody)) return "target_not_in_same_structure";
        if (!positiveMass(mass(controllerBody))) return "invalid_structure_mass";
        return expected.isInstance(target) ? null : "wrong_native_control";
    }

    private boolean isLive(Object body) throws ReflectiveOperationException {
        return bodyType.isInstance(body) && !((Boolean) removed.invoke(body));
    }

    private double mass(Object body) throws ReflectiveOperationException {
        return ((Number) getMass.invoke(massTracker.invoke(body))).doubleValue();
    }

    private static boolean positiveMass(double mass) { return Double.isFinite(mass) && mass > 0; }
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
    private static boolean finite(AABB bounds) {
        return bounds != null && Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ);
    }

    private static NativeControlResult controlFailure(Exception failure) {
        if (WARNED_CONTROL.compareAndSet(false, true)) {
            LOGGER.warn("[PhysicalTransport] Native Aeronautics actuator failed; automatic control must pause: {}", failure.toString());
        }
        return NativeControlResult.failure("native_control_failed");
    }
}
