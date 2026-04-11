package com.monpai.sailboatmod.client.screen.town;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownHomeScreenTest {
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
}
