package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.FlightPitchScope;
import com.monpai.sailboatmod.transport.air.PitchBootstrapFlightPhase;
import com.monpai.sailboatmod.transport.air.TakeoffAssistSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Restore native measured-AoA bootstrap in one owned, airborne direct-route invocation. */
public final class NativeFixedWingPitchBootstrap {
    private NativeFixedWingPitchBootstrap() { }
    public interface Scope extends AutoCloseable { @Override void close(); }
    public record Diagnostics(long bootstrapCalls, String reason, UUID body, UUID task, String profile,
                              double lastNativePhysicsTime, double lastForwardSpeed,
                              double lastMeasuredCompensation, double lastNativeCompensation,
                              double lastReturnedCompensation, double lastWeight, double lastVr) { }
    private static final long LEASE_NANOS = 400_000_000L;
    private static final Map<BlockEntity, Binding> BINDINGS = new IdentityHashMap<>();
    private static final FlightPitchScope<Context> CONTEXT = new FlightPitchScope<>();
    private static boolean listening;
    private static final class Binding {
        NativeTakeoffAssistReflection.Binding nativeBinding;
        NativeTakeoffAssistReflection access;
        long renewed, calls;
        UUID body, task;
        String profile = "";
        String reason = "waiting_cruise_scope";
        double physicsTime = -1;
        double forwardSpeed = Double.NaN, measuredCompensation = Double.NaN, nativeCompensation = Double.NaN,
                returnedCompensation = Double.NaN, weight = Double.NaN, vr = Double.NaN;
    }
    private record Context(Binding binding, Object follower,
                           NativeTakeoffAssistReflection.Observation frame,
                           Object vehicle, long sampledNanos, long opened) { }
    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<>() {
        @Override protected Map<String, Method> computeValue(Class<?> type) {
            return new java.util.concurrent.ConcurrentHashMap<>();
        }
    };
    public static synchronized void update(BlockEntity requester, UUID registration,
            BlockEntity terminal, List<BlockPos> wheels, boolean eligible) {
        if (!(requester.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clear(event.getServer()));
            listening = true;
        }
        BINDINGS.entrySet().removeIf(entry -> entry.getKey().isRemoved()
                || entry.getKey().getLevel() == null || System.nanoTime()-entry.getValue().renewed > 30_000_000_000L);
        if (!PitchBootstrapCompatibility.isApplied() || !eligible || terminal == null
                || terminal.isRemoved() || terminal.getLevel() != level || registration == null
                || wheels == null || wheels.size() < 3 || wheels.size() > 32) {
            BINDINGS.remove(requester); return;
        }
        Binding binding = BINDINGS.get(requester);
        var next = new NativeTakeoffAssistReflection.Binding(requester, registration, terminal, List.copyOf(wheels));
        if (binding == null || !next.equals(binding.nativeBinding)) {
            if (binding == null && BINDINGS.size() >= 64) return;
            binding = new Binding(); binding.nativeBinding = next; BINDINGS.put(requester, binding);
        }
        binding.renewed = System.nanoTime();
    }
    private static synchronized void clear(Object server) {
        BINDINGS.entrySet().removeIf(entry -> entry.getKey().getLevel() instanceof ServerLevel level
                && level.getServer() == server);
    }
    /** Always enter a scope, including null: an ineligible nested invocation shadows the outer one. */
    public static Scope enter(BlockEntity terminal, Object candidateFollower, Object vehicle) {
        Context context = authorize(terminal, candidateFollower, vehicle);
        var token = CONTEXT.enter(context);
        return token::close;
    }
    private static synchronized Context authorize(BlockEntity terminal, Object follower, Object vehicle) {
        if (!PitchBootstrapCompatibility.isApplied() || terminal == null || terminal.isRemoved()
                || !(terminal.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        long now = System.nanoTime();
        Context found = null;
        for (Binding binding : BINDINGS.values()) {
            if (binding.nativeBinding.terminal() != terminal || now-binding.renewed < 0
                    || now-binding.renewed > LEASE_NANOS) continue;
            try {
                if (!ordinaryCruise(follower)) { binding.reason = "not_ordinary_cruise"; continue; }
                if (binding.access == null) binding.access = new NativeTakeoffAssistReflection(terminal.getClass().getClassLoader());
                NativeTakeoffAssistReflection.Observation[] observed = {null};
                binding.access.observe(binding.nativeBinding, null, 0, state -> observed[0] = state);
                var frame = observed[0];
                long sampled = ((Number)NativeTakeoffAssistReflection.field(vehicle, "sampledNanos")).longValue();
                if (frame == null || !frame.fresh() || !frame.authority() || frame.takeoff()
                        || !frame.propulsion() || !frame.brakes() || !"RUNNING".equals(frame.phase())
                        || frame.task() == null || frame.contact() != TakeoffAssistSession.Contact.AIRBORNE
                        || frame.positiveWheels() != 0
                        || !freshScope(now, sampled, System.nanoTime(), vehicle, currentVehicle(terminal))
                        || !Objects.equals(frame.body(), NativeTakeoffAssistReflection.field(vehicle, "structureId"))
                        || Double.compare(frame.physicsTime(), ((Number)NativeTakeoffAssistReflection.field(vehicle, "physicsTimeSeconds")).doubleValue()) != 0)
                    { binding.reason = "native_frame_not_eligible"; continue; }
                if (found != null) return null;
                binding.reason = "authorized_cruise_scope";
                found = new Context(binding, follower, frame, vehicle, sampled, now);
            } catch (Exception | LinkageError unavailable) {
                binding.reason = "unavailable:"+unavailable.getClass().getSimpleName()+":"+unavailable.getMessage();
                return null;
            }
        }
        return found;
    }
    /** Blend the native unheld-zero store with its own earlier measured AoA, never a synthesized bias. */
    public static double nativeCompensation(Object settings, double nativeValue, double measured,
                                            double actualForwardSpeed, double minimumCompensation) {
        if (!PitchBootstrapCompatibility.isApplied()) return nativeValue;
        Context context = CONTEXT.current();
        if (context == null) return nativeValue;
        try {
            Binding binding = context.binding();
            BlockEntity terminal = binding.nativeBinding.terminal();
            if (!(terminal.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                    || !freshScope(context.opened(), context.sampledNanos(), System.nanoTime(), context.vehicle(), currentVehicle(terminal))
                    || !ordinaryCruise(context.follower()) || accessor(settings, "directOrbit") != null
                    || accessor(settings, "demand") == null || !Double.isFinite(minimumCompensation)
                    || minimumCompensation >= 0
                    || Boolean.TRUE.equals(accessor(accessor(settings, "pitchTrim"), "held"))) return nativeValue;
            var frame = context.frame();
            Object performance = accessor(settings, "performance");
            double vr = ((Number)accessor(performance, "vr")).doubleValue();
            double v1 = ((Number)accessor(performance, "v1")).doubleValue();
            // This is the native feedback method's real forward-speed argument, not horizontal speed.
            double result = compensation(nativeValue, measured, actualForwardSpeed, v1, vr, false);
            if (Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(nativeValue)
                    || measured < minimumCompensation || measured > Math.toRadians(8)
                    || !FlightControlBridge.inspectControlOwnership(level, terminal.getBlockPos())
                        .provesFlight(frame.body(), frame.task(), frame.profile())) return nativeValue;
            synchronized (NativeFixedWingPitchBootstrap.class) {
                Object current = currentVehicle(terminal);
                long now = System.nanoTime(), age = now-binding.renewed;
                if (BINDINGS.get(binding.nativeBinding.requester()) != binding
                        || age < 0 || age > LEASE_NANOS
                        || !freshScope(context.opened(), context.sampledNanos(), now, context.vehicle(), current)) return nativeValue;
                binding.calls++; binding.body = frame.body(); binding.task = frame.task();
                binding.reason = "measured_pitch_bootstrap";
                binding.profile = frame.profile(); binding.physicsTime = frame.physicsTime();
                binding.forwardSpeed = actualForwardSpeed; binding.measuredCompensation = measured;
                binding.nativeCompensation = nativeValue; binding.returnedCompensation = result;
                binding.weight = compensationWeight(actualForwardSpeed, v1, vr); binding.vr = vr;
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return nativeValue; }
    }
    private static boolean ordinaryCruise(Object follower) throws ReflectiveOperationException {
        if (follower == null || !"ace.flight.navigation.NavigationRouteFollower".equals(follower.getClass().getName())) return false;
        var phase = new PitchBootstrapFlightPhase(
                Boolean.TRUE.equals(NativeTakeoffAssistReflection.field(follower, "fixedWingCruiseEntered")),
                Boolean.TRUE.equals(NativeTakeoffAssistReflection.field(follower, "fixedWingTakeoffClimbing")),
                NativeTakeoffAssistReflection.field(follower, "fixedWingGroundRoll") != null,
                NativeTakeoffAssistReflection.field(follower, "fixedWingLanding") != null,
                NativeTakeoffAssistReflection.field(follower, "fixedWingFollowPlan") != null,
                Boolean.TRUE.equals(NativeTakeoffAssistReflection.field(follower, "holding")),
                NativeTakeoffAssistReflection.field(follower, "fixedWingOrbitTransition") != null);
        if (!phase.allowsMeasuredPitch()) return false;
        if (Boolean.TRUE.equals(NativeTakeoffAssistReflection.field(follower, "fixedWingAirspeedLimited"))
                || Boolean.TRUE.equals(NativeTakeoffAssistReflection.field(follower, "fixedWingSpeedRecovering"))) return false;
        Object route = NativeTakeoffAssistReflection.field(follower, "route");
        Object raw = accessor(route, "paths");
        int index = ((Number)NativeTakeoffAssistReflection.field(follower, "waypointIndex")).intValue();
        if (!(raw instanceof List<?> paths) || index < 0 || index >= paths.size()) return false;
        if ("APPROACH_LANDING".equals(String.valueOf(accessor(route, "endBehavior"))) && index >= paths.size()-2) return false;
        Object preset = accessor(paths.get(index), "flightPreset");
        return preset != null && "ace.flight.navigation.NavigationProfileDefinition$DirectFlightPreset".equals(preset.getClass().getName());
    }
    static double compensation(double nativeValue, double measured, double forwardSpeed,
                               double v1, double vr, boolean held) {
        if (held || nativeValue != 0 || !Double.isFinite(nativeValue) || !Double.isFinite(measured)) return nativeValue;
        double weight = compensationWeight(forwardSpeed, v1, vr);
        if (weight == 0) return nativeValue;
        if (weight == 1) return measured;
        return nativeValue + (measured-nativeValue)*weight;
    }
    private static double compensationWeight(double forwardSpeed, double v1, double vr) {
        if (!Double.isFinite(forwardSpeed) || !Double.isFinite(v1) || !Double.isFinite(vr)
                || v1 <= 0 || vr <= v1) return 0;
        double lower = Math.max(v1, .95 * vr);
        if (forwardSpeed <= lower) return 0;
        if (forwardSpeed >= vr) return 1;
        double fraction = (forwardSpeed-lower)/(vr-lower);
        return fraction*fraction*(3-2*fraction);
    }
    static boolean freshScope(long opened, long sampled, long now) {
        return now-opened >= 0 && now-opened <= NativeTakeoffAssistReflection.MAX_FRAME_AGE
                && now-sampled >= 0 && now-sampled <= NativeTakeoffAssistReflection.MAX_FRAME_AGE;
    }
    static boolean freshScope(long opened, long sampled, long now, Object expected, Object current) {
        return expected != null && expected == current && freshScope(opened, sampled, now);
    }
    private static Object currentVehicle(BlockEntity terminal) throws ReflectiveOperationException {
        if (!(terminal.getLevel() instanceof ServerLevel level)) return null;
        Object position = accessor(terminal, "getLinkedComputerPos");
        if (!(position instanceof BlockPos pos) || !level.hasChunkAt(pos)) return null;
        BlockEntity computer = level.getBlockEntity(pos);
        if (computer == null || computer.isRemoved()) return null;
        // FC's getter and Terminal's ExecutionContext retain this immutable snapshot object.
        // A replacement cannot borrow this invocation even when its body/time values are equal.
        return NativeTakeoffAssistReflection.field(computer, "navigationVehicleStateSnapshot");
    }
    private static Object accessor(Object target, String name) {
        if (target == null) throw new IllegalStateException("Missing native accessor owner: "+name);
        try {
            Method method = METHODS.get(target.getClass()).get(name);
            if (method == null) {
                method = target.getClass().getMethod(name);
                if (!method.canAccess(target) && !method.trySetAccessible()) throw new IllegalAccessException(name);
                METHODS.get(target.getClass()).put(name, method);
            }
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invoked ? invoked.getCause() : failure;
            throw new IllegalStateException("Native accessor unavailable: "+name, cause);
        }
    }
    public static synchronized Diagnostics diagnostics(BlockEntity requester) {
        Binding binding = BINDINGS.get(requester);
        // Scalars describe only the last changed, fully authorized native call, identified by its
        // physics time. A later rejected/held call must not look like a new measured sample.
        return binding == null ? new Diagnostics(0, "not_bound", null, null, "", -1,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
                : new Diagnostics(binding.calls, binding.reason, binding.body, binding.task, binding.profile, binding.physicsTime,
                binding.forwardSpeed, binding.measuredCompensation, binding.nativeCompensation,
                binding.returnedCompensation, binding.weight, binding.vr);
    }
}
