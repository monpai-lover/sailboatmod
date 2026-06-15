package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownMemberMigrationTest {
    @Test
    void migratesMayorToTownMember() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", mayor, 5L, "", TownRecord.noCorePos(), "", "european"));

        TownMemberMigration.runOnce(data);

        assertNotNull(data.getTownMember("crimea", mayor));
        assertTrue(data.isTownMembersMigrated());
    }

    @Test
    void migratesNationOrphanToCapitalTown() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha", "ALP", 0x111111, 0x222222, mayor, 1L, "crimea", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("crimea", "alpha", "Crimea", mayor, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putMember(new NationMemberRecord(orphan, "Orphan", "alpha", "member", 1L));

        TownMemberMigration.runOnce(data);

        assertNotNull(data.getTownMember("crimea", orphan));  // 挂到首都 town
    }

    @Test
    void isIdempotentViaFlag() {
        NationSavedData data = new NationSavedData();
        UUID mayor = UUID.randomUUID();
        data.putTown(new TownRecord("crimea", "", "Crimea", mayor, 5L, "", TownRecord.noCorePos(), "", "european"));
        TownMemberMigration.runOnce(data);

        // 标记已置，二次跑不再扫描：手动删镇民后重跑，不应被补回
        data.removeTownMember("crimea", mayor);
        TownMemberMigration.runOnce(data);

        assertNull(data.getTownMember("crimea", mayor));
    }
}
