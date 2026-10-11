package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataTownRemovalTest {
    @Test
    void removingTownClearsItsMembershipsAndRequestsWithoutAffectingOtherTowns() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        putTownMembership(data, "deleted", "", player);
        putTownMembership(data, "retained", "", player);
        data.markTownMembersMigratedForTest();

        data.removeTown("deleted");

        assertNull(data.getTownMember("deleted", player));
        assertNull(data.getTownMemberInvite("deleted", player));
        assertEquals(java.util.List.of("retained"), data.getTownsForPlayer(player));
        assertNotNull(data.getTownMember("retained", player));
        assertNotNull(data.getTownMemberInvite("retained", player));
        NationSavedData loaded = NationSavedData.load(data.save(new CompoundTag()));
        assertTrue(loaded.getTownMembersForTown("deleted").isEmpty());
        assertTrue(loaded.getTownMemberInvitesForTown("deleted").isEmpty());
    }

    @Test
    void removingNationClearsMembershipsAndRequestsOfAllItsDeletedTowns() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 1, 2,
                player, 1L, "first", "", NationRecord.noCorePos(), ""));
        putTownMembership(data, "first", "alpha", player);
        putTownMembership(data, "second", "alpha", player);
        putTownMembership(data, "retained", "", player);

        data.removeNation("alpha");

        assertNull(data.getTown("first"));
        assertNull(data.getTown("second"));
        assertTrue(data.getTownMembersForTown("first").isEmpty());
        assertTrue(data.getTownMembersForTown("second").isEmpty());
        assertTrue(data.getTownMemberInvitesForTown("first").isEmpty());
        assertTrue(data.getTownMemberInvitesForTown("second").isEmpty());
        assertEquals(java.util.List.of("retained"), data.getTownsForPlayer(player));
        assertNotNull(data.getTownMemberInvite("retained", player));
    }

    private static void putTownMembership(NationSavedData data, String townId, String nationId, UUID player) {
        data.putTown(new TownRecord(townId, nationId, townId, player, 1L,
                "", TownRecord.noCorePos(), "", "european"));
        data.putTownMember(new TownMemberRecord(player, townId, TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMemberInvite(new TownMemberInviteRecord(
                townId, player, TownMemberInviteRecord.DIRECTION_APPLY, player, 1L));
    }
}
