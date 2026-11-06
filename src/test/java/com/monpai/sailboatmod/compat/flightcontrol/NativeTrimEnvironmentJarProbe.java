package com.monpai.sailboatmod.compat.flightcontrol;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Standalone real-binary contract: javac/java need no Minecraft, NeoForge, native runtime or JUnit. */
public final class NativeTrimEnvironmentJarProbe {
    private static final String NATIVE_SHA = "86691e6965bb9c3e54ef71001a4ebea798e1ccc9b30ccdb731538933f1379208";
    private static final long STAMP = 1_000_000_000L;
    private static final UUID BODY = UUID.fromString("0bf1d134-7a6b-45d9-825e-4a569c9addce");
    private static final double PITCH = Math.toRadians(.5);
    private static final String NAV = "ace.flight.navigation.";
    private static final String PHYSICS = "ace.flight.physics.";
    private final ClassLoader loader;
    private int checks;

    private NativeTrimEnvironmentJarProbe(ClassLoader loader) { this.loader = loader; }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: NativeTrimEnvironmentJarProbe nativeJar jomlJar [--expect-learning-without-environment]");
        run(Path.of(args[0]), Path.of(args[1]), args.length > 2);
    }

    public static void run(Path nativeJar, Path jomlJar) throws Exception { run(nativeJar, jomlJar, false); }

    private static void run(Path nativeJar, Path jomlJar, boolean baselineRed) throws Exception {
        require(Files.isRegularFile(nativeJar), "Explicit nativeJar is not a regular file: " + nativeJar);
        require(Files.isRegularFile(jomlJar), "JOML jar is not a regular file: " + jomlJar);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(nativeJar)));
        require(NATIVE_SHA.equals(sha), "Native jar SHA mismatch: " + sha);
        try (var loader = new URLClassLoader(new java.net.URL[]{nativeJar.toUri().toURL(), jomlJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            var probe = new NativeTrimEnvironmentJarProbe(loader);
            System.out.println("Native FC SHA256=" + sha + "; isolated platform-parent classloader; no game runtime");
            if (baselineRed) {
                Object bias = probe.learn(probe.settings(false, "stable", 0), 25, 0, 0, 0, PITCH);
                require(probe.bool(bias, "held"), "RED: native learner cannot hold after 2.5s when environment remains UNKNOWN");
            } else {
                probe.contract();
                System.out.println("NativeTrimEnvironmentJarProbe GREEN: " + probe.checks + " real-native assertions");
            }
        }
    }

    private void contract() throws Exception {
        Object unknown = settings(false, "stable", 0);
        Object known = settings(true, "stable", 0);
        check(!bool(invoke(known, "axial"), "available"), "environment must not certify axial authority");
        check(bool(invoke(invoke(known, "axial"), "environment"), "available"), "known native environment reaches settings");
        check(!bool(learn(unknown, 25, 0, 0, 0, PITCH), "held"), "UNKNOWN never accumulates the learning window");

        Object learner = make(NAV + "FixedWingPitchTrim");
        for (int i = 0; i <= 19; i++) observe(learner, known, vehicle(i / 10.0, 25, 0, 0), PITCH, 0);
        check(!bool(invoke(learner, "bias"), "held"), "1.9 native seconds is insufficient");
        Object held = observe(learner, known, vehicle(2.0, 25, 0, 0), PITCH, 0);
        check(bool(held, "held") && bool(held, "verified"), "2.0 steady native seconds learns");
        check(Math.abs(number(held, "radians") - PITCH) < 1e-12, "learned AoA is actual .5-degree nose minus level flight path");
        Object repeated = observe(learner, known, vehicle(2.0, 25, 0, 0), PITCH, 0);
        check(repeated == held, "repeated native time must not advance the window");
        Object changed = observe(learner, settings(true, "changed", 0), vehicle(2.1, 25, 0, 0), PITCH, 0);
        check(bool(changed, "held") && !bool(changed, "verified"), "native settings change retains held but clears verified");
        check(Double.doubleToLongBits(number(changed, "radians")) == Double.doubleToLongBits(number(held, "radians")), "environment invalidation preserves native held radians");
        Object missing = observe(learner, unknown, vehicle(2.2, 25, 0, 0), PITCH, 0);
        check(bool(missing, "held") && !bool(missing, "verified"), "native UNKNOWN also preserves held and clears verified");

        Object windLearner = make(NAV + "FixedWingPitchTrim");
        for (int i = 0; i <= 20; i++) observe(windLearner, known, vehicle(i / 10.0, 25, 0, 0), PITCH, 0);
        Object windChanged = observe(windLearner, settings(true, "stable", .100001), vehicle(2.1, 25, 0, 0), PITCH, 0);
        check(bool(windChanged, "held") && !bool(windChanged, "verified"), "wind distance greater than .1 resets verification");

        check(!bool(learn(known, 23.9, 0, 0, 0, PITCH), "held"), "speed below VR24 blocks learning");
        check(!bool(learn(known, 25, .301, 0, 0, PITCH), "held"), "vertical speed above .3 blocks learning");
        check(!bool(learn(known, 25, 0, .030001, 0, PITCH), "held"), "angular norm above .03 blocks learning");
        check(!bool(learn(known, 25, 0, 0, Math.toRadians(5.001), PITCH), "held"), "commanded bank above 5 degrees blocks learning");
        check(!bool(learn(settings(true, "stable", 0, Math.toRadians(5.001)), 25, 0, 0, 0, PITCH), "held"), "measured bank above 5 degrees blocks learning");
        check(!bool(learn(known, 25, 0, 0, 0, Math.toRadians(1.001)), "held"), "previous path pitch above 1 degree blocks learning");
        for (boolean speedChange : List.of(false, true)) {
            Object unstable = make(NAV + "FixedWingPitchTrim");
            for (int i = 0; i <= 25; i++) observe(unstable, known,
                    vehicle(i / 10.0, speedChange ? 25 + (i % 2) * .1 : 25, speedChange ? 0 : (i % 2) * .04, 0), PITCH, 0);
            check(!bool(invoke(unstable, "bias"), "held"), speedChange ? "speed acceleration above .5 blocks learning" : "vertical acceleration above .3 blocks learning");
        }
        sampleContract(invoke(known, "axial"));
        contextContract();
    }

    private void sampleContract(Object limits) throws Exception {
        Object snapshot = vehicle(3, 25, 0, 0);
        Object sample = make(NAV + "FixedWingAxialBudget$Sample", snapshot, 4L, 5L, limits, 6L);
        Object read = invoke(sample, "forRead", snapshot, 4L, 5L, STAMP, 6L);
        check(!bool(read, "available") && bool(invoke(read, "environment"), "available"), "Sample keeps axial=false/environment=true");
        check(invoke(read, "source") == snapshot, "native Sample binds limits.source to exact snapshot");
        check(read == invoke(sample, "limits"), "successful native read returns its original limits");
        check(read == invoke(sample, "forRead", snapshot, 4L, 5L, STAMP + 250_000_000L, 6L), "250ms boundary remains valid");
        unavailable(invoke(sample, "forRead", snapshot, 4L, 5L, STAMP + 250_000_001L, 6L), "250ms+1ns expires");
        unavailable(invoke(sample, "forRead", vehicle(3, 25, 0, 0), 4L, 5L, STAMP, 6L), "equal-valued different snapshot object fails");
        unavailable(invoke(sample, "forRead", snapshot, 7L, 5L, STAMP, 6L), "topology mismatch fails");
        unavailable(invoke(sample, "forRead", snapshot, 4L, 7L, STAMP, 6L), "session mismatch fails");
        unavailable(invoke(sample, "forRead", snapshot, 4L, 5L, STAMP, 7L), "demand revision mismatch fails");
        unavailable(invoke(sample, "forRead", snapshot, 4L, 5L, STAMP - 1, 6L), "future snapshot timestamp fails");

        Object original = invoke(make(NAV + "FixedWingAxialBudget$Limits", false, 3.25, 2.5, 1.2), "withBrakingStatus", "native-status");
        Object supplemented = invoke(original, "withEnvironment", invoke(limits, "environment"));
        for (String name : List.of("available", "acceleration", "braking", "responseSeconds", "source", "brakingStatus"))
            check(java.util.Objects.equals(invoke(original, name), invoke(supplemented, name)), "withEnvironment preserves " + name);
    }

    private void contextContract() throws Exception {
        Object axis = make(PHYSICS + "FixedWingVelocityResponse$Axis", true, 2.0, .5);
        Object budget = make(PHYSICS + "FixedWingVelocityResponse$Budget", axis, axis, axis);
        var values = new ArrayList<>(List.of(budget));
        Constructor<?> ctor = type("ace.flight.block.FlightControlComputerBlockEntity$AxialResponseContext").getDeclaredConstructor(Object.class, List.class);
        check(ctor.trySetAccessible(), "native context constructor is accessible");
        Object first = ctor.newInstance("native-settings", values);
        Object second = ctor.newInstance("native-settings", List.of(budget));
        values.clear();
        check(first.equals(second), "context snapshots exact ordered budget values");
        check(((List<?>)invoke(first, "velocity")).size() == 1, "context copied caller list");
        Object changedAxis = make(PHYSICS + "FixedWingVelocityResponse$Axis", true, 3.0, .5);
        Object changedBudget = make(PHYSICS + "FixedWingVelocityResponse$Budget", changedAxis, axis, axis);
        check(!first.equals(ctor.newInstance("native-settings", List.of(changedBudget))), "velocity response configuration participates in environment key");
    }

    private void unavailable(Object limits, String message) throws Exception {
        check(limits == type(NAV + "FixedWingAxialBudget$Limits").getField("UNAVAILABLE").get(null)
                && !bool(invoke(limits, "environment"), "available"), message);
    }
    private Object learn(Object settings, double speed, double vy, double omega, double bank, double previousPitch) throws Exception {
        Object learner = make(NAV + "FixedWingPitchTrim");
        Object bias = null;
        for (int i = 0; i <= 25; i++) bias = observe(learner, settings, vehicle(i / 10.0, speed, vy, omega), previousPitch, bank);
        return bias;
    }
    private Object observe(Object learner, Object settings, Object vehicle, double previousPitch, double bank) throws Exception {
        return invoke(learner, "observe", vehicle, make("org.joml.Vector3d", -1.0, 0.0, 0.0), settings, previousPitch, bank);
    }
    private Object settings(boolean known, Object key, double wind) throws Exception { return settings(known, key, wind, 0); }
    private Object settings(boolean known, Object key, double wind, double measuredBank) throws Exception {
        Object performance = make(PHYSICS + "FixedWingPerformance$Estimate", type(PHYSICS + "FixedWingPerformance$Status").getField("READY").get(null), 20.0, 24.0, .6);
        Object settings = make(NAV + "FixedWingGuidance$Settings", performance, 11.0, 15.0);
        settings = invoke(settings, "withDemand", type(NAV + "FixedWingAxialDemand").getField("DEFAULT").get(null));
        settings = invoke(settings, "withExecution", make(NAV + "FixedWingExecutionBudget$Limits", true, 1.0, .1, .4, measuredBank));
        Object limits = type(NAV + "FixedWingAxialBudget$Limits").getField("UNAVAILABLE").get(null);
        if (known) limits = invoke(limits, "withEnvironment", make(NAV + "FixedWingPitchTrim$Environment", true, wind, 0.0, 0.0, key));
        return invoke(settings, "withAxial", limits);
    }
    private Object vehicle(double time, double speed, double vy, double omega) throws Exception {
        return make(NAV + "NavigationVehicleStateSnapshot", type(NAV + "NavigationVehicleStateSnapshot$Status").getField("AVAILABLE").get(null),
                "", "minecraft:overworld", BODY, 0.0, 100.0, 0.0,
                0.0, 0.0, -Math.sin(PITCH / 2), Math.cos(PITCH / 2), -speed, vy, 0.0,
                -speed * Math.cos(PITCH) - vy * Math.sin(PITCH), -speed * Math.sin(PITCH) + vy * Math.cos(PITCH), 0.0,
                omega, 0.0, 0.0, STAMP, time);
    }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private Object make(String name, Object... values) throws Exception {
        for (Constructor<?> ctor : type(name).getConstructors()) if (matches(ctor.getParameterTypes(), values)) return ctor.newInstance(values);
        throw new NoSuchMethodException(name + " constructor with " + values.length + " arguments");
    }
    private static Object invoke(Object owner, String name, Object... values) throws Exception {
        for (Method method : owner.getClass().getMethods()) {
            if (!method.getName().equals(name) || !matches(method.getParameterTypes(), values)) continue;
            if (!method.canAccess(owner) && !method.trySetAccessible()) throw new IllegalAccessException(name);
            return method.invoke(owner, values);
        }
        throw new NoSuchMethodException(owner.getClass().getName() + "." + name);
    }
    private static boolean matches(Class<?>[] parameters, Object[] values) {
        if (parameters.length != values.length) return false;
        for (int i = 0; i < values.length; i++) {
            Class<?> p = parameters[i];
            Class<?> boxed = p == boolean.class ? Boolean.class : p == double.class ? Double.class : p == long.class ? Long.class : p == int.class ? Integer.class : p;
            if (values[i] == null ? p.isPrimitive() : !boxed.isInstance(values[i])) return false;
        }
        return true;
    }
    private boolean bool(Object owner, String method) throws Exception { return Boolean.TRUE.equals(invoke(owner, method)); }
    private double number(Object owner, String method) throws Exception { return ((Number)invoke(owner, method)).doubleValue(); }
    private void check(boolean condition, String description) { require(condition, description); checks++; System.out.println("PASS " + description); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
