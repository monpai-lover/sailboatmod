package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownMemberQueryContractTest {
    @Test
    void townPanelDataSourcesReturnConsistentView() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putTownMember(new TownMemberRecord(mayor, "crimea", TownMemberRecord.OFFICE_MEMBER, 1L));
        data.putTownMember(new TownMemberRecord(resident, "crimea", TownMemberRecord.OFFICE_MEMBER, 2L));

        // GUI 镇民段数据源
        assertEquals(2, data.getTownMembersForTown("crimea").size());
        // GUI "我的 town" 段数据源
        assertEquals(1, data.getTownsForPlayer(mayor).size());
        assertEquals(1, data.getTownsForPlayer(resident).size());
    }
}
