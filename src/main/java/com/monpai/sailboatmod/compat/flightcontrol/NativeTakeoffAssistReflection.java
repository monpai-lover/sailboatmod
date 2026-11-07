package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity;
import com.monpai.sailboatmod.transport.air.TakeoffAssistSession;
import com.monpai.sailboatmod.transport.physical.NativeGroundObservation;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.Level;
import com.monpai.sailboatmod.transport.air.UnloadedWheelContactPolicy;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Version-pinned observations and the public native impulse sink. No native control/task writes. */
final class NativeTakeoffAssistReflection {
    static final long MAX_FRAME_AGE = 250_000_000L;
    record Binding(BlockEntity requester, UUID registration, BlockEntity terminal, List<BlockPos> wheels) { }
    record Observation(Object bodyHandle, UUID body, UUID task, String profile, String phase,
            boolean ready, boolean authority, boolean takeoff, boolean propulsion, boolean brakes,
            boolean fresh, double physicsTime, double dt, double mass, double speed, double vr, double commandedSpeed,
            double altitude, double verticalSpeed, int positiveWheels,
            TakeoffAssistSession.Contact contact, Vector3d localForward) { }

    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        protected Map<String, Field> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private final Class<?> serverBody, computerType, wheelType, thrusterType;
    private final Object helper;
    private final Method containing, bodyId, bodyRemoved, bodyLevel, massTracker, mass,
            readScope, linkedComputer, linkedTo, reciprocal, stage, handle, handleValid, impulse,
            active, maxThrust, linked, linkValid, thrusterComputer;
    private final Method heldTire, getTireComponent, tireRadius, logicalPose, transformNormal;
    private final Method wheelTravelAxis, transformPosition, inversePosition, ignoreRayBody, containingAt;
    private final Field tireComponent;

    NativeTakeoffAssistReflection(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable", false, loader);
        Class<?> companion = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion", false, loader);
        Class<?> subLevel = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel", false, loader);
        serverBody = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel", false, loader);
        Class<?> system = Class.forName("dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem", false, loader);
        Class<?> rigid = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle", false, loader);
        Class<?> fence = Class.forName("ace.flight.navigation.NavigationControlCommitFence", false, loader);
        Class<?> terminal = Class.forName("ace.flight.block.NavigationTerminalBlockEntity", false, loader);
        computerType = Class.forName("ace.flight.block.FlightControlComputerBlockEntity", false, loader);
        wheelType = Class.forName("dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity", false, loader);
        thrusterType = Class.forName("ace.flight.api.IFCCThruster", false, loader);
        Class<?> follower = Class.forName("ace.flight.navigation.NavigationRouteFollower", false, loader);
        helper = sable.getField("HELPER").get(null);
        containing = companion.getMethod("getContaining", BlockEntity.class);
        bodyId = subLevel.getMethod("getUniqueId");
        bodyRemoved = subLevel.getMethod("isRemoved");
        bodyLevel = serverBody.getMethod("getLevel");
        massTracker = serverBody.getMethod("getMassTracker");
        mass = Class.forName("dev.ryanhcode.sable.api.physics.mass.MassData", false, loader).getMethod("getMass");
        readScope = fence.getMethod("readScope");
        linkedComputer = terminal.getMethod("getLinkedComputerPos");
        linkedTo = terminal.getMethod("isLinkedTo", BlockPos.class);
        reciprocal = computerType.getMethod("hasReciprocalControllerAt", BlockPos.class);
        stage = follower.getMethod("fixedWingStage");
        handle = system.getMethod("getPhysicsHandle", serverBody);
        handleValid = rigid.getMethod("isValid");
        impulse = rigid.getMethod("applyLinearImpulse", Vector3dc.class);
        active = thrusterType.getMethod("isFCCActive");
        maxThrust = thrusterType.getMethod("getFCCMaxThrust");
        linked = thrusterType.getMethod("isFCCLinked");
        linkValid = thrusterType.getMethod("isFCCLinkConfigurationValid");
        thrusterComputer = thrusterType.getMethod("getFCCLinkedComputerPos");
        heldTire = wheelType.getMethod("getHeldItem");
        getTireComponent = heldTire.getReturnType().getMethod("get", DataComponentType.class);
        tireComponent = Class.forName("dev.ryanhcode.offroad.index.OffroadDataComponents", false, loader).getField("TIRE");
        tireRadius = Class.forName("dev.ryanhcode.offroad.content.components.TireLike", false, loader).getMethod("radius");
        logicalPose = serverBody.getMethod("logicalPose");
        transformNormal = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc", false, loader)
                .getMethod("transformNormal", Vec3.class);
        Class<?> poseType = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc", false, loader);
        transformPosition = poseType.getMethod("transformPosition", Vec3.class);
        wheelTravelAxis = wheelType.getDeclaredMethod("getRotatedWheelAxis", Vec3i.class);
        inversePosition = poseType.getMethod("transformPositionInverse",Vec3.class);
        Class<?> rayExtension = Class.forName("dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension",false,loader);
        ignoreRayBody = rayExtension.getMethod("sable$setIgnoredSubLevel",subLevel);
        containingAt = companion.getMethod("getContaining",Level.class,net.minecraft.core.Position.class);
        if (!wheelTravelAxis.trySetAccessible()) throw new IllegalAccessException("Native wheel observation unavailable");
    }

