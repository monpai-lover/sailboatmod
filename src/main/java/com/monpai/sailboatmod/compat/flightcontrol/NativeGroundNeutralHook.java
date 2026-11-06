package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Exact-native raw observation, then normal native output APIs. Never expires or acquires native authority. */
public final class NativeGroundNeutralHook {
    private static final long FRAME_AGE_NANOS = 250_000_000L;
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        protected Access computeValue(Class<?> type) {
            try { return new Access(type.getClassLoader()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
    };
    private NativeGroundNeutralHook() { }
    public static boolean neutralize(Object computer, Object actualBody, double dt,
            NativeGroundNeutral.NativeActions actions) {
        NativeGroundNeutral.recordHookSeen();
        var claim = NativeGroundNeutral.claim(computer);
        if (claim == null || !NativeGroundNeutralCompatibility.isApplied()) return false;
        boolean handled = false;
        try {
            Access api = ACCESS.get(computer.getClass());
            // The support gate uses bounded linked snapshots and existing BEs, never arbitrary IFCC callbacks.
            if (!NativeGroundNeutralSupport.supports(computer)) {
                NativeGroundNeutral.invalidate(claim.lease(), "unsupported_native_effectors");
                return false;
            }
            try (AutoCloseable scope = (AutoCloseable) invoke(api.readScope)) {
                Object broker = field(computer, "navigationLeaseBroker");
                if (broker == null) throw new IllegalStateException("native_broker_missing");
                synchronized (broker) {
                    var context = claim.context();
                    boolean sameBodies = sameBodies(api, computer, actualBody, context);
                    var observed = captureFields(computer, context, actualBody, System.nanoTime());
                    if (!sameBodies) observed = null;
                    handled = NativeGroundNeutral.process(claim.lease(), computer, observed, System.nanoTime(), dt, actions);
                }
            }
        } catch (Exception | LinkageError failure) {
            NativeGroundNeutral.invalidate(claim.lease(), "native_ground_observation_failed");
        }
        return handled;
    }
    private static boolean sameBodies(Access api, Object computer, Object actualBody,
            NativeGroundNeutral.Context context) throws ReflectiveOperationException {
        if (!(computer instanceof BlockEntity fcc) || !(context.terminal() instanceof BlockEntity terminal)
                || !(context.requester() instanceof RegisteredVehicleControllerBlockEntity requester)
                || fcc.isRemoved() || terminal.isRemoved() || requester.isRemoved() || fcc.getLevel() == null
                || fcc.getLevel() != terminal.getLevel() || fcc.getLevel() != requester.getLevel()
                || !Objects.equals(requester.getTransportUuid(), context.ownerRegistration())
                || actualBody != context.body()) return false;
        Object helper = api.sable.getField("HELPER").get(null);
        if (invoke(api.containing, helper, fcc) != actualBody
                || invoke(api.containing, helper, terminal) != actualBody
                || invoke(api.containing, helper, requester) != actualBody) return false;
        Object vehicle = field(computer, "navigationVehicleStateSnapshot");
        UUID bodyId=(UUID)invoke(api.bodyId, actualBody);
        return requester.nativeGroundHoldRegistrationMatches(context.ownerRegistration(),bodyId)
                && Objects.equals(bodyId, field(vehicle, "structureId"));
    }
    /** Reads only existing raw objects. Caller holds native read-fence then broker monitor. */
    static NativeGroundNeutral.PhysicsContext captureFields(Object computer, NativeGroundNeutral.Context expected,
            Object actualBody, long now) throws ReflectiveOperationException {
        Object broker = field(computer, "navigationLeaseBroker");
        Object frame = ((AtomicReference<?>) field(broker, "effectiveFrame")).get();
        Object terminal = expected.terminal(), task = field(terminal, "taskSnapshot");
        Object slot = field(terminal, "profileSlotSnapshot"), network = field(terminal, "controlNetworkSnapshot");
        Object vehicle = field(computer, "navigationVehicleStateSnapshot");
        String slotProfile = optionalFingerprint(field(slot, "fingerprint"));
        Object taskFingerprint = field(task, "profileFingerprint");
        String taskProfile = taskFingerprint == null ? "" : taskFingerprint.toString();
        long topology = number(field(computer, "navigationTopologyEpoch"));
        long session = number(field(computer, "navigationSessionEpoch"));
        long sampled = number(field(vehicle, "sampledNanos"));
        boolean normalWork = bool(field(computer, "working")) && number(field(computer, "mode")) == 0
                && !bool(field(computer, "workDryRun")) && !bool(field(computer, "debugApplyForces"))
                && !bool(field(computer, "navigationModeRevocationPending"));
        boolean released = empty(field(broker, "leasesByOwner")) && empty(field(broker, "claims"))
                && empty(field(frame, "commands")) && number(field(broker, "topologyEpoch")) == topology
                && number(field(frame, "topologyEpoch")) == topology
                && field(terminal, "activeIdentity") == null && field(terminal, "activeLeaseToken") == null
                && field(terminal, "activeRouteBinding") == null && field(terminal, "activeComputerPos") == null;
        String phase = name(field(task, "phase"));
        boolean idleTask = ("READY".equals(phase) || "COMPLETED".equals(phase)) && "NONE".equals(name(field(task, "blockReason")));
        boolean profile = "VALID".equals(name(field(slot, "status"))) && !slotProfile.isBlank()
                && expected.profile().equals(slotProfile) && slotProfile.equals(taskProfile)
                && number(field(slot, "slotGeneration")) == number(field(task, "slotGeneration"));
        if(expected.scope()==NativeGroundNeutral.Scope.HOLD) {
            // Stop-only HOLD can survive a no-task blocked route snapshot; it never submits or resumes that route.
            idleTask=idleTask||("BLOCKED".equals(phase)&&field(task,"taskId")==null
                    &&number(field(task,"commandRevision"))==-1);
            profile="VALID".equals(name(field(slot,"status")))&&!slotProfile.isBlank()
                    &&expected.profile().equals(slotProfile);
        }
        boolean fresh = "AVAILABLE".equals(name(field(vehicle, "status"))) && now - sampled >= 0
                && now - sampled <= FRAME_AGE_NANOS;
        boolean binding = "AVAILABLE".equals(name(field(network, "status")))
                && number(field(network, "topologyEpoch")) == topology
                && Objects.equals(field(network, "computerPosition"), field(computer, "worldPosition"));
        Object requester = expected.requester();
        Object groundMission = field(requester, "groundMission");
        boolean manual=bool(field(requester,"manualTakeover"));
        boolean owner;
        if(expected.scope()==NativeGroundNeutral.Scope.HOLD) {
            Object value=field(requester,"groundHoldProof");
            var proof=value instanceof NativeGroundNeutral.HoldProof p?p:null;
            Object parking=field(requester,"parkingBrake");
            boolean held=parking!=null&&bool(field(parking,"held"))&&!bool(field(parking,"released"));
            Object physical=field(vehicle,"structureId");
            boolean proofFresh=proof!=null&&proof.requester()==requester
                    &&proof.ownerRegistration().equals(expected.ownerRegistration())&&proof.mission()==expected.mission()
                    &&Objects.equals(proof.physicalId(),physical)&&proof.profile().equals(expected.profile())
                    &&now-proof.validatedAtNanos()>=0&&now-proof.validatedAtNanos()<=NativeGroundNeutral.LEASE_NANOS;
            boolean scope=proofFresh&&(proof.parking()?groundMission==null&&expected.mission()==parking
                    :bool(field(requester,"active"))&&groundMission!=null
                        &&field(groundMission,"plan")==expected.mission()
                        &&knownGroundPhase(name(field(field(groundMission,"state"),"phase"))));
            owner=!manual&&held&&Objects.equals(field(parking,"body"),physical)
                    &&!bool(field(requester,"groundOutputsOwned"))&&scope;
        } else {
            owner=expected.scope()==NativeGroundNeutral.Scope.DRIVE&&bool(field(requester,"active"))
                    &&!bool(field(requester,"paused"))&&!manual&&groundMission!=null
                    &&field(groundMission,"plan")==expected.mission();
        }
        return new NativeGroundNeutral.PhysicsContext(actualBody, profile ? slotProfile : "", topology, session,
                ((Number) field(vehicle, "physicsTimeSeconds")).doubleValue(), normalWork,
                released && idleTask, owner && fresh && binding);
    }
    private static boolean knownGroundPhase(String phase) {
        return switch(phase) {
            case "TAXI_OUT","HOLD_LINE","LINE_UP","TAXI_IN","STAND_STABILIZING","COMPLETE" -> true;
            default -> false;
        };
    }
    private static final class Access {
        final Class<?> sable;
        final MethodHandle readScope, containing, bodyId;
        Access(ClassLoader loader) throws ReflectiveOperationException {
            var lookup = MethodHandles.publicLookup();
            sable = Class.forName("dev.ryanhcode.sable.Sable", false, loader);
            Class<?> companion = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion", false, loader);
            Class<?> body = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel", false, loader);
            Class<?> fence = Class.forName("ace.flight.navigation.NavigationControlCommitFence", false, loader);
            Class<?> scope = Class.forName("ace.flight.navigation.NavigationControlCommitFence$Scope", false, loader);
            readScope = lookup.findStatic(fence, "readScope", MethodType.methodType(scope));
            containing = lookup.findVirtual(companion, "getContaining", MethodType.methodType(body, BlockEntity.class));
            bodyId = lookup.findVirtual(body, "getUniqueId", MethodType.methodType(UUID.class));
        }
    }
    private static Object invoke(MethodHandle method, Object... arguments) throws ReflectiveOperationException {
        try { return method.invokeWithArguments(arguments); }
        catch (RuntimeException failure) { throw failure; }
        catch (Error failure) { throw failure; }
        catch (Throwable failure) { throw new ReflectiveOperationException(failure); }
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        if (object == null) throw new IllegalStateException("native field owner missing: " + name);
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static boolean empty(Object value) { return value instanceof Map<?, ?> map && map.isEmpty(); }
    private static String name(Object value) { return value instanceof Enum<?> e ? e.name() : ""; }
    private static String optionalFingerprint(Object value) {
        return value instanceof Optional<?> optional ? optional.map(Object::toString).orElse("") : "";
    }
}
