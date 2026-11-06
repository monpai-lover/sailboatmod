package com.monpai.sailboatmod.compat.flightcontrol;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.Supplier;

/** Optional-native adapter for the pinned FCC cruise-job submission call site. */
public final class NativeCruiseBudgetContext {
    private static final String BUDGET = "ace.flight.physics.AllocationBudget";
    private NativeCruiseBudgetContext() { }

    public static <T> Supplier<T> wrap(Object owner, Supplier<T> task) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        Class<?> budget;
        try {
            budget = Class.forName(BUDGET, false, owner.getClass().getClassLoader());
        } catch (ClassNotFoundException absent) {
            return task;
        }
        try {
            Method current = budget.getMethod("current");
            Method limit = budget.getMethod("limit", int.class);
            if (current.getReturnType() != int.class || limit.getReturnType() != budget
                    || !Modifier.isStatic(current.getModifiers()) || !Modifier.isStatic(limit.getModifiers())
                    || !AutoCloseable.class.isAssignableFrom(budget)) {
                throw new IllegalStateException("Unsupported native allocation-budget API");
            }
            return SolverBudgetContext.wrap(() -> (Integer) invoke(current),
                    captured -> (AutoCloseable) invoke(limit, captured), task);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("Native allocation-budget API unavailable", unavailable);
        }
    }

    private static Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            return NativeCruiseBudgetContext.<Object, RuntimeException>rethrow(failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Native allocation-budget API inaccessible", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
