package com.monpai.sailboatmod.registry;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ModDistributionConfigTest {
    @Test
    void bundledUiHasItsKotlinRuntimeWithoutRequiringAnExternalLanguageLoader() throws IOException {
        String build = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(build.contains("jarJar(implementation(\"org.jetbrains.kotlin:kotlin-stdlib:"), "UI Kotlin classes require the bundled stdlib");
        assertTrue(build.contains("jarJar(implementation(\"org.jetbrains.kotlin:kotlin-reflect:"), "Elementa reflection support must ship with the mod");
        String metadata = Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertFalse(metadata.contains("modId=\"kotlinforforge\""), "This Java mod provides its library runtime through JarJar");
    }

    @Test
    void metadataRequiresTheTargetNeoForgeMinecraftAndGeckoLibVersions() throws IOException {
        String metadata = Files.readString(Path.of("src/main/resources/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("modId=\"neoforge\""));
        assertTrue(metadata.contains("type=\"required\""));
        assertTrue(metadata.contains("versionRange=\"${neo_version_range}\""));
        assertTrue(metadata.contains("versionRange=\"${minecraft_version_range}\""));
        assertTrue(metadata.contains("versionRange=\"[4.9.3,5)\""));
        String versions = Files.readString(Path.of("gradle.properties"), StandardCharsets.UTF_8);
        assertTrue(versions.lines().anyMatch(line -> line.equals("minecraft_version=1.21.1")));
        assertTrue(versions.contains("minecraft_version_range=[1.21.1]"));
        assertTrue(versions.contains("neo_version=21.1.259"));
    }
}
