package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PitchBootstrapCompatibilityTest {
    @AfterEach void clearMarkers() throws Exception {
        resetMarkers();
    }
    @Test void onlyTheInspectedVersionAndBothUniqueCallSitesAreSupported() {
        assertTrue(PitchBootstrapCompatibility.supports("0.8.3", PitchBootstrapCompatibility.PINNED_CLASSES, 1, 1, 2));
        assertFalse(PitchBootstrapCompatibility.supports("0.8.4", PitchBootstrapCompatibility.PINNED_CLASSES, 1, 1, 2));
        assertFalse(PitchBootstrapCompatibility.supports(null, PitchBootstrapCompatibility.PINNED_CLASSES, 1, 1, 2));
        for (int invalid : new int[]{-1, 0, 2}) {
            assertFalse(PitchBootstrapCompatibility.supports("0.8.3", PitchBootstrapCompatibility.PINNED_CLASSES, invalid, 1, 2));
            assertFalse(PitchBootstrapCompatibility.supports("0.8.3", PitchBootstrapCompatibility.PINNED_CLASSES, 1, invalid, 2));
        }
    }
    @Test void bothCompensationStoresAreRequired() {
        for (int stores : new int[]{-1, 0, 1, 3})
            assertFalse(PitchBootstrapCompatibility.supports("0.8.3", PitchBootstrapCompatibility.PINNED_CLASSES, 1, 1, stores));
    }
    @Test void everyPinnedClassIsRequiredAndChangedBytecodeFailsClosed() {
        for (String name : PitchBootstrapCompatibility.PINNED_CLASSES.keySet()) {
            var hashes = new HashMap<>(PitchBootstrapCompatibility.PINNED_CLASSES);
            hashes.remove(name);
            assertFalse(PitchBootstrapCompatibility.supports("0.8.3", hashes, 1, 1, 2), name);
            hashes.put(name, "unverified");
            assertFalse(PitchBootstrapCompatibility.supports("0.8.3", hashes, 1, 1, 2), name);
        }
        assertFalse(PitchBootstrapCompatibility.supports("0.8.3", Map.of(), 1, 1, 2));
        assertFalse(PitchBootstrapCompatibility.supports("0.8.3", null, 1, 1, 2));
    }
    @Test void neitherHookAloneCanEnableTheFeedbackInEitherApplicationOrder() throws Exception {
        resetMarkers();
        assertFalse(PitchBootstrapCompatibility.isApplied());
        PitchBootstrapCompatibility.markScopeApplied();
        assertFalse(PitchBootstrapCompatibility.isApplied());
        PitchBootstrapCompatibility.markGuidanceApplied();
        assertFalse(PitchBootstrapCompatibility.isApplied());
        PitchBootstrapCompatibility.markMeasurementApplied();
        assertTrue(PitchBootstrapCompatibility.isApplied());
        resetMarkers();
        PitchBootstrapCompatibility.markGuidanceApplied();
        assertFalse(PitchBootstrapCompatibility.isApplied());
        PitchBootstrapCompatibility.markScopeApplied();
        assertFalse(PitchBootstrapCompatibility.isApplied());
        PitchBootstrapCompatibility.markMeasurementApplied();
        assertTrue(PitchBootstrapCompatibility.isApplied());
    }
    private static void resetMarkers() throws Exception {
        for (String name : new String[]{"scopeApplied", "measurementApplied", "guidanceApplied"}) {
            var field = PitchBootstrapCompatibility.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, false);
        }
    }
}
