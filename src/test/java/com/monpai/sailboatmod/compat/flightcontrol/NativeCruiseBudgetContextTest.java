package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

public class NativeCruiseBudgetContextTest {
    /** Real ThreadLocal fixture behind exact optional-native public signatures. */
    public static final class NativeFixture {
        public static final ThreadLocal<Integer> BUDGET = new ThreadLocal<>();
        public static int current() { Integer value = BUDGET.get(); return value == null ? 120 : value; }
    }
    public static class NativeScope implements AutoCloseable {
        private final Integer previous = NativeFixture.BUDGET.get();
        public NativeScope(int requested) {
            NativeFixture.BUDGET.set(Math.min(NativeFixture.current(), requested));
        }
        @Override public void close() {
            if (previous == null) NativeFixture.BUDGET.remove(); else NativeFixture.BUDGET.set(previous);
        }
    }

    @Test void exactReflectedPublicNativeApiInheritsBoth30And60AcrossThreads() throws Exception {
        Object owner = nativeOwner();
        try {
            for (int value : new int[]{30, 60}) {
                NativeFixture.BUDGET.set(value);
                Supplier<Integer> task = NativeCruiseBudgetContext.wrap(owner, NativeFixture::current);
                NativeFixture.BUDGET.set(20);
                try (var worker = Executors.newSingleThreadExecutor()) {
                    assertEquals(value, worker.submit(task::get).get(2, TimeUnit.SECONDS));
                    assertEquals(120, worker.submit(NativeFixture::current).get(2, TimeUnit.SECONDS));
                }
                assertEquals(20, NativeFixture.current());
            }
        } finally { NativeFixture.BUDGET.remove(); }
    }

    @Test void supplierExceptionPreservesItsObjectAndRestoresAnExistingNativeWorkerScope() throws Exception {
        Object owner = nativeOwner();
        RuntimeException original = new RuntimeException("original native job failure");
        NativeFixture.BUDGET.set(30);
        try {
            Supplier<Object> task = NativeCruiseBudgetContext.wrap(owner, () -> {
                assertEquals(30, NativeFixture.current());
                throw original;
            });
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> NativeFixture.BUDGET.set(60)).get(2, TimeUnit.SECONDS);
                ExecutionException result = assertThrows(ExecutionException.class,
                        () -> worker.submit(task::get).get(2, TimeUnit.SECONDS));
                assertSame(original, result.getCause());
                assertEquals(60, worker.submit(NativeFixture::current).get(2, TimeUnit.SECONDS));
            }
        } finally { NativeFixture.BUDGET.remove(); }
    }

    @Test void absentOptionalNativeClassLeavesTheOriginalSupplierUnchanged() {
        Supplier<Integer> original = () -> 42;
        assertSame(original, NativeCruiseBudgetContext.wrap(new Object(), original));
        assertEquals(42, original.get());
    }

    private static Object nativeOwner() throws Exception {
        String budget = "ace/flight/physics/AllocationBudget";
        String owner = "ace/flight/block/FlightControlComputerBlockEntity";
        String scope = NativeScope.class.getName().replace('.', '/');
        String fixture = NativeFixture.class.getName().replace('.', '/');
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, budget, null, scope, null);
        var ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ILOAD, 1);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, scope, "<init>", "(I)V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        var current = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "()I", null, null);
        current.visitCode(); current.visitMethodInsn(Opcodes.INVOKESTATIC, fixture, "current", "()I", false);
        current.visitInsn(Opcodes.IRETURN); current.visitMaxs(0, 0); current.visitEnd();
        var limit = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "limit", "(I)L" + budget + ";", null, null);
        limit.visitCode(); limit.visitTypeInsn(Opcodes.NEW, budget); limit.visitInsn(Opcodes.DUP);
        limit.visitVarInsn(Opcodes.ILOAD, 0);
        limit.visitMethodInsn(Opcodes.INVOKESPECIAL, budget, "<init>", "(I)V", false);
        limit.visitInsn(Opcodes.ARETURN); limit.visitMaxs(0, 0); limit.visitEnd(); w.visitEnd();
        byte[] nativeBudget = w.toByteArray();
        w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd(); w.visitEnd();
        Map<String, byte[]> definitions = Map.of(budget.replace('/', '.'), nativeBudget,
                owner.replace('/', '.'), w.toByteArray());
        ClassLoader loader = new ClassLoader(NativeCruiseBudgetContextTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                byte[] bytes = definitions.get(name);
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(found);
                return found;
            }
        };
        return loader.loadClass(owner.replace('/', '.')).getConstructor().newInstance();
    }
}
