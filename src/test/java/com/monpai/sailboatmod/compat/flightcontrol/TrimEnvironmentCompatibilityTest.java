package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class TrimEnvironmentCompatibilityTest {
    @Test void requiresExactJarEveryClassAndAllThreeUniqueCallsites() {
        var hashes = TrimEnvironmentCompatibility.PINNED_CLASSES;
        assertTrue(TrimEnvironmentCompatibility.supports("0.8.3", TrimEnvironmentCompatibility.NATIVE_JAR_SHA256, hashes, 1, 1, 1));
        assertFalse(TrimEnvironmentCompatibility.supports("0.8.4", TrimEnvironmentCompatibility.NATIVE_JAR_SHA256, hashes, 1, 1, 1));
        assertFalse(TrimEnvironmentCompatibility.supports("0.8.3", "different", hashes, 1, 1, 1));
        for (String name : hashes.keySet()) {
            var changed = new HashMap<>(hashes); changed.put(name, "different");
            assertFalse(TrimEnvironmentCompatibility.supports("0.8.3", TrimEnvironmentCompatibility.NATIVE_JAR_SHA256, changed, 1, 1, 1));
        }
        for (int i : new int[]{0, 2}) {
            assertFalse(TrimEnvironmentCompatibility.supports("0.8.3", TrimEnvironmentCompatibility.NATIVE_JAR_SHA256, hashes, i, 1, 1));
            assertFalse(TrimEnvironmentCompatibility.supports("0.8.3", TrimEnvironmentCompatibility.NATIVE_JAR_SHA256, hashes, 1, i, 1));
            assertFalse(TrimEnvironmentCompatibility.supports("0.8.3", TrimEnvironmentCompatibility.NATIVE_JAR_SHA256, hashes, 1, 1, i));
        }
    }
    @Test void allThreePostApplyMarkersAreRequiredWithoutPollutingRuntimeTestClass() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{TrimEnvironmentCompatibility.class.getProtectionDomain().getCodeSource().getLocation()}, ClassLoader.getPlatformClassLoader())) {
            var type = loader.loadClass(TrimEnvironmentCompatibility.class.getName());
            assertEquals(false, type.getMethod("isApplied").invoke(null));
            type.getMethod("markScopeApplied").invoke(null); type.getMethod("markBudgetApplied").invoke(null);
            assertEquals(false, type.getMethod("isApplied").invoke(null));
            type.getMethod("markSampleApplied").invoke(null);
            assertEquals(true, type.getMethod("isApplied").invoke(null));
        }
    }
}
