package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.integration.xaero.SailboatClaimHighlightEntry;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimHighlightSyncServiceTest {
    @Test
    void townNationOverridesLegacyClaimNationForHighlightColor() {
        NationClaimRecord legacyClaim = claim("oldnation", "crimea");
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea Port", UUID.randomUUID(), 1L,
                "minecraft:overworld", 0L, "", "european");
        NationRecord nation = new NationRecord("alpha", "Alpha Nation", "ALP", 0x123456, 0x654321,
                UUID.randomUUID(), 1L, "crimea", "", NationRecord.noCorePos(), "");

        SailboatClaimHighlightEntry entry = ClaimHighlightSyncService.entryForTest(legacyClaim, null, town, nation);

        assertEquals("alpha", entry.nationId());
        assertEquals("Alpha Nation", entry.nationName());
        assertEquals(0x123456, entry.primaryColorRgb());
        assertEquals(0x654321, entry.secondaryColorRgb());
    }

    @Test
    void claimNationIsUsedWhenClaimHasNoTown() {
        NationClaimRecord claim = claim("alpha", "");
        NationRecord nation = new NationRecord("alpha", "Alpha Nation", "ALP", 0xABCDEF, 0xFEDCBA,
                UUID.randomUUID(), 1L, "", "", NationRecord.noCorePos(), "");

        SailboatClaimHighlightEntry entry = ClaimHighlightSyncService.entryForTest(claim, nation, null, null);

        assertEquals("alpha", entry.nationId());
        assertEquals(0xABCDEF, entry.primaryColorRgb());
        assertEquals(0xFEDCBA, entry.secondaryColorRgb());
    }

    private static NationClaimRecord claim(String nationId, String townId) {
        return new NationClaimRecord(
                "minecraft:overworld",
                64,
                64,
                nationId,
                townId,
                "member",
                "member",
                "member",
                "member",
                "member",
                "member",
                "member",
                1L
        );
    }
}
