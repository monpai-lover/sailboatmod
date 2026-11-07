package com.monpai.sailboatmod.compat.flightcontrol;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in real-native contract. Absence is an explicit skip, never a native PASS. */
class NativeTrimEnvironmentJarContractTest {
    @Test void exactNativeLearnerAndSampleContracts() throws Exception {
        String nativePath = configured("nativeJar", "SAILBOAT_NATIVE_FLIGHT_CONTROL_JAR");
        Assumptions.assumeTrue(nativePath != null, "Native contract SKIPPED: set nativeJar or SAILBOAT_NATIVE_FLIGHT_CONTROL_JAR to exact FC0.8.3 jar");
        String jomlPath = configured("nativeJomlJar", "SAILBOAT_NATIVE_JOML_JAR");
        Path joml = jomlPath == null
                ? Path.of(Class.forName("org.joml.Vector3d").getProtectionDomain().getCodeSource().getLocation().toURI())
                : Path.of(jomlPath);
        NativeTrimEnvironmentJarProbe.run(Path.of(nativePath), joml);
    }
    private static String configured(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? null : value;
    }
}
