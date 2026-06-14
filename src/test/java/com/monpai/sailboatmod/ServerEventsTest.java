package com.monpai.sailboatmod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEventsTest {
    @Test
    void startupTaskWrapperCatchesLinkageErrors() {
        assertFalse(ServerEvents.runStartupTaskSafelyForTest("sqlite", () -> {
            throw new NoClassDefFoundError("org/sqlite/core/NativeDB");
        }));
    }

    @Test
    void startupTaskWrapperReportsSuccessfulExecution() {
        assertTrue(ServerEvents.runStartupTaskSafelyForTest("sqlite", () -> {
        }));
    }

    @Test
    void orphanClaimCleanupSyncsHighlightsOnlyWhenClaimsWereRemoved() {
        assertFalse(ServerEvents.shouldSyncAfterOrphanClaimCleanupForTest(0));
        assertTrue(ServerEvents.shouldSyncAfterOrphanClaimCleanupForTest(2));
    }
}
