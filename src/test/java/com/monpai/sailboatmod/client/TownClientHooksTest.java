package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownClientHooksTest {
    @Test
    void claimPreviewOwnerFilterRejectsMismatchedOwner() {
        assertTrue(TownClientHooks.shouldApplyClaimPreviewOwner("town-a", "town-a"));
        assertFalse(TownClientHooks.shouldApplyClaimPreviewOwner("town-a", "town-b"));
        assertFalse(TownClientHooks.shouldApplyClaimPreviewOwner("", "town-a"));
    }

    @Test
    void emptyTownOverviewCanBeCenteredOnPlayerChunk() {
        TownOverviewData data = TownOverviewData.emptyAt(34, -12);

        assertEquals(34, data.currentChunkX());
        assertEquals(-12, data.currentChunkZ());
        assertEquals(34, data.previewCenterChunkX());
        assertEquals(-12, data.previewCenterChunkZ());
        assertEquals(34, data.claimMapState().centerChunkX());
        assertEquals(-12, data.claimMapState().centerChunkZ());
    }
}
