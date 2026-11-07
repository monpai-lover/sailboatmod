package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;

/** All optional native names and motion calls are confined to this lazy server adapter. */
final class RailAircraftReflectionAccess {
    @FunctionalInterface interface ClassLookup { Class<?> find(String name) throws ClassNotFoundException; }

    final Class<?> postEvent;
    private final Class<?> bodyType, pipelineType, rapierType;
    private final Object helper;
    private final Method containing, bodyId, bodyLevel, removed, pose, transformPosition;
    private final Method orientation, rotationPoint, scale, systemGet, systemLevel, getPipeline;
    private final Method resetVelocity, teleport, updatePose, updateBounds, clearForces;
    private final Method eventSystem, eventStep, constraintValid, getPlot, getActors;
    private final Field latestAngularVelocity;
    private final ClassValue<FccReadAccess> fccReaders = new ClassValue<>() {
        @Override protected FccReadAccess computeValue(Class<?> computer) {
            try { return FccReadAccess.bind(computer); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return new FccReadAccess(null, null, null, null, null, null, unavailable);
            }
        }
    };

    static RailAircraftReflectionAccess bind(ClassLookup lookup) throws ReflectiveOperationException {
        return new RailAircraftReflectionAccess(lookup);
    }

    private RailAircraftReflectionAccess(ClassLookup lookup) throws ReflectiveOperationException {
        for (var pin : RailAircraftMotionCompatibility.PINNED_CLASSES.entrySet())
            checkHash(lookup.find(pin.getKey()), pin.getValue());
        for (var pin : RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES.entrySet())
            checkHash(lookup.find(pin.getKey()), pin.getValue());
        rapierType = lookup.find(RailAircraftMotionCompatibility.RAPIER);
        constraintValid = lookup.find(RailAircraftMotionCompatibility.CONSTRAINT).getMethod("isValid");
        bodyType = lookup.find(RailAircraftMotionCompatibility.BODY);
        pipelineType = lookup.find(RailAircraftMotionCompatibility.PIPELINE);
        Class<?> bodyInterface = lookup.find("dev.ryanhcode.sable.api.physics.PhysicsPipelineBody");
        Class<?> poseType = lookup.find("dev.ryanhcode.sable.companion.math.Pose3dc");
        Class<?> systemType = lookup.find(RailAircraftMotionCompatibility.SYSTEM);
        helper = lookup.find("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
        if (helper == null) throw new ReflectiveOperationException("air_rail_missing_sable_helper");
        containing = lookup.find("dev.ryanhcode.sable.ActiveSableCompanion").getMethod("getContaining", BlockEntity.class);
        bodyId = bodyType.getMethod("getUniqueId"); bodyLevel = bodyType.getMethod("getLevel");
        removed = bodyType.getMethod("isRemoved"); pose = bodyType.getMethod("logicalPose");
        getPlot = bodyType.getMethod("getPlot");
        getActors = lookup.find(RailAircraftMotionCompatibility.PLOT).getMethod("getBlockEntityActors");
        latestAngularVelocity = bodyType.getField("latestAngularVelocity");
        if (!Vector3dc.class.isAssignableFrom(latestAngularVelocity.getType()))
            throw new ReflectiveOperationException("air_rail_invalid_angular_velocity_abi");
        transformPosition = poseType.getMethod("transformPosition", Vec3.class);
        orientation = poseType.getMethod("orientation"); rotationPoint = poseType.getMethod("rotationPoint");
        scale = poseType.getMethod("scale");
        systemGet = systemType.getMethod("get", Level.class); systemLevel = systemType.getMethod("getLevel");
        getPipeline = systemType.getMethod("getPipeline");
        resetVelocity = pipelineType.getMethod("resetVelocity", bodyInterface);
        teleport = pipelineType.getMethod("teleport", bodyInterface, Vector3dc.class, Quaterniondc.class);
        updatePose = systemType.getMethod("updatePose", bodyType); updateBounds = bodyType.getMethod("updateBoundingBox");
        clearForces = bodyType.getMethod("prePhysicsTickBegin");
        postEvent = lookup.find("dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent");
        eventSystem = postEvent.getMethod("getPhysicsSystem"); eventStep = postEvent.getMethod("getTimeStep");
    }

    Object body(BlockEntity controller) throws ReflectiveOperationException {
        Object body = call(containing, helper, controller);
        return live(body, controller.getLevel()) ? body : null;
    }
    UUID id(Object body) throws ReflectiveOperationException { return (UUID) call(bodyId, body); }
    boolean live(Object body, Object world) throws ReflectiveOperationException {
        return body != null && body.getClass() == bodyType && !Boolean.TRUE.equals(call(removed, body))
                && call(bodyLevel, body) == world && id(body) != null;
    }
    Object system(ServerLevel level) throws ReflectiveOperationException {
        Object system = call(systemGet, null, level);
        if (system == null) throw new ReflectiveOperationException("air_rail_missing_physics_system");
        pipeline(system); return system;
    }
    Object eventSystem(Object event) throws ReflectiveOperationException { return call(eventSystem, event); }
    double eventStep(Object event) throws ReflectiveOperationException { return ((Number) call(eventStep, event)).doubleValue(); }
    ServerLevel level(Object system) throws ReflectiveOperationException { return (ServerLevel) call(systemLevel, system); }

    double angularSpeed(Object body) throws ReflectiveOperationException {
        Object value = latestAngularVelocity.get(body);
        if (!(value instanceof Vector3dc velocity))
            throw new ReflectiveOperationException("air_rail_invalid_native_angular_velocity");
        double speed = velocity.length();
        if (!Double.isFinite(speed))
            throw new ReflectiveOperationException("air_rail_invalid_native_angular_velocity");
        return speed;
    }
    boolean constraintValid(Object handle) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(call(constraintValid, handle));
    }

