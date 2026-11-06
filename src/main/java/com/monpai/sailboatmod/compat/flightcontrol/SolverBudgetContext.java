package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.Objects;

/** Carries a submission-time solver limit into a worker's temporary public scope. */
public final class SolverBudgetContext {
    private SolverBudgetContext() { }

    public static <T> Supplier<T> wrap(IntSupplier currentBudget,
                                       IntFunction<? extends AutoCloseable> scopeFactory,
                                       Supplier<T> task) {
        Objects.requireNonNull(currentBudget, "currentBudget");
        Objects.requireNonNull(scopeFactory, "scopeFactory");
        Objects.requireNonNull(task, "task");
        int captured = currentBudget.getAsInt();
        return () -> {
            try (AutoCloseable ignored = Objects.requireNonNull(scopeFactory.apply(captured), "budget scope")) {
                return task.get();
            } catch (Throwable failure) {
                return SolverBudgetContext.<T, RuntimeException>rethrow(failure);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T rethrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
