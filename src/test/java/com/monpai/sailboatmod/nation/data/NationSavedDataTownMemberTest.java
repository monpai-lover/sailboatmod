package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NationSavedDataTownMemberTest {
    @Test
    void putGetRemoveTownMember() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));

        assertEquals(player, data.getTownMember("crimea", player).playerUuid());

        data.removeTownMember("crimea", player);
        assertNull(data.getTownMember("crimea", player));
    }

    @Test
    void getTownMembersForTownIsolatesByTown() {
        NationSavedData data = new NationSavedData();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(p1, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(p2, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putTownMember(new TownMemberRecord(p1, "kerch", TownMemberRecord.OFFICE_MEMBER, 3L));

        assertEquals(2, data.getTownMembersForTown("crimea").size());
        assertEquals(1, data.getTownMembersForTown("kerch").size());
    }

    @Test
    void getTownsForPlayerReturnsAllTowns() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(player, "kerch", TownMemberRecord.OFFICE_MEMBER, 2L));

        List<String> towns = data.getTownsForPlayer(player);
        assertEquals(2, towns.size());
        assertTrue(towns.contains("crimea"));
        assertTrue(towns.contains("kerch"));
    }
}
