package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftMotionCompatibilityTest {
    @Test void onlyTheInspectedSableAbiAndBothBodyFencesAreAccepted() {
        var hashes = RailAircraftMotionCompatibility.PINNED_CLASSES;
        assertTrue(RailAircraftMotionCompatibility.supports("2.0.6", hashes, 1, 1));
        assertFalse(RailAircraftMotionCompatibility.supports("2.0.7", hashes, 1, 1));
        assertFalse(RailAircraftMotionCompatibility.supports("2.0.6", hashes, 0, 1));
        assertFalse(RailAircraftMotionCompatibility.supports("2.0.6", hashes, 1, 0));
        var changed = new HashMap<>(hashes);
        changed.put(RailAircraftMotionCompatibility.BODY, "0".repeat(64));
        assertFalse(RailAircraftMotionCompatibility.supports("2.0.6", changed, 1, 1));
        changed.remove(RailAircraftMotionCompatibility.BODY);
        assertFalse(RailAircraftMotionCompatibility.supports("2.0.6", changed, 1, 1));
    }
    @Test void topologyObservationRequiresTheInspectedPipelineAndEveryLifecycleFence() {
        var pins = RailAircraftMotionCompatibility.PINNED_RAPIER_CLASSES;
        assertTrue(RailAircraftMotionCompatibility.supportsTopology("2.0.6", pins, 1, 1, 1, 1, 1));
        assertFalse(RailAircraftMotionCompatibility.supportsTopology("2.0.7", pins, 1, 1, 1, 1, 1));
        for (int i = 0; i < 5; i++) {
            int[] counts = {1, 1, 1, 1, 1}; counts[i] = 0;
            assertFalse(RailAircraftMotionCompatibility.supportsTopology("2.0.6", pins,
                    counts[0], counts[1], counts[2], counts[3], counts[4]));
        }
        var missing = new HashMap<>(pins);
        missing.remove(RailAircraftMotionCompatibility.CONSTRAINT);
        assertFalse(RailAircraftMotionCompatibility.supportsTopology("2.0.6", missing, 1, 1, 1, 1, 1));
    }
}
