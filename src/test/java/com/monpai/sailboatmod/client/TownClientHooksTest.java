package com.monpai.sailboatmod.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownClientHooksTest {
    @Test
    void claimPreviewOwnerFilterRejectsMismatchedOwner() {
        assertTrue(TownClientHooks.shouldApplyClaimPreviewOwner("town-a", "town-a"));
        assertFalse(TownClientHooks.shouldApplyClaimPreviewOwner("town-a", "town-b"));
        assertFalse(TownClientHooks.shouldApplyClaimPreviewOwner("", "town-a"));
    }
}
