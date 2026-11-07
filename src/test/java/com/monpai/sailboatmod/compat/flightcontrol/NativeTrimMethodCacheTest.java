package com.monpai.sailboatmod.compat.flightcontrol;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTrimMethodCacheTest {
    private static final class Getters {
        int value;
        int reads;
        Getters(int value) { this.value = value; }
        public int value() { reads++; return value; }
        public String overloaded(Object value) { return "object"; }
        public String overloaded(String value) { return "string"; }
        public String overloaded(long value) { return "long"; }
        public String overloaded(double value) { return "double"; }
        public String primitive(long value) { return "long:" + value; }
        public String primitive(double value) { return "double:" + value; }
        public static String staticValue(String value) { return "static:" + value; }
        public Object fails() { reads++; throw new IllegalStateException("native failure"); }
    }
    private static final class OtherGetters {
        public String value() { return "different owner class"; }
    }

    @Test void repeatedGetterLookupAvoidsAllocatingPublicMethodArrays() throws Exception {
        ThreadMXBean allocations = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        assertTrue(allocations.isThreadAllocatedMemorySupported(), "JDK allocation measurement is required");
        if (!allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
        var getter = new Getters(41);
        for (int i = 0; i < 10_000; i++) NativeFixedWingTrimEnvironment.invoke(Getters.class, getter, "value");
        long thread = Thread.currentThread().threadId();
        long before = allocations.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10_000; i++) NativeFixedWingTrimEnvironment.invoke(Getters.class, getter, "value");
        long bytes = allocations.getThreadAllocatedBytes(thread) - before;
        assertEquals(20_000, getter.reads, "every invocation still reads the mutable owner exactly once");
        assertTrue(bytes < 5_120_000L, "steady getter lookup allocated " + bytes / 10_000.0
                + " bytes/call; repeated public Method arrays must not be rebuilt");
    }

    @Test void keepsFirstMatchingOverloadForEachArgumentTypeIncludingNull() throws Exception {
        var owner = new Getters(0);
        assertEquals(originalReferenceChoice(owner, "sample"), invoke(owner, "overloaded", "sample"));
        assertEquals(originalReferenceChoice(owner, null), invoke(owner, "overloaded", (Object)null));
        assertEquals(originalReferenceChoice(owner, new Object()), invoke(owner, "overloaded", new Object()));
        assertEquals(originalReferenceChoice(owner, "next"), invoke(owner, "overloaded", "next"));
        assertEquals(originalReferenceChoice(owner, null), invoke(owner, "overloaded", (Object)null));
    }

    @Test void preservesOnlyLongAndDoublePrimitiveMatching() throws Exception {
        var owner = new Getters(0);
        assertEquals("long:7", invoke(owner, "primitive", 7L));
        assertEquals("double:2.5", invoke(owner, "primitive", 2.5));
        assertThrows(NoSuchMethodException.class, () -> invoke(owner, "primitive", 7));
        assertThrows(NoSuchMethodException.class, () -> invoke(owner, "primitive", (Object)null));
    }

    @Test void cachedMethodsReadEachOwnerAndPreserveStaticAndPrivateClassAccess() throws Exception {
        var first = new Getters(11);
        var second = new Getters(22);
        assertEquals(11, invoke(first, "value"));
        first.value = 33;
        assertEquals(33, invoke(first, "value"));
        assertEquals(22, invoke(second, "value"));
        assertEquals("different owner class", NativeFixedWingTrimEnvironment.invoke(OtherGetters.class, new OtherGetters(), "value"));
        assertEquals(22, invoke(second, "value"));
        assertEquals(2, first.reads);
        assertEquals(2, second.reads);
        assertEquals("static:a", NativeFixedWingTrimEnvironment.invoke(Getters.class, null, "staticValue", "a"));
        assertEquals("static:b", NativeFixedWingTrimEnvironment.invoke(Getters.class, null, "staticValue", "b"));
    }

    @Test void cachedResolutionDoesNotHideGetterFailuresOrCacheTheirResults() throws Exception {
        var owner = new Getters(0);
        for (int i = 0; i < 2; i++) {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> invoke(owner, "fails"));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("native failure", failure.getCause().getMessage());
        }
        assertEquals(2, owner.reads);
    }

    private static Object invoke(Getters owner, String name, Object... arguments) throws ReflectiveOperationException {
        return NativeFixedWingTrimEnvironment.invoke(Getters.class, owner, name, arguments);
    }

    /** Reflection's existing first-match order is the contract; null is not a specificity preference. */
    private static Object originalReferenceChoice(Getters owner, Object value) throws ReflectiveOperationException {
        Method method = Arrays.stream(Getters.class.getMethods())
                .filter(candidate -> candidate.getName().equals("overloaded"))
                .filter(candidate -> !candidate.getParameterTypes()[0].isPrimitive())
                .filter(candidate -> value == null || candidate.getParameterTypes()[0].isInstance(value))
                .findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(owner, value);
    }
}
