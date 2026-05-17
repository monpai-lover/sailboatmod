package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradeClientHooksTest {
    @Test
    void lastSyncedDataStartsEmpty() {
        TradeScreenData data = TradeClientHooks.lastSyncedData();
        assertNotNull(data);
        assertEquals("", data.ourNationId());
        assertEquals("", data.targetNationId());
    }

    @Test
    void clearCacheResetsToEmpty() {
        TradeClientHooks.clearCache();
        TradeScreenData data = TradeClientHooks.lastSyncedData();
        assertNotNull(data);
        assertFalse(data.hasExistingProposal());
    }
}
