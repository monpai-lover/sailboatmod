package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapSettingsTest {
    @Test
    void defaultsLeaveMostOfTheTickToGameplay() {
        assertEquals(2_000_000L, MarketWebMapSettings.mainThreadBudgetNanos());
        assertEquals(8, MarketWebMapSettings.maxSnapshotsPerTick());
        assertEquals(4, MarketWebMapSettings.maxProbeStartsPerTick());
        assertEquals(32, MarketWebMapSettings.maxInflightProbes());
        assertEquals(1, MarketWebMapSettings.renderThreads());
        assertEquals(30_000L, MarketWebMapSettings.loadedRefreshCooldownMillis());
        assertEquals(60_000L, MarketWebMapSettings.browserDiscoveryCooldownMillis());
    }

    @Test
    void pauseSettingDoesNotRequireAnActiveRenderJob() {
        boolean before = MarketWebMapSettings.updatesEnabled();
        try {
            MarketWebMapSettings.setUpdatesEnabled(false);
            assertFalse(MarketWebMapSettings.updatesEnabled());
            MarketWebMapSettings.setUpdatesEnabled(true);
            assertTrue(MarketWebMapSettings.updatesEnabled());
        } finally {
            MarketWebMapSettings.setUpdatesEnabled(before);
        }
    }
}
