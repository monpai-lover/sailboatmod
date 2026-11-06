package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class SolverBudgetContextTest {
    private static final class Budgets {
        final ThreadLocal<Integer> current = new ThreadLocal<>();
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        int current() { Integer value = current.get(); return value == null ? 120 : value; }
        AutoCloseable limit(int requested) {
            Integer previous = current.get();
            current.set(Math.min(current(), requested));
            opened.incrementAndGet();
            return () -> {
                if (previous == null) current.remove(); else current.set(previous);
                closed.incrementAndGet();
            };
        }
    }

    @Test void capturesCallerBudgetBeforeWorkerExecutionAndRestoresItsDefault() throws Exception {
        Budgets budgets = new Budgets();
        budgets.current.set(30);
        Supplier<Integer> task = SolverBudgetContext.wrap(budgets::current, budgets::limit, budgets::current);
        assertEquals(0, budgets.opened.get(), "Creating a supplier must not enter its worker scope");
        budgets.current.set(60);
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertEquals(30, worker.submit(task::get).get(2, TimeUnit.SECONDS));
            assertEquals(120, worker.submit(budgets::current).get(2, TimeUnit.SECONDS));
        }
        assertEquals(60, budgets.current());
        assertEquals(1, budgets.opened.get());
        assertEquals(1, budgets.closed.get());
    }

    @Test void restoresAnExistingWorkerBudgetInsteadOfClearingAnotherScope() throws Exception {
        Budgets budgets = new Budgets();
        budgets.current.set(30);
        Supplier<Integer> task = SolverBudgetContext.wrap(budgets::current, budgets::limit, budgets::current);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> budgets.current.set(60)).get(2, TimeUnit.SECONDS);
            assertEquals(30, worker.submit(task::get).get(2, TimeUnit.SECONDS));
            assertEquals(60, worker.submit(budgets::current).get(2, TimeUnit.SECONDS));
        }
        assertEquals(1, budgets.closed.get());
    }

    @Test void delegatesNativeMinimumSemanticsAndNeverRaisesANarrowerWorkerScope() throws Exception {
        Budgets budgets = new Budgets();
        budgets.current.set(60);
        Supplier<Integer> task = SolverBudgetContext.wrap(budgets::current, budgets::limit, budgets::current);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> budgets.current.set(20)).get(2, TimeUnit.SECONDS);
            assertEquals(20, worker.submit(task::get).get(2, TimeUnit.SECONDS));
            assertEquals(20, worker.submit(budgets::current).get(2, TimeUnit.SECONDS));
        }
        assertEquals(1, budgets.opened.get(), "The native scope must be used even when its minimum wins");
        assertEquals(1, budgets.closed.get());
    }

    @Test void supplierFailureKeepsItsIdentityAndClosesTheScope() {
        Budgets budgets = new Budgets();
        RuntimeException original = new RuntimeException("native supplier failed");
        Supplier<Object> task = SolverBudgetContext.wrap(() -> 30, budgets::limit, () -> { throw original; });
        assertSame(original, assertThrows(RuntimeException.class, task::get));
        assertEquals(120, budgets.current());
        assertEquals(1, budgets.closed.get());
    }

    @Test void closeFailureIsSuppressedOnTheOriginalSupplierFailure() {
        RuntimeException original = new RuntimeException("supplier");
        Exception close = new Exception("close");
        Supplier<Object> task = SolverBudgetContext.wrap(() -> 30,
                ignored -> () -> { throw close; }, () -> { throw original; });
        assertSame(original, assertThrows(RuntimeException.class, task::get));
        assertArrayEquals(new Throwable[]{close}, original.getSuppressed());
    }

    @Test void aCloseOnlyFailureIsNotWrappedOrSwallowed() {
        Exception close = new Exception("close only");
        Supplier<Integer> task = SolverBudgetContext.wrap(() -> 30,
                ignored -> () -> { throw close; }, () -> 7);
        assertSame(close, assertThrows(Exception.class, task::get));
    }

    @Test void errorAndFactoryFailureDoNotRunOrLeakAnUnclosedTaskScope() {
        Budgets budgets = new Budgets();
        AssertionError original = new AssertionError("supplier error");
        Supplier<Object> task = SolverBudgetContext.wrap(() -> 30, budgets::limit, () -> { throw original; });
        assertSame(original, assertThrows(AssertionError.class, task::get));
        assertEquals(120, budgets.current());
        assertEquals(1, budgets.closed.get());
        RuntimeException factory = new RuntimeException("scope factory failed");
        AtomicInteger calls = new AtomicInteger();
        Supplier<Integer> unopened = SolverBudgetContext.wrap(() -> 30,
                ignored -> { throw factory; }, calls::incrementAndGet);
        assertSame(factory, assertThrows(RuntimeException.class, unopened::get));
        assertEquals(0, calls.get());
    }
}
