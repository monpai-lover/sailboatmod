package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.FlightPitchScope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Supplies only missing observation metadata. Never invokes a solver or writes native control state. */
public final class NativeFixedWingTrimEnvironment {
    public interface Scope extends AutoCloseable { @Override void close(); }
    interface Access {
        TrimEnvironmentFrame.State read(Object computer, Object body, Object handle) throws ReflectiveOperationException;
        boolean known(Object limits) throws ReflectiveOperationException;
        default String reason() { return "environment_unavailable"; }
        Object supplement(Object limits, Object model, List<Object> budgets, TrimEnvironmentFrame.State state)
                throws ReflectiveOperationException;
        default Object supplement(Object limits, Object model, List<Object> budgets, TrimEnvironmentFrame.State state,
                                  TrimEnvironmentFrame.State before) throws ReflectiveOperationException {
            return supplement(limits, model, budgets, state);
        }
    }
    private static final FlightPitchScope<Capture> SCOPES = new FlightPitchScope<>();
    public record Diagnostics(long prepared, long attempted, long supplemented, String reason,
                              Object bodyId, double physicsTime) { }
    private record DiagnosticSlot(WeakReference<Object> computer, Diagnostics value) { }
    private static final List<DiagnosticSlot> DIAGNOSTICS = new ArrayList<>();
    /** Exact object identity only. This snapshot is never used to authorize native publication. */
    public static Diagnostics diagnostics(Object computer) {
        synchronized (DIAGNOSTICS) {
            for (var slot : DIAGNOSTICS) if (computer != null && slot.computer().get() == computer) return slot.value();
            return null;
        }
    }
    private static void observe(Object computer, TrimEnvironmentFrame.State state, String reason, int count) {
        if (computer == null) return;
        synchronized (DIAGNOSTICS) {
            DIAGNOSTICS.removeIf(slot -> slot.computer().get() == null);
            int found = -1;
            for (int i = 0; i < DIAGNOSTICS.size(); i++) if (DIAGNOSTICS.get(i).computer().get() == computer) { found = i; break; }
            Diagnostics previous = found < 0 ? new Diagnostics(0, 0, 0, "", null, Double.NaN) : DIAGNOSTICS.get(found).value();
            var next = new Diagnostics(previous.prepared() + (count == 1 ? 1 : 0), previous.attempted() + (count == 2 ? 1 : 0),
                    previous.supplemented() + (count == 3 ? 1 : 0), reason,
                    state == null ? null : state.bodyId(), state == null ? Double.NaN : state.physicsTime());
            var slot = new DiagnosticSlot(new WeakReference<>(computer), next);
            if (found >= 0) DIAGNOSTICS.set(found, slot);
            else { if (DIAGNOSTICS.size() == 128) DIAGNOSTICS.removeFirst(); DIAGNOSTICS.add(slot); }
        }
    }
    private static final class Capture {
        final Object computer;
        TrimEnvironmentFrame.Entry entry;
        Access access;
        final List<Object> budgets = new ArrayList<>();
        boolean failed;
        Capture(Object computer) { this.computer = computer; }
        void reject() { failed = true; budgets.clear(); }
        void reject(String reason, TrimEnvironmentFrame.State state) { observe(computer, state, reason, 0); reject(); }
    }
    private NativeFixedWingTrimEnvironment() { }

