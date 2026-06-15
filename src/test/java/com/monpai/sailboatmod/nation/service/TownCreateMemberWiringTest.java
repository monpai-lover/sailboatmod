package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TownCreateMemberWiringTest {
    @Test
    void tryCreateTownRegistersMayorAsTownMember() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/nation/service/TownService.java"));
        int createIdx = src.indexOf("private static TownCreationOutcome tryCreateTown");
        assertTrue(createIdx >= 0, "tryCreateTown should exist");
        String body = src.substring(createIdx);
        assertTrue(body.contains("putTownMember(new TownMemberRecord("),
                "tryCreateTown should register the mayor as a town member via putTownMember");
    }
}
