package com.monpai.sailboatmod.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModDistributionConfigTest {
    @Test
    void allInOneJarDoesNotBundleKotlinStdlib() throws IOException {
        String buildFile = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);

        assertFalse(buildFile.contains("jarJar(\"org.jetbrains.kotlin:kotlin-stdlib"),
                "Kotlin stdlib must be supplied by KotlinForForge, not bundled into the all jar");
        assertFalse(buildFile.contains("jarJar(\"org.jetbrains.kotlin:kotlin-stdlib-jdk7"),
                "Kotlin stdlib-jdk7 must be supplied by KotlinForForge, not bundled into the all jar");
        assertFalse(buildFile.contains("jarJar(\"org.jetbrains.kotlin:kotlin-stdlib-jdk8"),
                "Kotlin stdlib-jdk8 must be supplied by KotlinForForge, not bundled into the all jar");
        assertTrue(buildFile.contains("exclude group: 'org.jetbrains.kotlin'"),
                "JarJar must exclude transitive Kotlin artifacts from bundled UI libraries");
        assertTrue(buildFile.contains("exclude 'META-INF/jarjar/kotlin-stdlib*.jar'"),
                "JarJar archive must strip any Kotlin stdlib jars that ForgeGradle adds after dependency resolution");
        assertTrue(buildFile.contains("stripKotlinStdlibJarJarEntries"),
                "JarJar and reobfJarJar must post-process archives because ForgeGradle appends JarJar artifacts late");
    }

    @Test
    void kotlinForForgeIsDeclaredAsExternalDependency() throws IOException {
        String modsToml = Files.readString(Path.of("src/main/resources/META-INF/mods.toml"), StandardCharsets.UTF_8);
        String block = dependencyBlock(modsToml, "kotlinforforge");

        assertTrue(block.contains("mandatory=true"), "KotlinForForge must be a mandatory runtime dependency");
        assertTrue(block.contains("versionRange=\"[4.0,)\""), "KotlinForForge dependency should allow current 1.20.1 4.x builds");
        assertTrue(block.contains("side=\"BOTH\""), "KotlinForForge dependency must be declared for both sides");
    }

    private static String dependencyBlock(String modsToml, String modId) {
        String marker = "[[dependencies.${mod_id}]]";
        String[] sections = modsToml.split("\\Q" + marker + "\\E");
        for (String section : sections) {
            if (section.contains("modId=\"" + modId + "\"")) {
                return marker + section;
            }
        }
        throw new AssertionError("Missing dependency block for modId=" + modId);
    }
}
