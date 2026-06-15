package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownMemberInviteRecord;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownMemberServiceTest {
    private static TownRecord standaloneTown(NationSavedData data, UUID mayor) {
        TownRecord town = new TownRecord("crimea", "", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        return town;
    }

    @Test
    void applyCreatesApplyRequest() {
        NationSavedData data = new NationSavedData();
        UUID player = UUID.randomUUID();
        standaloneTown(data, UUID.randomUUID());

        TownMemberService.applyToTownForTest(data, player, "crimea");

        TownMemberInviteRecord req = data.getTownMemberInvite("crimea", player);
        assertNotNull(req);
        assertTrue(req.isApply());
        assertNull(data.getTownMember("crimea", player));  // 未直接入镇
    }

    @Test
    void inviteThenApplyMergesAndAddsMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);

        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        assertNotNull(data.getTownMember("crimea", player));  // 相遇成交
        assertNull(data.getTownMemberInvite("crimea", player));  // 请求已清
    }

    @Test
    void applyThenInviteMergesAndAddsMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);

        TownMemberService.applyToTownForTest(data, player, "crimea");
        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);

        assertNotNull(data.getTownMember("crimea", player));
        assertNull(data.getTownMemberInvite("crimea", player));
    }

    @Test
    void joinTownAlreadyInNationGrantsNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        data.putNation(new NationRecord(
                "alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);

        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        assertNotNull(data.getTownMember("crimea", player));
        assertNotNull(data.getMember(player));
        assertEquals("alpha", data.getMember(player).nationId());
    }

    @Test
    void joinStandaloneTownGivesNoNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        standaloneTown(data, mayor);  // nationId=""

        TownMemberService.inviteToTownForTest(data, mayor, "crimea", player);
        TownMemberService.applyToTownForTest(data, player, "crimea");

        assertNotNull(data.getTownMember("crimea", player));
        assertNull(data.getMember(player));  // 独立 town 无 nation 籍
    }

    @Test
    void leaveTownRemovesMemberAndNationMembership() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        data.putNation(new NationRecord(
                "alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", NationRecord.noCorePos(), ""));
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european");
        data.putTown(town);
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putMember(new NationMemberRecord(player, "P", "alpha", "member", 1L));

        TownMemberService.leaveTownForTest(data, player, "crimea");

        assertNull(data.getTownMember("crimea", player));
        assertNull(data.getMember(player));  // 不再属任何此 nation 的 town → 退籍
    }

    @Test
    void leaveTownKeepsNationIfStillInAnotherTown() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        data.putNation(new NationRecord(
                "alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTown(new TownRecord("kerch", "alpha", "Kerch", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTownMember(new TownMemberRecord(player, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(player, "kerch", TownMemberRecord.OFFICE_MEMBER, 2L));
        data.putMember(new NationMemberRecord(player, "P", "alpha", "member", 1L));

        TownMemberService.leaveTownForTest(data, player, "crimea");

        assertNull(data.getTownMember("crimea", player));
        assertNotNull(data.getMember(player));  // 仍在 kerch（同 nation）→ 保留
    }
}