    public static Scope enter(Object computer, Object body, Object handle, double dt) {
        return enter(computer, body, handle, dt, computer == null ? null
                : new ReflectionAccess(computer.getClass().getClassLoader()), TrimEnvironmentCompatibility.isApplied());
    }
    static Scope enter(Object computer, Object body, Object handle, double dt, Access access, boolean enabled) {
        Capture capture = new Capture(computer);
        // Even rejected/nested calls shadow the outer frame; they must never borrow its evidence.
        if (enabled && SCOPES.current() == null && access != null && computer != null && body != null && handle != null) {
            try {
                capture.entry = new TrimEnvironmentFrame.Entry(computer, body, handle, access.read(computer, body, handle), dt);
                capture.access = access;
                observe(computer, capture.entry.before(), "prepared", 1);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { capture.reject("prepare_unavailable", null); }
        } else capture.reject(enabled ? "scope_rejected" : "compatibility_unapplied", null);
        var token = SCOPES.enter(capture);
        return () -> { token.close(); capture.reject(); };
    }

    /** The Mixin calls the original accepted-budget getter exactly once before this observer. */
    public static void captureBudget(Object computer, Object budget) {
        Capture capture = SCOPES.current();
        if (capture == null || capture.failed) return;
        if (capture.entry == null || capture.entry.computer() != computer || budget == null) capture.reject("budget_identity_rejected", null);
        else capture.budgets.add(budget);
    }

    /** ModifyArgs has no coerced native method arguments; use the enclosing operation's exact inputs. */
    public static Object supplement(Object computer, Object vehicle, long topology, long session, Object limits, long revision) {
        Capture capture = SCOPES.current();
        if (capture == null || capture.failed || capture.entry == null) return limits;
        var entry = capture.entry;
        return supplement(computer, entry.body(), entry.handle(), entry.dt(), vehicle, topology, session, limits, revision);
    }

    public static Object supplement(Object computer, Object body, Object handle, double dt, Object vehicle,
                                    long topology, long session, Object limits, long revision) {
        Capture capture = SCOPES.current();
        if (capture == null || capture.failed || limits == null) return limits;
        TrimEnvironmentFrame.State state = null;
        try {
            if (capture.entry == null || Double.compare(dt, capture.entry.dt()) != 0) { capture.reject("invocation_rejected", null); return limits; }
            if (capture.access.known(limits)) { capture.reject("native_environment_available", null); return limits; }
            state = capture.access.read(computer, body, handle);
            observe(capture.computer, state, "publication_attempted", 2);
            if (!TrimEnvironmentFrame.permits(capture.entry, computer, body, handle, state, vehicle, topology, session, revision)) {
                capture.reject("frame_guard_rejected", state); return limits;
            }
            Object result = capture.access.supplement(limits, state.model(), List.copyOf(capture.budgets), state, capture.entry.before());
            if (result != null && result != limits && capture.access.known(result))
                observe(capture.computer, state, capture.access.reason().equals("environment_unavailable") ? "environment_observed" : capture.access.reason(), 3);
            else observe(capture.computer, state, capture.access.reason(), 0);
            // One native Sample publication consumes this invocation's evidence.
            capture.reject();
            return result == null ? limits : result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            capture.reject("publication_unavailable", state); return limits;
        }
    }

    static final class ReflectionAccess implements Access {
        private final ClassLoader loader;
        private String reason = "environment_unavailable";
        ReflectionAccess(ClassLoader loader) { this.loader = loader; }
        @Override public String reason() { return reason; }
        @Override public TrimEnvironmentFrame.State read(Object computer, Object body, Object handle) throws ReflectiveOperationException {
            long now = System.nanoTime();
            Object vehicle = field(computer, "navigationVehicleStateSnapshot");
            Object model = field(computer, "postEngineFlapModel");
            long topology = integer(field(computer, "navigationTopologyEpoch"));
            long session = integer(field(computer, "navigationSessionEpoch"));
            Object request = field(computer, "axialRequest");
            Object execution = field(computer, "fixedWingExecutionBudget");
            Object thrust = field(computer, "navigationThrustSnapshot");
            Object executionVehicle = execution == null ? null : call(execution, "vehicle");
            boolean executionReady = execution != null && truth(call(call(execution, "forRead", vehicle, topology, session, now), "available"));
            boolean thrustReady = thrust != null && truth(call(thrust, "available"))
                    && truth(call(thrust, "matches", vehicle, topology, session, now));
            boolean active = truth(field(computer, "working")) && integer(field(computer, "mode")) == 0
                    && !truth(field(computer, "workDryRun")) && !truth(field(computer, "navigationModeRevocationPending"))
                    && truth(field(computer, "fixedWingNavigationAccepted")) && !truth(field(computer, "fixedWingGroundLowSpeed"))
                    && "FIXED_WING".equals(String.valueOf(field(computer, "physicsAirframeMode")));
            Object bodyId = callStatic(Class.forName("ace.flight.physics.PhysicsStructureIdentity", false, loader), "getUuid", body);
            return new TrimEnvironmentFrame.State(vehicle, model, bodyId, call(vehicle, "structureId"),
                    number(call(vehicle, "physicsTimeSeconds")), number(field(computer, "navigationPhysicsTimeSeconds")),
                    number(call(vehicle, "quaternionX")), number(call(vehicle, "quaternionY")),
                    number(call(vehicle, "quaternionZ")), number(call(vehicle, "quaternionW")),
                    topology, session, integer(call(request, "revision")), executionVehicle,
                    active && truth(call(vehicle, "available")), truth(call(vehicle, "isFreshAt", now, 250_000_000L)), thrustReady, executionReady,
                    DefaultTrimEnvironment.context(computer, body, model, loader));
        }
        @Override public boolean known(Object limits) throws ReflectiveOperationException {
            return truth(call(call(limits, "environment"), "available"));
        }
        @Override public Object supplement(Object limits, Object model, List<Object> budgets, TrimEnvironmentFrame.State state)
                throws ReflectiveOperationException {
            return supplement(limits, model, budgets, state, state);
        }
        @Override public Object supplement(Object limits, Object model, List<Object> budgets, TrimEnvironmentFrame.State state,
                                           TrimEnvironmentFrame.State before) throws ReflectiveOperationException {
            if (model.getClass() != Class.forName("ace.flight.physics.NativeBodyFlapModel", false, loader)) return limits;
            Object wind = call(model, "responseWind");
            Object environmentKey = call(model, "responseEnvironmentKey");
            if (wind == null && environmentKey == null) {
                try {
                    var observation = DefaultTrimEnvironment.observe(model, before, state, loader);
                    wind = observation.wind();
                    environmentKey = observation.key();
                    reason = "default_environment_observed";
                } catch (DefaultTrimEnvironment.Rejected rejected) {
                    reason = rejected.getMessage();
                    return limits;
                }
            } else {
                reason = "native_environment_observed";
            }
            if (wind == null || environmentKey == null) return limits;
            // responseWind returns a new vector. Match native code's quaternion.transform exactly.
            Object quaternion = Class.forName("org.joml.Quaterniond", false, loader)
                    .getConstructor(double.class, double.class, double.class, double.class)
                    .newInstance(state.qx(), state.qy(), state.qz(), state.qw());
            call(quaternion, "transform", wind);
            double x = number(call(wind, "x")), y = number(call(wind, "y")), z = number(call(wind, "z"));
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return limits;
            var contextConstructor = Class.forName("ace.flight.block.FlightControlComputerBlockEntity$AxialResponseContext", false, loader)
                    .getDeclaredConstructor(Object.class, List.class);
            if (!contextConstructor.trySetAccessible()) return limits;
            Object context = contextConstructor.newInstance(environmentKey, budgets);
            Object environment = Class.forName("ace.flight.navigation.FixedWingPitchTrim$Environment", false, loader)
                    .getConstructor(boolean.class, double.class, double.class, double.class, Object.class)
                    .newInstance(true, x, y, z, context);
            return call(limits, "withEnvironment", environment);
        }
    }
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        if (owner == null) throw new IllegalAccessException("Missing native field owner: " + name);
        Field field = FIELDS.get(owner.getClass()).get(name);
        if (field == null) {
            field = owner.getClass().getDeclaredField(name);
            if (!field.trySetAccessible()) throw new IllegalAccessException(name);
            FIELDS.get(owner.getClass()).put(name, field);
        }
        return field.get(owner);
    }
    private static Object call(Object owner, String name, Object... args) throws ReflectiveOperationException {
        if (owner == null) throw new IllegalAccessException("Missing native method owner: " + name);
        return invoke(owner.getClass(), owner, name, args);
    }
    private static Object callStatic(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
        return invoke(type, null, name, args);
    }
    private static final class NullArgument { }
    private record MethodKey(String name, List<Class<?>> arguments) { }
    /** Class metadata only: owners, argument instances and mutable getter results are never retained. */
    private static final ClassValue<Map<MethodKey, Method>> METHODS = new ClassValue<>() {
        @Override protected Map<MethodKey, Method> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    static Object invoke(Class<?> type, Object owner, String name, Object... args) throws ReflectiveOperationException {
        List<Class<?>> arguments;
        if (args.length == 0) arguments = List.of();
        else {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = args[i] == null ? NullArgument.class : args[i].getClass();
            arguments = List.of(types);
        }
        MethodKey key = new MethodKey(name, arguments);
        Map<MethodKey, Method> methods = METHODS.get(type);
        Method method = methods.get(key);
        if (method == null) {
            Method resolved = resolve(type, name, args);
            Method existing = methods.putIfAbsent(key, resolved);
            method = existing == null ? resolved : existing;
        }
        if (!method.canAccess(owner) && !method.trySetAccessible()) throw new IllegalAccessException(name);
        return method.invoke(owner, args);
    }
    private static Method resolve(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> parameter = method.getParameterTypes()[i];
                Class<?> boxed = parameter == long.class ? Long.class : parameter == double.class ? Double.class : parameter;
                if (args[i] == null ? parameter.isPrimitive() : !boxed.isInstance(args[i])) { matches = false; break; }
            }
            if (matches) return method;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static double number(Object value) { return ((Number)value).doubleValue(); }
    private static long integer(Object value) { return ((Number)value).longValue(); }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value); }
}
