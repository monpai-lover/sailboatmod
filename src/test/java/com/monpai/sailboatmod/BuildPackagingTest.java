package com.monpai.sailboatmod;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPackagingTest {
    @Test
    void buildScriptDoesNotRelocateSqliteJdbcNativeBindings() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertFalse(buildScript.contains("relocate('org.sqlite'"),
                "sqlite-jdbc JNI bindings must keep the org.sqlite package name");
    }

    @Test
    void buildScriptKeepsSqliteJdbcOutOfSailboatModule() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertFalse(buildScript.contains("zipTree(tasks.named('shadowSqliteJar')"),
                "sqlite-jdbc must not be merged into sailboatmod because that creates split org.sqlite packages");
    }

    @Test
    void buildScriptEmbedsSqliteJdbcAsInertResource() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertFalse(buildScript.contains("relocate('org.sqlite'"),
                "sqlite-jdbc JNI bindings must keep the org.sqlite package name");
        assertFalse(buildScript.contains("jarJar(\"org.xerial:sqlite-jdbc:"),
                "sqlite-jdbc must not be visible to Forge JarJar module discovery");
        assertTrue(buildScript.contains("sqliteEmbeddedLibrary(\"org.xerial:sqlite-jdbc:"),
                "sqlite-jdbc should be embedded as an inert resource for local Forge installs");
        assertTrue(buildScript.contains("META-INF/sailboatmod-libs"),
                "embedded sqlite-jdbc should live outside META-INF/jarjar");
    }

    @Test
    void marketDatabaseDoesNotFallbackToRelocatedSqliteDriver() throws IOException {
        String marketDatabase = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/db/MarketDatabase.java"));

        assertFalse(marketDatabase.contains("com.monpai.sailboatmod.shadow.sqlite.JDBC"),
                "market database should load only the standard org.sqlite driver");
    }

    @Test
    void marketDatabaseCanLoadEmbeddedSqliteDriverLazily() throws IOException {
        String marketDatabase = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/db/MarketDatabase.java"));

        assertTrue(marketDatabase.contains("META-INF/sailboatmod-libs/sqlite-jdbc-"),
                "embedded sqlite-jdbc jar should be loaded from the inert resource path");
        assertTrue(marketDatabase.contains("URLClassLoader"),
                "market database should lazily load the embedded sqlite driver when the environment has none");
        assertTrue(marketDatabase.contains("DriverManager.registerDriver"),
                "a parent-visible Driver shim should register embedded sqlite with DriverManager");
    }

    @Test
    void marketWebAddonPackageDoesNotLeakIntoMainMod() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertTrue(buildScript.contains("exclude 'com/monpai/sailboatmod/market/web/**'"),
                "the main mod jar must exclude the complete market web addon package");
        assertFalse(Files.exists(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketTerminalSavedData.java")),
                "shared market terminal saved data must not live in the market web addon package");
        assertTrue(Files.exists(Path.of("src/main/java/com/monpai/sailboatmod/market/terminal/MarketTerminalSavedData.java")),
                "shared market terminal saved data should live in a main-mod market terminal package");
    }
}
