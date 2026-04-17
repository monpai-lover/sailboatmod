package com.monpai.sailboatmod.client.screen.town;

import com.monpai.sailboatmod.client.cache.TerrainColorClientCache;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownHomeScreenTest {
    @BeforeEach
    void clearTerrainCache() {
        TerrainColorClientCache.clear();
    }

    @Test
    void joinNationButtonVisibleForStandaloneManagedTown() {
        TownHomeScreen screen = new TownHomeScreen(dataWithJoinTargets());

        assertTrue(screen.shouldShowJoinNationButtonForTest());
    }

    @Test
    void joinNationOverlayTracksSelectedNation() {
        TownHomeScreen screen = new TownHomeScreen(dataWithJoinTargets());
        screen.openJoinNationOverlayForTest();
        screen.selectJoinNationIndexForTest(1);

        assertEquals("beta", screen.selectedJoinNationIdForTest());
    }

    @Test
    void joinNationSubmissionClosesOverlayAndLeavesStatusMessage() {
        TownHomeScreen screen = new TownHomeScreen(dataWithJoinTargets());
        screen.openJoinNationOverlayForTest();
        screen.submitJoinNationSelectionForTest();

        assertFalse(screen.joinNationOverlayOpenForTest());
    }

    @Test
    void pendingLoadingStateRequestsRetryForSameRevision() {
        assertTrue(TownHomeScreen.shouldRetryPendingPreviewRequest(
                true,
                7L,
                10,
                20,
                ClaimPreviewMapState.loading(7L, 8, 10, 20)
        ));
    }

    @Test
    void pendingRetryDisabledWhenRevisionNoLongerLoading() {
        assertFalse(TownHomeScreen.shouldRetryPendingPreviewRequest(
                true,
                7L,
                10,
                20,
                ClaimPreviewMapState.ready(8L, 8, 10, 20, List.of(0xFF010203))
        ));
    }

    @Test
    void updateDataKeepsLastCompleteTerrainWhileNewViewportIsLoading() {
        TownHomeScreen screen = new TownHomeScreen(baseData(
                List.of(0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444),
                ClaimPreviewMapState.ready(1L, 0, 0, 0, List.of())
        ));

        screen.updateData(baseData(
                List.of(),
                ClaimPreviewMapState.loading(2L, 0, 1, 0)
        ));

        assertEquals(0xFF111111, screen.sampleClaimTerrainColorForTest(0, 0, 0, 0));
    }

    @Test
    void completeViewportSnapshotReplacesPreviousTerrainAfterDrag() {
        TownHomeScreen screen = new TownHomeScreen(baseData(
                List.of(0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444),
                ClaimPreviewMapState.ready(1L, 0, 0, 0, List.of())
        ));

        screen.updateData(baseData(
                List.of(0xFFAAAAAA, 0xFFBBBBBB, 0xFFCCCCCC, 0xFFDDDDDD),
                ClaimPreviewMapState.ready(2L, 0, 1, 0, List.of())
        ));

        assertEquals(0xFFAAAAAA, screen.sampleClaimTerrainColorForTest(1, 0, 0, 0));
    }

    private static TownOverviewData dataWithJoinTargets() {
        return new TownOverviewData(
                true,
                "monpai",
                "Monpai Town",
                "",
                "",
                "mayor-uuid",
                "GoatDie",
                false,
                0x4FA89B,
                0xD8B35A,
                true,
                "minecraft:overworld",
                0L,
                0,
                1,
                0,
                0,
                0,
                0,
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                0,
                0L,
                "",
                false,
                true,
                true,
                true,
                false,
                true,
                List.of(),
                List.of(),
                List.of(),
                "european",
                Map.of(),
                0.0f,
                Map.of(),
                0.0f,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new TownOverviewData.JoinableNationTarget("alpha", "Alpha Nation"),
                        new TownOverviewData.JoinableNationTarget("beta", "Beta Nation")
                )
        );
    }

    private static TownOverviewData baseData(List<Integer> terrainColors, ClaimPreviewMapState mapState) {
        return new TownOverviewData(
                true,
                "town-a",
                "Town A",
                "",
                "",
                "",
                "",
                false,
                0x123456,
                0x654321,
                false,
                "",
                0L,
                0,
                0,
                0,
                0,
                mapState.centerChunkX(),
                mapState.centerChunkZ(),
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                0,
                0L,
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                terrainColors,
                List.of(),
                "european",
                Map.of(),
                0.0f,
                Map.of(),
                0.0f,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                mapState
        );
    }
}
