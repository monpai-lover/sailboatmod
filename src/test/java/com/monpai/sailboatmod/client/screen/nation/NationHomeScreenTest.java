package com.monpai.sailboatmod.client.screen.nation;

import com.monpai.sailboatmod.client.cache.TerrainColorClientCache;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void clearTerrainCache() {
        TerrainColorClientCache.clear();
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
    void loadingViewportPreservesCachedSubpixelTerrainResolution() {
        TerrainColorClientCache.put(0, 0, new int[] {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444});
        NationHomeScreen screen = new NationHomeScreen(NationOverviewData.empty().withClaimPreview(
                ClaimPreviewMapState.loading(2L, 0, 1, 0),
                java.util.List.of()
        ));

        assertEquals(0xFF111111, screen.sampleClaimTerrainColorForTest(0, 0, 0, 0));
        assertEquals(0xFF222222, screen.sampleClaimTerrainColorForTest(0, 0, 1, 0));
        assertEquals(0xFF333333, screen.sampleClaimTerrainColorForTest(0, 0, 0, 1));
        assertEquals(0xFF444444, screen.sampleClaimTerrainColorForTest(0, 0, 1, 1));
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
