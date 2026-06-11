package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownNationRequestRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void sameNationJoinBackfillsLegacyCapitalClaimsWithTownId() {
        NationSavedData data = new NationSavedData();
        UUID actorUuid = UUID.randomUUID();
        data.putNation(new NationRecord("alpha", "Alpha Nation", "ALP", 0x123456, 0x654321, actorUuid, 1L, "", "", NationRecord.noCorePos(), ""));
        data.putTown(new TownRecord("monpai", "", "Monpai Town", actorUuid, 1L, "", TownRecord.noCorePos(), "", "european"));
        data.putMember(new NationMemberRecord(actorUuid, "GoatDie", "alpha", "", 1L));
        data.putClaim(claim(0, 0, "alpha", ""));
        data.putClaim(claim(1, 0, "alpha", ""));

        NationResult result = TownService.joinTownToNationDirectlyForTest(data, actorUuid, "Alpha Nation");

        assertTrue(result.success());
        assertEquals("monpai", data.getClaim("minecraft:overworld", 0, 0).townId());
        assertEquals("monpai", data.getClaim("minecraft:overworld", 1, 0).townId());
    }

    @Test
    void removeTownCoreActionUsesPickupWhenExistingCoreIsPresent() {
        AtomicBoolean pickedUp = new AtomicBoolean(false);

        NationResult result = TownService.finishTownCoreRemovalForTest(true, true, true, () -> {
            pickedUp.set(true);
            return NationResult.success(Component.literal("picked up"));
        });

        assertTrue(result.success());
        assertTrue(pickedUp.get());
    }

    @Test
    void removeTownCoreActionDoesNotPickupWhenCoreIsMissing() {
        AtomicBoolean pickedUp = new AtomicBoolean(false);

        NationResult result = TownService.finishTownCoreRemovalForTest(true, true, false, () -> {
            pickedUp.set(true);
            return NationResult.success(Component.literal("picked up"));
        });

        assertFalse(result.success());
        assertFalse(pickedUp.get());
    }

    private static NationClaimRecord claim(int chunkX, int chunkZ, String nationId, String townId) {
        return new NationClaimRecord(
                "minecraft:overworld",
                chunkX,
                chunkZ,
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
