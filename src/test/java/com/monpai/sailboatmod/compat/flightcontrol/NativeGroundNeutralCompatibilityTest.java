package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutralCompatibility.*;

class NativeGroundNeutralCompatibilityTest {
    @Test void acceptsOnlyTheActualNativeJarComputerAndSingleAllocationHook() {
        assertTrue(supports("0.8.3", NATIVE_JAR_SHA256, COMPUTER_SHA256, 1));
    }
    @Test void unknownOrPatchedNativeBinariesAndAmbiguousHooksRemainUnmodified() {
        assertFalse(supports("0.8.4", NATIVE_JAR_SHA256, COMPUTER_SHA256, 1));
        assertFalse(supports("0.8.3", "unreviewed", COMPUTER_SHA256, 1));
        assertFalse(supports("0.8.3", NATIVE_JAR_SHA256, "unreviewed", 1));
        assertFalse(supports("0.8.3", NATIVE_JAR_SHA256, COMPUTER_SHA256, 0));
        assertFalse(supports("0.8.3", NATIVE_JAR_SHA256, COMPUTER_SHA256, 2));
    }
}