    boolean configuredWheel(Binding binding, BlockEntity wheel, Object body, UUID expectedBody)
            throws ReflectiveOperationException {
        if (!(binding.requester() instanceof RegisteredVehicleControllerBlockEntity requester)
                || !(requester.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || requester.isRemoved() || wheel.isRemoved() || wheel.getLevel() != level
                || binding.registration() == null || !binding.registration().equals(requester.getTransportUuid())
                || !"FIXED_WING".equals(name(field(requester,"aircraftClass")))
                || bool(field(requester,"manualTakeover")) || bool(field(requester,"groundOutputsOwned"))
                || !Objects.equals(expectedBody,field(requester,"groundCalibrationBody"))
                || !requester.nativeGroundHoldRegistrationMatches(binding.registration(),expectedBody)
                || binding.wheels().size() < 3 || binding.wheels().size() > 32
                || !binding.wheels().contains(wheel.getBlockPos().subtract(requester.getBlockPos()))
                || !wheelType.isInstance(wheel) || !serverBody.isInstance(body)
                || bool(bodyRemoved.invoke(body)) || bodyLevel.invoke(body) != level
                || !Objects.equals(expectedBody,bodyId.invoke(body)) || !loadedIdentity(level,wheel)
                || !loadedIdentity(level,requester) || containing.invoke(helper,wheel) != body
                || containing.invoke(helper,requester) != body) return false;
        Object ground = field(requester,"groundConfiguration");
        return ground != null && bool(call(call(ground,"calibration"),"liveVerified"));
    }

    double wheelRadius(BlockEntity wheel) throws ReflectiveOperationException {
        Object tire = getTireComponent.invoke(heldTire.invoke(wheel),tireComponent.get(null));
        return tire == null ? Double.NaN : ((Number)tireRadius.invoke(tire)).doubleValue();
    }

    double upright(Object body) throws ReflectiveOperationException {
        return ((Vec3)transformNormal.invoke(logicalPose.invoke(body),new Vec3(0,1,0))).y;
    }

    boolean staticFloorStillUnloaded(BlockEntity wheel, Object body, double observedExtension)
            throws ReflectiveOperationException {
        if (!(wheel.getLevel() instanceof ServerLevel level)
                || !wheel.getBlockState().hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return false;
        Direction facing = wheel.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3i normal = Direction.get(Direction.AxisDirection.POSITIVE,facing.getAxis()).getNormal();
        Vector3dc axis = (Vector3dc)wheelTravelAxis.invoke(wheel,new Vec3i(normal.getZ(),0,normal.getX()));
        Object pose = logicalPose.invoke(body);
        Vec3 center = Vec3.atCenterOf(wheel.getBlockPos().relative(facing));
        // Inspect every native ray: the native aggregate overwrites its hit metadata
        // even when a later ray did not provide the minimum extension.
        List<UnloadedWheelContactPolicy.RayHit> hits = new ArrayList<>();
        double minimum = 5;
        for (int i=-1;i<=1;i++) {
            Vec3 start = center.add(axis.x()*i,axis.y()*i,axis.z()*i);
            Vec3 end = start.add(0,-5,0);
            Vec3 a = (Vec3)transformPosition.invoke(pose,start);
            Vec3 b = (Vec3)transformPosition.invoke(pose,end);
            if (!Double.isFinite(a.x) || !Double.isFinite(a.y) || !Double.isFinite(a.z)
                    || !Double.isFinite(b.x) || !Double.isFinite(b.y) || !Double.isFinite(b.z)
                    || !level.hasChunkAt(BlockPos.containing(a)) || !level.hasChunkAt(BlockPos.containing(b))) return false;
            ClipContext context = new ClipContext(start,end,ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,CollisionContext.empty());
            ignoreRayBody.invoke(context,body);
            var hit = level.clip(context);
            if (hit.getType() == HitResult.Type.MISS) {
                hits.add(new UnloadedWheelContactPolicy.RayHit(false,false,false));
                continue;
            }
            Object hitBody = containingAt.invoke(helper,level,hit.getLocation());
            boolean staticFloor = hitBody == null && hit.getDirection() == Direction.UP;
            hits.add(new UnloadedWheelContactPolicy.RayHit(true,hitBody == null,hit.getDirection() == Direction.UP));
            if (!staticFloor) return false;
            Vec3 localHit = (Vec3)inversePosition.invoke(pose,hit.getLocation());
            double distance = center.y-localHit.y;
            if (localHit.y <= center.y && start.distanceTo(localHit) >= .05 && distance > 1e-5)
                minimum = Math.min(minimum,distance);
        }
        return UnloadedWheelContactPolicy.staticFloorHits(hits)
                && Double.isFinite(minimum) && Math.abs(minimum-observedExtension) <= 1e-6;
    }

    /** Fence and native broker stay held through the decision and public native sink. */
    void observe(Binding binding, Object physicsSystem, double dt, Consumer<Observation> consumer) throws Exception {
        if (!(binding.requester() instanceof RegisteredVehicleControllerBlockEntity requester)
                || !(requester.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || requester.isRemoved() || binding.terminal() == null || binding.terminal().isRemoved()
                || binding.terminal().getLevel() != level || binding.registration() == null
                || !binding.registration().equals(requester.getTransportUuid())
                || !"ace.flight.block.NavigationTerminalBlockEntity".equals(binding.terminal().getClass().getName())
                || !loadedIdentity(level, requester) || !loadedIdentity(level, binding.terminal()))
            throw new IllegalStateException("requester_binding_lost");
        if (!NativeGroundNeutralCompatibility.isApplied()) throw new IllegalStateException("native_version_unsupported");
        try (AutoCloseable scope = (AutoCloseable) readScope.invoke(null)) {
            BlockEntity terminal = binding.terminal();
            BlockPos computerPos = (BlockPos) linkedComputer.invoke(terminal);
            BlockEntity computer = computerPos == null ? null : loaded(level, computerPos);
            if (computer == null || !computerType.isInstance(computer)) throw new IllegalStateException("computer_unavailable");
            Object broker = field(computer, "navigationLeaseBroker");
            synchronized (broker) {
                Object body = containing.invoke(helper, requester);
                if (!serverBody.isInstance(body) || bool(bodyRemoved.invoke(body)) || bodyLevel.invoke(body) != level
                        || containing.invoke(helper, terminal) != body || containing.invoke(helper, computer) != body
                        || !bool(linkedTo.invoke(terminal, computerPos))
                        || !bool(reciprocal.invoke(computer, terminal.getBlockPos())))
                    throw new IllegalStateException("physical_body_binding_lost");
                UUID id = (UUID) bodyId.invoke(body);
                if (!requester.nativeGroundHoldRegistrationMatches(binding.registration(), id)
                        || !Objects.equals(id, field(requester, "groundCalibrationBody"))
                        || !"FIXED_WING".equals(name(field(requester, "aircraftClass")))
                        || bool(field(requester, "manualTakeover")) || bool(field(requester, "groundOutputsOwned"))
                        || bool(field(requester, "flightRecovering")))
                    throw new IllegalStateException("requester_no_longer_eligible");
                Object ground = field(requester, "groundConfiguration");
                if (ground == null || !bool(call(call(ground, "calibration"), "liveVerified")))
                    throw new IllegalStateException("calibration_lost");
                Object vehicle = field(computer, "navigationVehicleStateSnapshot");
                Object task = field(terminal, "taskSnapshot"), slot = field(terminal, "profileSlotSnapshot");
                Object network = field(terminal, "controlNetworkSnapshot");
                UUID taskId = (UUID) field(task, "taskId");
                String profile = fingerprint(field(slot, "fingerprint"));
                String phase = name(field(task, "phase"));
                long now = System.nanoTime(), sampled = number(field(vehicle, "sampledNanos"));
                double physicsTime = decimal(field(vehicle, "physicsTimeSeconds"));
                boolean fresh = "AVAILABLE".equals(name(field(vehicle, "status")))
                        && now-sampled >= 0 && now-sampled <= MAX_FRAME_AGE
                        && id.equals(field(vehicle, "structureId"))
                        && level.dimension().location().toString().equals(field(vehicle, "dimensionId"))
                        && Double.compare(physicsTime, decimal(field(computer, "navigationPhysicsTimeSeconds"))) == 0;
                long topology = number(field(computer, "navigationTopologyEpoch"));
                boolean bound = "AVAILABLE".equals(name(field(network, "status")))
                        && computerPos.equals(field(network, "computerPosition"))
                        && level.dimension().location().toString().equals(field(network, "dimensionId"))
                        && topology == number(field(network, "topologyEpoch"));
                boolean validProfile = "VALID".equals(name(field(slot, "status"))) && !profile.isBlank()
                        && profile.equals(String.valueOf(field(task, "profileFingerprint")))
                        && number(field(slot, "slotGeneration")) == number(field(task, "slotGeneration"));
                var installed = FlightControlBridge.inspectInstalledProfile(level, terminal.getBlockPos());
                validProfile &= installed.available() && profile.equals(installed.fingerprint());
                if (!bound || !validProfile) throw new IllegalStateException("native_profile_or_network_lost");
                boolean normal = bool(field(computer, "working")) && number(field(computer, "mode")) == 0
                        && !bool(field(computer, "workDryRun")) && !bool(field(computer, "debugApplyForces"))
                        && !bool(field(computer, "navigationModeRevocationPending"))
                        && "FIXED_WING".equals(name(field(computer, "airframeMode")));
                if (!normal) throw new IllegalStateException("native_normal_control_required");
                var ownership = FlightControlBridge.inspectControlOwnership(level, terminal.getBlockPos());
                boolean authority = bound && validProfile && normal && ownership.provesFlight(id, taskId, profile);
                boolean ready = bound && validProfile && fresh && normal && "READY".equals(phase)
                        && "NONE".equals(name(field(task, "blockReason"))) && ownership.provesReleased(id);
                Object follower = field(terminal, "routeFollower");
                String nativeStage = follower == null ? "none" : String.valueOf(stage.invoke(follower));
                boolean takeoff = "RUNNING".equals(phase) && "NONE".equals(name(field(task, "blockReason")))
                        && NativeTakeoffAssistEvidence.takeoffStage(nativeStage)
                        && follower != null && !bool(field(follower, "fixedWingCruiseEntered"));
                double commandedSpeed = takeoff
                        ? decimal(field(field(follower, "fixedWingTakeoffSpeed"), "command")) : 0;
                var performance = FlightControlBridge.inspectFixedWingPerformance(level, terminal.getBlockPos());
                double vr = performance.vr();
                boolean reference = performance.failure(id, level.getGameTime()).isBlank();
                if (!reference) throw new IllegalStateException("native_takeoff_reference_unavailable");
                double actualMass = decimal(mass.invoke(massTracker.invoke(body)));
                boolean propulsion = normal && reference && propulsion(level, computer, body, vehicle, topology, now, actualMass);
                List<BlockEntity> wheels = wheelMounts(binding, level, body);
                NativeGroundObservation contacts = PhysicalVehicleBridge.groundObservation(requester, wheels);
                var contact = NativeTakeoffAssistEvidence.contact(contacts, id, wheels.size());
                int positives = (int) contacts.wheels().stream().filter(w -> w.supported() && w.suspensionCompression() > 0).count();
                // FC 0.8.3 fixedWingBrakeDeployment stores allowBrakes(), a hysteretic
                // aerodynamic planning permission above VR, not an applied wheel brake.
                // The session separately limits assistance to the native commanded speed.
                int[] wheelBrakeSignals = new int[wheels.size()];
                for (int i = 0; i < wheels.size(); i++)
                    wheelBrakeSignals[i] = level.getSignal(wheels.get(i).getBlockPos().above(), Direction.UP);
                boolean brakes = NativeTakeoffAssistEvidence.wheelBrakesReleased(wheelBrakeSignals);
                Vector3d local = new Vector3d();
                double speed = Math.hypot(decimal(field(vehicle, "worldVelocityX")), decimal(field(vehicle, "worldVelocityZ")));
                if (takeoff) {
                    Object forward = field(follower, "bodyForward");
                    Vector3d localAxis = switch (name(forward)) {
                        case "NORTH" -> new Vector3d(0,0,-1);
                        case "EAST" -> new Vector3d(1,0,0);
                        case "SOUTH" -> new Vector3d(0,0,1);
                        case "WEST" -> new Vector3d(-1,0,0);
                        default -> new Vector3d();
                    };
                    Object launch = field(follower, "fixedWingLaunchHeading");
                    var direction = NativeTakeoffAssistEvidence.direction(new Quaterniond(
                                    decimal(field(vehicle, "quaternionX")), decimal(field(vehicle, "quaternionY")),
                                    decimal(field(vehicle, "quaternionZ")), decimal(field(vehicle, "quaternionW"))),
                            localAxis, launch instanceof Vector3dc v ? v : null,
                            new Vector3d(decimal(field(vehicle,"worldVelocityX")), decimal(field(vehicle,"worldVelocityY")),
                                    decimal(field(vehicle,"worldVelocityZ"))));
                    local = direction.local();
                    authority &= direction.valid();
                }
                Object bodyHandle = physicsSystem == null ? null : handle.invoke(physicsSystem, body);
                if (physicsSystem != null && (bodyHandle == null || !bool(handleValid.invoke(bodyHandle))))
                    throw new IllegalStateException("native_physics_handle_unavailable");
                consumer.accept(new Observation(bodyHandle, id, taskId, profile, phase, ready, authority,
                        takeoff, propulsion, brakes, fresh && bound && validProfile, physicsTime, dt, actualMass, speed,
                        vr, commandedSpeed, decimal(field(vehicle,"positionY")), decimal(field(vehicle,"worldVelocityY")),
                        positives, contact, local));
            }
        }
    }

    void apply(Observation state, double magnitude) throws ReflectiveOperationException {
        if (state.bodyHandle() == null || !Double.isFinite(magnitude) || magnitude <= 0)
            throw new IllegalStateException("invalid_native_impulse");
        // Sable's public handle accepts local impulses. The vector was projected in world XZ,
        // then transformed back to body coordinates; no vertical boost or torque is requested.
        impulse.invoke(state.bodyHandle(), new Vector3d(state.localForward()).mul(magnitude));
    }

    private List<BlockEntity> wheelMounts(Binding binding, ServerLevel level, Object body) throws ReflectiveOperationException {
        if (binding.wheels() == null || binding.wheels().size() < 3 || binding.wheels().size() > 32)
            throw new IllegalStateException("configured_wheels_required");
        List<BlockEntity> result = new ArrayList<>();
        HashSet<BlockPos> unique = new HashSet<>();
        for (BlockPos offset : binding.wheels()) {
            if (offset == null || Math.abs((long)offset.getX()) > 256 || Math.abs((long)offset.getY()) > 256
                    || Math.abs((long)offset.getZ()) > 256) throw new IllegalStateException("invalid_wheel_offset");
            BlockPos position = binding.requester().getBlockPos().offset(offset);
            BlockEntity wheel = loaded(level, position);
            if (!unique.add(position) || wheel == null || !wheelType.isInstance(wheel)
                    || containing.invoke(helper, wheel) != body) throw new IllegalStateException("configured_wheel_binding_lost");
            result.add(wheel);
        }
        return result;
    }

    private boolean propulsion(ServerLevel level, BlockEntity computer, Object body, Object vehicle,
            long topology, long now, double bodyMass) throws ReflectiveOperationException {
        Object thrust = field(computer, "navigationThrustSnapshot");
        if (!bool(field(thrust, "available")) || bool(field(thrust, "directForce"))
                || field(thrust, "vehicle") != vehicle || number(field(thrust,"topologyEpoch")) != topology
                || number(field(thrust,"sessionEpoch")) != number(field(computer,"navigationSessionEpoch"))
                || Math.abs(decimal(field(thrust,"mass"))-bodyMass) > Math.max(.001,bodyMass*1e-5)) return false;
        Object raw = field(computer, "linkedThrusters");
        if (!(raw instanceof List<?> entries) || entries.isEmpty() || entries.size() > 128) return false;
        boolean powered = false;
        for (Object entry : List.copyOf(entries)) {
            BlockPos relative = (BlockPos) field(entry,"relativePos");
            BlockEntity thruster = loaded(level, computer.getBlockPos().offset(relative));
            if (thruster == null || !thrusterType.isInstance(thruster) || containing.invoke(helper,thruster) != body
                    || !bool(linked.invoke(thruster)) || !bool(linkValid.invoke(thruster))
                    || !computer.getBlockPos().equals(thrusterComputer.invoke(thruster))) return false;
            double capacity = decimal(maxThrust.invoke(thruster));
            if (!Double.isFinite(capacity) || capacity < 0) return false;
            // A normal native PD/allocator response can temporarily request no thrust after an
            // assist impulse. Check real powered capability, independently of that command.
            powered |= NativeTakeoffAssistEvidence.powered(bool(active.invoke(thruster)), capacity);
        }
        return powered;
    }

    private static boolean loadedIdentity(ServerLevel level, BlockEntity block) { return loaded(level,block.getBlockPos()) == block; }
    private static BlockEntity loaded(ServerLevel level, BlockPos pos) {
        if (pos == null || !level.hasChunkAt(pos) || level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4) == null) return null;
        BlockEntity block = level.getBlockEntity(pos);
        return block == null || block.isRemoved() ? null : block;
    }
    static Object field(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) throw new IllegalStateException("native_field_owner_missing:"+name);
        Field cached = FIELDS.get(owner.getClass()).get(name);
        if (cached == null) {
            for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                try { cached = type.getDeclaredField(name); cached.setAccessible(true); break; }
                catch (NoSuchFieldException absent) { }
            }
            if (cached == null) throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
            FIELDS.get(owner.getClass()).put(name,cached);
        }
        return cached.get(owner);
    }
    private static Object call(Object owner, String method) throws ReflectiveOperationException {
        Method accessor = owner.getClass().getMethod(method);
        // Registrar configuration records are intentionally package-private. Their generated
        // public accessors still need declaring-class access when read from this adapter.
        if (!accessor.canAccess(owner) && !accessor.trySetAccessible())
            throw new IllegalAccessException("accessor_not_accessible:"+owner.getClass().getName()+"."+method);
        return accessor.invoke(owner);
    }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static double decimal(Object value) { return ((Number)value).doubleValue(); }
    private static String name(Object value) { return value instanceof Enum<?> e ? e.name() : ""; }
    private static String fingerprint(Object value) {
        return value instanceof Optional<?> optional ? optional.map(Object::toString).orElse("") : "";
    }
}
