package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownNationRequestRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownServiceTest {
    @Test
    void sameNationJoinBindsTownImmediatelyWithoutPendingRequest() {
        NationSavedData data = new NationSavedData();
        UUID actorUuid = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha Nation", "ALP", 0x123456, 0x654321, actorUuid, 1L, "", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("monpai", "", "Monpai Town", actorUuid, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putMember(new NationMemberRecord(actorUuid, "GoatDie", "alpha", "", 1L));
        data.putTownNationRequest(new TownNationRequestRecord("monpai", "alpha", TownNationRequestRecord.DIRECTION_APPLY, actorUuid, 1L));

        NationResult result = TownService.joinTownToNationDirectlyForTest(data, actorUuid, "Alpha Nation");

        assertTrue(result.success());
        assertEquals("alpha", data.getTown("monpai").nationId());
        assertNull(data.getTownNationRequest("monpai", "alpha"));
    }
}
