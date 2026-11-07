package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import com.monpai.sailboatmod.transport.physical.NativeGroundObservation;
import com.monpai.sailboatmod.transport.physical.GroundSurfaceObservation;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
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
    private final Method containing, passengerTracking, id, level, removed, pose, lastPose, bounds, massTracker;
    private final Method transformPosition, transformNormal, toMojangBounds, getMass;
    private final Field angularVelocity;
    private final OffroadWheelReflectionAccess wheels;
    private final GroundSurfaceReflectionAccess groundSurfaces;
    private final NativeActuators actuators;
    private final SablePlotMembers plotMembers;
    private final Map<Object, ThrottleLease> throttleLeases = new WeakHashMap<>();

    private record ThrottleLease(int command, int observedState) { }
    private record NativeActuators(Class<?> throttleType, Class<?> steeringType, Method setThrottle,
                                   Method getThrottleState, Method setSteering, Field steeringHeld) {
        static NativeActuators bindIfAvailable(ClassLookup lookup) {
            try {
                Class<?> throttle = lookup.find("dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity");
                Class<?> steering = lookup.find("dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity");
                return new NativeActuators(throttle, steering, throttle.getMethod("setSignal", int.class),
                        throttle.getMethod("getState"), steering.getMethod("updateTargetAngle", float.class), steering.getField("held"));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                // Flight Control aircraft need Sable membership and pose, not Simulated levers.
                return null;
            }
        }
    }

    static SableReflectionAccess bind(ClassLookup lookup) throws ReflectiveOperationException {
        return new SableReflectionAccess(lookup);
    }

    private SableReflectionAccess(ClassLookup lookup) throws ReflectiveOperationException {
        Class<?> sable = lookup.find("dev.ryanhcode.sable.Sable");
        bodyType = lookup.find("dev.ryanhcode.sable.sublevel.ServerSubLevel");
        Class<?> poseType = lookup.find("dev.ryanhcode.sable.companion.math.Pose3dc");
        Class<?> boundsType = lookup.find("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
        Class<?> massType = lookup.find("dev.ryanhcode.sable.api.physics.mass.MassData");
        helper = sable.getField("HELPER").get(null);
        if (helper == null) throw new ReflectiveOperationException("Sable.HELPER is unavailable");
        containing = helper.getClass().getMethod("getContaining", BlockEntity.class);
        Method tracking;
        try { tracking = helper.getClass().getMethod("getTrackingOrVehicleSubLevel", Entity.class); }
        catch (NoSuchMethodException unavailable) { tracking = null; }
        passengerTracking = tracking;
        id = bodyType.getMethod("getUniqueId");
        level = bodyType.getMethod("getLevel");
        removed = bodyType.getMethod("isRemoved");
        pose = bodyType.getMethod("logicalPose");
        lastPose = bodyType.getMethod("lastPose");
        bounds = bodyType.getMethod("boundingBox");
        massTracker = bodyType.getMethod("getMassTracker");
        Field angular;
        try { angular = bodyType.getField("latestAngularVelocity"); } catch (NoSuchFieldException unavailable) { angular = null; }
        angularVelocity = angular;
        wheels = OffroadWheelReflectionAccess.bindIfAvailable(lookup);
        groundSurfaces = GroundSurfaceReflectionAccess.bindIfAvailable(lookup);
        transformPosition = poseType.getMethod("transformPosition", Vec3.class);
        transformNormal = poseType.getMethod("transformNormal", Vec3.class);
        toMojangBounds = boundsType.getMethod("toMojang");
        getMass = massType.getMethod("getMass");
        actuators = NativeActuators.bindIfAvailable(lookup);
        plotMembers = SablePlotMembers.bindIfAvailable(lookup, bodyType);
    }

    Object containing(BlockEntity entity) throws ReflectiveOperationException {
        Object body = containing.invoke(helper, entity);
        return isLive(body) ? body : null;
    }

    Object levelOf(Object body) throws ReflectiveOperationException {
        return level.invoke(body);
    }

    Optional<AeronauticsVehicleBridge.SetupEquipment> setupEquipment(Object body, BlockEntity controller, UUID expectedId)
            throws ReflectiveOperationException {
        if (plotMembers == null || body == null || body.getClass() != bodyType || !isLive(body)
                || !expectedId.equals(id.invoke(body))
                || !(controller.getLevel() instanceof ServerLevel world) || levelOf(body) != world) return Optional.empty();
        var loaded = plotMembers.read(body, world);
        if (loaded.isEmpty() || loaded.orElseThrow().stream().noneMatch(entity -> entity == controller)) return Optional.empty();
        List<BlockPos> throttles = new ArrayList<>();
        List<BlockPos> steering = new ArrayList<>();
        List<BlockPos> flightTerminals = new ArrayList<>();
        for (BlockEntity entity : loaded.orElseThrow()) {
            if (containing(entity) != body) return Optional.empty();
            Class<?> type = entity.getClass();
            if (actuators != null && type == actuators.throttleType()) throttles.add(entity.getBlockPos().immutable());
            if (actuators != null && type == actuators.steeringType()) steering.add(entity.getBlockPos().immutable());
            if ("ace.flight.block.NavigationTerminalBlockEntity".equals(type.getName()))
                flightTerminals.add(entity.getBlockPos().immutable());
        }
        if (containing(controller) != body || !expectedId.equals(id.invoke(body))) return Optional.empty();
        return Optional.of(new AeronauticsVehicleBridge.SetupEquipment(
                List.copyOf(throttles), List.copyOf(steering), List.copyOf(flightTerminals)));
    }

    UUID passengerPhysicalId(Entity passenger) throws ReflectiveOperationException {
        if (passengerTracking == null || passenger == null) return null;
        Object body = passengerTracking.invoke(helper, passenger);
        if (!isLive(body) || level.invoke(body) != passenger.level()) return null;
        AABB worldBounds = (AABB) toMojangBounds.invoke(bounds.invoke(body));
        // A cached tracking reference alone must not permit remote management after leaving a craft.
        if (!finite(worldBounds) || !worldBounds.inflate(1).contains(passenger.position())) return null;
        return (UUID) id.invoke(body);
    }

    PhysicalVehicleView snapshot(Object body, BlockPos controllerPosition, Direction facing, String dimensionId) throws ReflectiveOperationException {
        Object currentPose = pose.invoke(body);
        Object previousPose = lastPose.invoke(body);
        if (currentPose == null || previousPose == null) throw new ReflectiveOperationException("native_pose_history_unavailable");
        Vec3 localAnchor = Vec3.atCenterOf(controllerPosition);
        Vec3 worldPosition = (Vec3) transformPosition.invoke(currentPose, localAnchor);
        Vec3 previousWorldPosition = (Vec3) transformPosition.invoke(previousPose, localAnchor);
        Vec3 forward = (Vec3) transformNormal.invoke(currentPose, Vec3.atLowerCornerOf(facing.getNormal()));
        Vec3 worldForward = finite(forward) && forward.lengthSqr() > 1.0E-12 ? forward.normalize() : Vec3.ZERO;
        // Sable's cached velocity differences COM positions, which jump when blocks assemble.
        // Transform the same material anchor through each pose, including its own rotation point.
        Vec3 linearVelocity = worldPosition.subtract(previousWorldPosition).scale(20);
        AABB worldBounds = (AABB) toMojangBounds.invoke(bounds.invoke(body));
        double mass = mass(body);
        UUID physicalId = (UUID) id.invoke(body);
        boolean available = physicalId != null && isLive(body) && positiveMass(mass)
                && finite(worldPosition) && finite(linearVelocity) && worldForward.lengthSqr() > 0
                && finite(worldBounds);
        return new PhysicalVehicleView(physicalId, dimensionId, worldPosition, worldForward,
                linearVelocity, worldBounds, mass, available);
    }

    Vec3 angularVelocity(Object body) throws ReflectiveOperationException {
        if (angularVelocity==null || !isLive(body)) return null;
        Object value=angularVelocity.get(body);
        if (!(value instanceof Vector3dc vector)) return null;
        Vec3 result=new Vec3(vector.x(),vector.y(),vector.z());
        return finite(result) ? result : null;
    }

    NativeGroundObservation groundObservation(Object body, List<BlockEntity> targets) throws ReflectiveOperationException {
        Vec3 angular=angularVelocity(body);
        if (wheels==null || angular==null || targets==null || targets.isEmpty() || targets.size()>32)
            return NativeGroundObservation.unavailable("native_ground_evidence_unavailable");
        Object currentPose=pose.invoke(body);
        List<NativeGroundObservation.WheelContact> contacts=new ArrayList<>();
        java.util.HashSet<BlockPos> seen=new java.util.HashSet<>();
        for (BlockEntity target : targets) {
            if (target==null || target.isRemoved() || !(target.getLevel() instanceof ServerLevel server)
                    || !sameLiveStructure(body,containing(target)) || !seen.add(target.getBlockPos()))
                return NativeGroundObservation.unavailable("invalid_or_foreign_wheel");
            if (!target.getBlockState().hasProperty(BlockStateProperties.HORIZONTAL_FACING))
                return NativeGroundObservation.unavailable("wheel_orientation_unavailable");
            Direction facing=target.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
            Vec3 localCenter=Vec3.atCenterOf(target.getBlockPos().relative(facing));
            Vector3dc axis=wheels.travelAxis(target,facing);
            Vec3 localAxis=new Vec3(axis.x(),axis.y(),axis.z());
            // Native cast samples three local rays, five blocks down, ignoring its own sublevel.
            // Check the transformed columns first; this observer must never load terrain chunks.
            for (int offset=-1;offset<=1;offset++) {
                Vec3 start=localCenter.add(localAxis.scale(offset));
                Vec3 worldStart=(Vec3)transformPosition.invoke(currentPose,start);
                Vec3 worldEnd=(Vec3)transformPosition.invoke(currentPose,start.add(0,-5,0));
                if (!finite(worldStart) || !finite(worldEnd) || !server.hasChunkAt(BlockPos.containing(worldStart))
                        || !server.hasChunkAt(BlockPos.containing(worldEnd)))
                    return NativeGroundObservation.unavailable("wheel_terrain_unloaded");
            }
            OffroadWheelReflectionAccess.Evidence evidence=wheels.observe(target,currentPose,facing);
            Vec3 center=(Vec3)transformPosition.invoke(currentPose,localCenter);
            contacts.add(new NativeGroundObservation.WheelContact(center,evidence.tireRadius(),evidence.suspensionCompression(),evidence.supported()));
        }
        return new NativeGroundObservation((UUID)id.invoke(body),angular,contacts,true,"observed");
    }

    GroundSurfaceObservation groundSurface(Object body, List<BlockEntity> targets) throws ReflectiveOperationException {
        if(groundSurfaces==null)return GroundSurfaceObservation.unavailable("ground_surface_native_ray_abi_unavailable");
        if(!isLive(body) || !(level.invoke(body) instanceof ServerLevel server) || targets==null || targets.size()<3 || targets.size()>32)
            return GroundSurfaceObservation.unavailable("ground_surface_wheel_set_incomplete");
        long tick=server.getGameTime(),nanos=System.nanoTime();
        UUID bodyId=(UUID)id.invoke(body);
        Object currentPose=pose.invoke(body);
        if(currentPose==null || bodyId==null)return GroundSurfaceObservation.unavailable("ground_surface_native_pose_unavailable");
        var evidence=new ArrayList<GroundSurfaceObservation.WheelEvidence>();
        var positions=new java.util.HashSet<BlockPos>();
        for(BlockEntity target:targets) {
            if(target==null || target.isRemoved() || target.getLevel()!=server || !positions.add(target.getBlockPos())
                    || containing(target)!=body)return GroundSurfaceObservation.unavailable("ground_surface_wheel_binding_invalid");
            var chunk=server.getChunkSource().getChunkNow(target.getBlockPos().getX()>>4,target.getBlockPos().getZ()>>4);
            if(chunk==null || chunk.getBlockEntities().get(target.getBlockPos())!=target)
                return GroundSurfaceObservation.unavailable("ground_surface_wheel_not_loaded");
            evidence.add(groundSurfaces.observe(server,target,body,currentPose,bodyId));
        }
        if(!isLive(body) || server.getGameTime()!=tick || !bodyId.equals(id.invoke(body)))
            return GroundSurfaceObservation.unavailable("ground_surface_frame_changed");
        return GroundSurfaceObservation.fromEvidence(bodyId,tick,nanos,targets.size(),evidence);
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
        if (actuators == null) return NativeControlResult.failure("optional_mod_unavailable");
        try {
            String invalid = bindingFailure(controllerBody, targetBody, target, actuators.throttleType());
            if (invalid != null) return NativeControlResult.failure(invalid);
            int observed = ((Number) actuators.getThrottleState().invoke(target)).intValue();
            ThrottleLease previous = throttleLeases.get(target);
            if (previous != null && observed != previous.observedState()) {
                throttleLeases.remove(target);
                return NativeControlResult.failure("manual_control");
            }
            if (previous == null || previous.command() != signal) {
                actuators.setThrottle().invoke(target, signal);
                throttleLeases.put(target, new ThrottleLease(signal,
                        ((Number) actuators.getThrottleState().invoke(target)).intValue()));
            }
            return NativeControlResult.applied();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return controlFailure(failure);
        }
    }

    NativeControlResult steering(Object controllerBody, Object targetBody, Object target, float angleDegrees) {
        if (!Float.isFinite(angleDegrees)) return NativeControlResult.failure("invalid_angle");
        if (actuators == null) return NativeControlResult.failure("optional_mod_unavailable");
        try {
            String invalid = bindingFailure(controllerBody, targetBody, target, actuators.steeringType());
            if (invalid != null) return NativeControlResult.failure(invalid);
            if (actuators.steeringHeld().getBoolean(target)) return NativeControlResult.failure("manual_control");
            actuators.setSteering().invoke(target, angleDegrees);
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
