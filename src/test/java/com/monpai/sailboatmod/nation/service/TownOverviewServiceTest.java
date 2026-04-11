package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownOverviewServiceTest {
    @Test
    void standaloneManagedTownIncludesJoinableNationTargets() {
        List<TownOverviewData.JoinableNationTarget> targets = TownOverviewService.joinableNationTargetsForTest(
                true,
                false,
                List.of(
                        new TownOverviewData.JoinableNationTarget("beta", "beta Nation"),
                        new TownOverviewData.JoinableNationTarget("alpha", "Alpha Nation")
                )
        );

        assertEquals(2, targets.size());
        assertEquals("alpha", targets.get(0).nationId());
        assertEquals("beta", targets.get(1).nationId());
    }

    @Test
    void joinableNationTargetsAreEmptyWhenTownCannotBeManaged() {
        assertTrue(TownOverviewService.joinableNationTargetsForTest(
                false,
                false,
                List.of(new TownOverviewData.JoinableNationTarget("alpha", "Alpha Nation"))
        ).isEmpty());
    }

    @Test
    void joinableNationTargetsAreEmptyWhenTownAlreadyHasNation() {
        assertTrue(TownOverviewService.joinableNationTargetsForTest(
                true,
                true,
                List.of(new TownOverviewData.JoinableNationTarget("alpha", "Alpha Nation"))
        ).isEmpty());
    }

    @Test
    void joinableNationTargetsAreEmptyWhenCandidatesMissing() {
        assertTrue(TownOverviewService.joinableNationTargetsForTest(
                true,
                false,
                null
        ).isEmpty());
    }
}