    NativeControlResult foreignFlightControlStatus(Object body, Object world) throws ReflectiveOperationException {
        Object actors = call(getActors, call(getPlot, body));
        if (!(actors instanceof Iterable<?> iterable))
            return NativeControlResult.failure("flight_control_state_unavailable");
        return RailAircraftFlightControlGuard.inspect(iterable, 4096,
                actor -> actor instanceof BlockEntity entity && !entity.isRemoved() && entity.getLevel() == world
                        && call(containing, helper, entity) == body,
                actor -> {
                    if (actor == null) throw new ReflectiveOperationException("null_native_actor");
                    Class<?> type = actor.getClass();
                    for (int depth = 0; type != null && depth < 32; depth++, type = type.getSuperclass())
                        if (type.getName().equals(RailAircraftFlightControlGuard.COMPUTER))
                            return fccReaders.get(type).read(actor);
                    if (type != null) throw new ReflectiveOperationException("native_actor_hierarchy_limit");
                    return null;
                });
    }

    private record FccReadAccess(Field working, Field defaultEnabled, Field broker,
                                 Method count, Method frame, Method commands, Throwable failure) {
        static FccReadAccess bind(Class<?> computer) throws ReflectiveOperationException {
            checkHash(computer, RailAircraftFlightControlGuard.PINS.get(computer.getName()));
            Field working = computer.getField("working");
            Field enabled = computer.getDeclaredField("workingDefaultEnabled");
            Field broker = computer.getDeclaredField("navigationLeaseBroker");
            if (working.getType() != boolean.class || enabled.getType() != boolean.class
                    || !broker.getType().getName().equals(RailAircraftFlightControlGuard.BROKER)
                    || !enabled.trySetAccessible() || !broker.trySetAccessible())
                throw new ReflectiveOperationException("unsupported_flight_control_ownership_fields");
            Class<?> brokerType = broker.getType();
            checkHash(brokerType, RailAircraftFlightControlGuard.PINS.get(brokerType.getName()));
            Method count = brokerType.getMethod("activeBundleCount"), frame = brokerType.getMethod("effectiveFrame");
            Class<?> frameType = frame.getReturnType();
            if (count.getReturnType() != int.class || !frameType.getName().equals(RailAircraftFlightControlGuard.FRAME))
                throw new ReflectiveOperationException("unsupported_flight_control_broker");
            checkHash(frameType, RailAircraftFlightControlGuard.PINS.get(frameType.getName()));
            Method commands = frameType.getMethod("commands");
            if (commands.getReturnType() != Map.class)
                throw new ReflectiveOperationException("unsupported_flight_control_frame");
            return new FccReadAccess(working, enabled, broker, count, frame, commands, null);
        }
        RailAircraftFlightControlGuard.Readback read(Object computer) throws ReflectiveOperationException {
            if (failure != null) throw new ReflectiveOperationException("unknown_flight_control_ownership_abi", failure);
            Object brokerValue = broker.get(computer);
            if (brokerValue == null) throw new ReflectiveOperationException("missing_flight_control_broker");
            Object frameValue = call(frame, brokerValue);
            if (frameValue == null) throw new ReflectiveOperationException("missing_flight_control_frame");
            Object commandsValue = call(commands, frameValue);
            if (!(commandsValue instanceof Map<?, ?> entries))
                throw new ReflectiveOperationException("missing_flight_control_commands");
            return new RailAircraftFlightControlGuard.Readback(working.getBoolean(computer), defaultEnabled.getBoolean(computer),
                    ((Number) call(count, brokerValue)).intValue(), entries.size());
        }
    }

