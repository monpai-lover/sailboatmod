package com.monpai.sailboatmod.network.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationGuiActionPacketTest {
    @Test
    void openingTradeScreenDoesNotImmediatelyRefreshNationOverview() {
        assertFalse(NationGuiActionPacket.shouldRefreshNationOverviewAfterAction(
                NationGuiActionPacket.Action.OPEN_TRADE_SCREEN));
        assertTrue(NationGuiActionPacket.shouldRefreshNationOverviewAfterAction(
                NationGuiActionPacket.Action.DIPLOMACY_TRADE));
    }
}
