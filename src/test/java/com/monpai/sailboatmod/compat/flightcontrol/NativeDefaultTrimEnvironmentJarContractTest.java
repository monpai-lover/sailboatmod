package com.monpai.sailboatmod.compat.flightcontrol;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Opt-in actual default-capture fixture. Missing native configuration is an explicit skip. */
class NativeDefaultTrimEnvironmentJarContractTest {
    @Test void realNativeDefaultCaptureEnvironmentContract() throws Exception {
        String nativeJar = configured("nativeJar", "SAILBOAT_NATIVE_FLIGHT_CONTROL_JAR");
        Assumptions.assumeTrue(nativeJar != null, "Native default contract SKIPPED: configure exact nativeJar");
        String dependencies = configured("nativeDefaultClasspathFile", "SAILBOAT_NATIVE_DEFAULT_CLASSPATH_FILE");
        assertNotNull(dependencies, "Explicit native contract needs nativeDefaultClasspathFile with actual Sable/Pose, Minecraft, Create, Simulated, codec, and ASM fixture dependencies");
        NativeDefaultTrimEnvironmentJarProbe.run(Path.of(nativeJar), Path.of(dependencies));
    }
    private static String configured(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? null : value;
    }
}
