package com.monpai.sailboatmod.client.screen.nation;

import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewNationEntry;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationHomeScreenTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void middleDragReleaseRequestsRefresh() {
        assertTrue(NationHomeScreen.shouldRequestRefreshAfterMapDragRelease(2, true));
        assertFalse(NationHomeScreen.shouldRequestRefreshAfterMapDragRelease(0, true));
        assertFalse(NationHomeScreen.shouldRequestRefreshAfterMapDragRelease(2, false));
    }

    @Test
    void queuedRefreshFlushesOnlyWhenNewCenterDiffersAndNothingPending() {
        assertTrue(NationHomeScreen.shouldFlushQueuedPreviewRefresh(false, 14, -6, 10, -6));
        assertFalse(NationHomeScreen.shouldFlushQueuedPreviewRefresh(true, 14, -6, 10, -6));
        assertFalse(NationHomeScreen.shouldFlushQueuedPreviewRefresh(false, Integer.MIN_VALUE, -6, 10, -6));
        assertFalse(NationHomeScreen.shouldFlushQueuedPreviewRefresh(false, 10, -6, 10, -6));
    }

    @Test
    void tradeWindowEntryDoesNotRequireWarOrTreasuryPermission() {
        assertTrue(NationHomeScreen.canOpenTradeWindow(true, true, true, false, true));
        assertTrue(NationHomeScreen.canOpenTradeWindow(true, true, true, true, false));
        assertFalse(NationHomeScreen.canOpenTradeWindow(true, true, false, false, true));
    }

    @Test
    void tradeWindowOpenTargetUsesNationIdWithNameFallback() {
        NationOverviewNationEntry entry = new NationOverviewNationEntry(
                " nation-id ",
                "Target Nation",
                "TN",
                0x112233,
                0x445566,
                "",
                false,
                4,
                "trade"
        );
        NationOverviewNationEntry legacyEntryWithoutId = new NationOverviewNationEntry(
                "",
                "Legacy Nation",
                "LN",
                0x112233,
                0x445566,
                "",
                false,
                4,
                "trade"
        );

        assertEquals("nation-id", NationHomeScreen.tradeOpenTargetForTest(entry));
        assertEquals("Legacy Nation", NationHomeScreen.tradeOpenTargetForTest(legacyEntryWithoutId));
        assertEquals("", NationHomeScreen.tradeOpenTargetForTest(null));
    }

    @Test
    void bottomClaimMapProgressHidesOnlyAfterVisibleAndPrefetchWorkFinish() {
        ClaimPreviewMapState pendingPrefetch = ClaimPreviewMapState.ready(
                12L,
                1,
                0,
                0,
                java.util.List.of(0xFF112233),
                9,
                9,
                12,
                16
        );
        ClaimPreviewMapState complete = ClaimPreviewMapState.ready(
                13L,
                1,
                0,
                0,
                java.util.List.of(0xFF112233),
                9,
                9,
                16,
                16
        );

        assertTrue(NationHomeScreen.shouldShowClaimMapProgress(pendingPrefetch));
        assertFalse(NationHomeScreen.shouldShowClaimMapProgress(complete));
    }
}
