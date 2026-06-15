package com.monpai.sailboatmod.nation.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownMemberInviteRecordTest {
    @Test
    void saveLoadRoundTripAndDirectionHelpers() {
        UUID player = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        TownMemberInviteRecord invite = new TownMemberInviteRecord(
                "Crimea", player, TownMemberInviteRecord.DIRECTION_INVITE, initiator, 50L);

        TownMemberInviteRecord loaded = TownMemberInviteRecord.load(invite.save());

        assertEquals("crimea", loaded.townId());
        assertEquals(player, loaded.playerUuid());
        assertEquals(initiator, loaded.initiatorUuid());
        assertEquals(50L, loaded.createdAt());
        assertTrue(loaded.isInvite());
        assertFalse(loaded.isApply());
    }

    @Test
    void applyDirectionHelper() {
        TownMemberInviteRecord apply = new TownMemberInviteRecord(
                "t", new UUID(0L, 0L), TownMemberInviteRecord.DIRECTION_APPLY, new UUID(0L, 0L), 1L);
        assertTrue(apply.isApply());
        assertFalse(apply.isInvite());
    }
}
