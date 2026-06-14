package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NationFlagStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesSharedHashFileFromFlagIdPrefixWhenMetadataIsMissing() throws Exception {
        String sha = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        Path shared = tempDir.resolve(sha + ".png");
        Files.write(shared, new byte[] {1, 2, 3});

        Path resolved = NationFlagStorage.resolveFlagPathForTest(tempDir, "nation_abcdef123456");

        assertEquals(shared, resolved);
    }

    @Test
    void keepsLegacyFlagFileFallbackWhenNoSharedHashMatches() throws Exception {
        Path legacy = tempDir.resolve("nation_abcdef123456.png");
        Files.write(legacy, new byte[] {1, 2, 3});

        Path resolved = NationFlagStorage.resolveFlagPathForTest(tempDir, "nation_abcdef123456");

        assertEquals(legacy, resolved);
    }
}
