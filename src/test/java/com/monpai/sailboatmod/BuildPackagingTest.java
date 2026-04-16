package com.monpai.sailboatmod;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BuildPackagingTest {
    @Test
    void buildScriptDoesNotRelocateSqliteJdbcNativeBindings() throws IOException {
        String buildScript = Files.readString(Path.of("build.gradle"));

        assertFalse(buildScript.contains("relocate('org.sqlite'"),
                "sqlite-jdbc JNI bindings must keep the org.sqlite package name");
    }

    @Test
    void marketDatabaseDoesNotFallbackToRelocatedSqliteDriver() throws IOException {
        String marketDatabase = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/db/MarketDatabase.java"));

        assertFalse(marketDatabase.contains("com.monpai.sailboatmod.shadow.sqlite.JDBC"),
                "market database should load only the standard org.sqlite driver");
    }
}
