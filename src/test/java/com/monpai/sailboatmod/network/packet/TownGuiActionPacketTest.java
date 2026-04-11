package com.monpai.sailboatmod.network.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownGuiActionPacketTest {
    @Test
    void townGuiJoinNationActionRoutesToTownService() {
        assertEquals(TownGuiActionPacket.Action.JOIN_NATION, TownGuiActionPacket.Action.valueOf("JOIN_NATION"));
        assertTrue(TownGuiActionPacket.shouldJoinTownDirectlyForTest("alpha", "alpha"));
        assertFalse(TownGuiActionPacket.shouldJoinTownDirectlyForTest("alpha", "beta"));
    }
}
