package com.monpai.sailboatmod.compat.flightcontrol;

import org.joml.Vector3d;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Pure mathematical fallback used only by the pinned cruise-preview call site. */
public final class NativeCruiseTrimSearch {
    private static final String TRIM = "ace.flight.physics.FixedWingLevelTrim";
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) {
            try { return new Access(type); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported native trim layout", e); }
        }
    };
    private NativeCruiseTrimSearch() { }

    public static double[] controls(Object trim, double speed, Vector3d desiredTorque) {
        if (trim == null || !TRIM.equals(trim.getClass().getName())) return null;
        Access a = ACCESS.get(trim.getClass());
        // Native failures propagate. A successful native answer remains exactly as returned.
        double[] original = (double[]) invoke(a.controls, trim, speed, desiredTorque);
        if (original != null) return original;
        try { return refine(a, trim, speed, desiredTorque); }
        catch (ReflectiveOperationException | IllegalArgumentException | IllegalStateException unavailable) {
            return null;
        }
    }

    private static double[] refine(Access a, Object trim, double speed, Vector3d desiredTorque)
            throws ReflectiveOperationException {
        if (!Double.isFinite(speed) || speed <= 0 || desiredTorque == null || !desiredTorque.isFinite()) return null;
        var wings = (List<?>) a.wings.get(trim);
        var ranges = (List<?>) a.ranges.get(trim);
        Object flaps = a.flaps.get(trim), passive = a.passive.get(trim);
        // Integrated wing/sail coefficients have different domains; retain their native behavior.
        if (!wings.isEmpty() || ranges.isEmpty() || ranges.size() > 16
                || !((List<?>) invoke(a.sailControls, flaps)).isEmpty()) return null;
        double[] seed = (double[]) a.aerodynamicControls.get(trim);
        if (seed == null || seed.length != ranges.size()) return null;
        seed = seed.clone();
        double mass = a.mass.getDouble(trim);
        if (!Double.isFinite(mass) || mass <= 0) return null;
        double[] lower = new double[seed.length], upper = new double[seed.length];
        for (int i = 0; i < seed.length; i++) {
            lower[i] = Math.max(-30, (double) invoke(a.rangeMin, ranges.get(i)));
            upper[i] = Math.min(30, (double) invoke(a.rangeMax, ranges.get(i)));
        }
        Vector3d forward = new Vector3d((Vector3d) a.forward.get(trim));
        Vector3d torqueCommand = new Vector3d(desiredTorque);
        double[] maxima = ((double[]) a.maxima.get(trim)).clone();
        // Reuse the original required-force functions, including nonzero engine baselines.
        Vector3d requiredForce = new Vector3d((Vector3d) invoke(a.requiredForce, trim, speed));
        Vector3d requiredTorque = new Vector3d((Vector3d) invoke(a.requiredTorque, trim, torqueCommand));
        return BoundedTrimSearch.search(seed, lower, upper, angles -> {
            Object wrench = invoke(a.wrenchAt, null, angles, wings, ranges, flaps, forward, speed, passive);
            Vector3d force = new Vector3d(requiredForce).fma(-1 / mass, (Vector3d) invoke(a.wrenchForce, wrench));
            Vector3d torque = new Vector3d(requiredTorque).fma(-1 / mass, (Vector3d) invoke(a.wrenchTorque, wrench));
            if (!force.isFinite() || !torque.isFinite()) return null;
            Object allocated = invoke(a.allocate, trim, maxima.clone(), array(force), array(torque));
            double[] actualForce = (double[]) invoke(a.actualForce, allocated);
            double[] actualTorque = (double[]) invoke(a.actualTorque, allocated);
            if (actualForce.length != 3 || actualTorque.length != 3) return null;
            return new double[]{actualForce[0] - force.x, actualForce[1] - force.y, actualForce[2] - force.z,
                    actualTorque[0] - torque.x, actualTorque[1] - torque.y, actualTorque[2] - torque.z};
        }, angles -> (boolean) invoke(a.supports, trim, speed, angles, new Vector3d(torqueCommand)), 192);
    }

    private static double[] array(Vector3d v) { return new double[]{v.x, v.y, v.z}; }
    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("Native mathematical method failed", e.getCause());
        } catch (IllegalAccessException e) { throw new IllegalStateException("Native mathematical method inaccessible", e); }
    }

    private static final class Access {
        final Field wings, ranges, flaps, passive, aerodynamicControls, mass, forward, maxima;
        final Method controls, supports, requiredForce, requiredTorque, allocate, sailControls,
                rangeMin, rangeMax, wrenchAt, wrenchForce, wrenchTorque, actualForce, actualTorque;
        Access(Class<?> type) throws ReflectiveOperationException {
            if (!TRIM.equals(type.getName())) throw new IllegalArgumentException("Unexpected trim class");
            ClassLoader loader = type.getClassLoader();
            Class<?> performance = Class.forName("ace.flight.physics.FixedWingPerformance", false, loader);
            Class<?> flapType = Class.forName("ace.flight.physics.FixedWingPerformance$FlapModel", false, loader);
            Class<?> passiveType = Class.forName("ace.flight.physics.FixedWingPerformance$PassiveModel", false, loader);
            Class<?> rangeType = Class.forName("ace.flight.physics.FixedWingPerformance$AngleRange", false, loader);
            Class<?> wrenchType = Class.forName("ace.flight.physics.SymmetricSailFlapAllocation$Wrench", false, loader);
            Class<?> allocationType = Class.forName("ace.flight.physics.AllocationResult", false, loader);
            controls = type.getMethod("controls", double.class, Vector3d.class);
            supports = type.getMethod("supports", double.class, double[].class, Vector3d.class);
            requiredForce = privateMethod(type, "requiredForce", double.class);
            requiredTorque = privateMethod(type, "requiredTorque", Vector3d.class);
            allocate = privateMethod(type, "allocate", double[].class, double[].class, double[].class);
            sailControls = flapType.getMethod("sailControls");
            rangeMin = rangeType.getMethod("min"); rangeMax = rangeType.getMethod("max");
            wrenchAt = performance.getMethod("wrenchAt", double[].class, List.class, List.class,
                    flapType, Vector3d.class, double.class, passiveType);
            wrenchForce = wrenchType.getMethod("force"); wrenchTorque = wrenchType.getMethod("torque");
            actualForce = allocationType.getMethod("actualForce"); actualTorque = allocationType.getMethod("actualTorque");
            wings = field(type, "wings"); ranges = field(type, "ranges"); flaps = field(type, "flaps");
            passive = field(type, "passive"); aerodynamicControls = field(type, "aerodynamicControls");
            mass = field(type, "mass"); forward = field(type, "forward"); maxima = field(type, "maxima");
        }
        private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
            Field f = type.getDeclaredField(name);
            if (!f.trySetAccessible()) throw new IllegalAccessException(name);
            return f;
        }
        private static Method privateMethod(Class<?> type, String name, Class<?>... parameters)
                throws ReflectiveOperationException {
            Method m = type.getDeclaredMethod(name, parameters);
            if (!m.trySetAccessible()) throw new IllegalAccessException(name);
            return m;
        }
    }
}