    RailAircraftMotionState.Frame frame(Object body, Vec3 localAnchor) throws ReflectiveOperationException {
        Object current = call(pose, body);
        Vec3 anchor = (Vec3) call(transformPosition, current, localAnchor);
        Quaterniond q = new Quaterniond((Quaterniondc) call(orientation, current));
        var frame = new RailAircraftMotionState.Frame(anchor, q);
        if (!RailAircraftMotionState.valid(frame)) throw new ReflectiveOperationException("air_rail_invalid_native_pose");
        return frame;
    }

    void apply(Object system, Object body, Vec3 localAnchor, RailAircraftMotionState.Frame target)
            throws ReflectiveOperationException {
        if (!live(body, level(system))) throw new ReflectiveOperationException("air_rail_native_body_removed");
        Object nativePose = call(pose, body);
        Vec3 pivot = vector((Vector3dc) call(rotationPoint, nativePose));
        Vec3 nativeScale = vector((Vector3dc) call(scale, nativePose));
        var desiredPosition = RailAircraftMotionState.posePosition(localAnchor, pivot, nativeScale,
                target.orientation(), target.anchor());
        Object pipeline = pipeline(system);
        call(resetVelocity, pipeline, body);
        call(teleport, pipeline, body, desiredPosition, target.orientation());
        // This preserves Sable's original tick-start lastPose and refreshes native network velocity/bounds.
        call(updatePose, system, body); call(updateBounds, body);
    }

    void release(Object system, Object body) throws ReflectiveOperationException {
        if (!live(body, level(system))) return;
        call(clearForces, body);
        call(resetVelocity, pipeline(system), body);
        call(updatePose, system, body); call(updateBounds, body);
    }

    Object pipeline(Object system) throws ReflectiveOperationException {
        Object value = call(getPipeline, system);
        if (value == null || value.getClass() != rapierType || !pipelineType.isInstance(value))
            throw new ReflectiveOperationException("air_rail_unknown_physics_pipeline");
        return value;
    }
    private static Vec3 vector(Vector3dc v) { return new Vec3(v.x(), v.y(), v.z()); }
    private static Object call(Method method, Object target, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            throw new ReflectiveOperationException("air_rail_native_call_failed:" + method.getName(), failure.getCause());
        }
    }
    private static void checkHash(Class<?> type, String expected) throws ReflectiveOperationException {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(path)) {
            if (input == null || !expected.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))
                throw new ReflectiveOperationException("air_rail_unknown_native_abi:" + type.getName());
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new ReflectiveOperationException("air_rail_native_abi_unavailable", failure);
        }
    }
}
