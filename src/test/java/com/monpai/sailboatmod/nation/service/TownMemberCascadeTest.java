package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TownMemberCascadeTest {
    @Test
    void bindTownToNationGivesAllTownMembersNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        data.putTownMember(new TownMemberRecord(mayor, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));

        TownService.bindTownToNationForTest(data, town, "alpha");

        assertNotNull(data.getMember(mayor));
        assertEquals("alpha", data.getMember(mayor).nationId());
        assertNotNull(data.getMember(resident));
        assertEquals("alpha", data.getMember(resident).nationId());
    }

    @Test
    void unbindTownFromNationRemovesNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID resident = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, resident, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea", resident, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putMember(new NationMemberRecord(resident, "Res", "alpha", "member", 2L));

        TownService.unbindTownFromNationForTest(data, town);

        assertNull(data.getMember(resident));
    }

    @Test
    void unbindKeepsNationMembershipIfPlayerInAnotherTownOfSameNation() {
        NationSavedData data = new NationSavedData();
        UUID resident = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, resident, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord leaving = new TownRecord("crimea", "alpha", "Crimea", resident, 1L, "", TownRecord.noCorePos(), "", "european");
        TownRecord other = new TownRecord("kerch", "alpha", "Kerch", resident, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(leaving);
        data.putTown(other);
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putTownMember(new TownMemberRecord(resident, "kerch", TownMemberRecord.OFFICE_MEMBER, 3L));
        data.putMember(new NationMemberRecord(resident, "Res", "alpha", "member", 2L));

        TownService.unbindTownFromNationForTest(data, leaving);

        assertNotNull(data.getMember(resident));
        assertEquals("alpha", data.getMember(resident).nationId());
    }
}
