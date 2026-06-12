package com.monpai.sailboatmod.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketScreenDispatchCandidateTest {
    @Test
    void dispatchCandidateSubtitleShowsCarrierNameInCandidateList() {
        assertEquals(
                "Oak Carriage | Alpha -> Cedar",
                MarketScreen.dispatchCandidateSubtitleForTest("Oak Carriage", "Alpha -> Cedar", "Select carrier")
        );
    }

    @Test
    void dispatchCandidateSubtitleFallsBackWhenCarrierAndRouteAreBlank() {
        assertEquals(
                "Select carrier",
                MarketScreen.dispatchCandidateSubtitleForTest("", "", "Select carrier")
        );
    }
}
