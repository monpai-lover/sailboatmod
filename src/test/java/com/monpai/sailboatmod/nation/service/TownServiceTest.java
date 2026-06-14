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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void previousTownCoreClaimCleanupRemovesAutoCoreClaimOnly() {
        NationSavedData data = new NationSavedData();
        UUID mayorUuid = UUID.randomUUID();
        net.minecraft.core.BlockPos oldCore = new net.minecraft.core.BlockPos(1024, 64, 1024);
        net.minecraft.world.level.ChunkPos oldChunk = new net.minecraft.world.level.ChunkPos(oldCore);
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea Port", mayorUuid, 1L,
                "minecraft:overworld", oldCore.asLong(), "", "european");
        data.putTown(town);
        data.putClaim(claim(oldChunk.x, oldChunk.z, "alpha", "crimea", NationClaimRecord.SOURCE_TOWN_CORE));
        data.putClaim(claim(99, 99, "alpha", "crimea"));

        assertTrue(TownService.removePreviousTownCoreClaimForTest(data, town, "minecraft:overworld", oldCore.asLong()));

        assertNull(data.getClaim("minecraft:overworld", oldChunk.x, oldChunk.z));
        assertNotNull(data.getClaim("minecraft:overworld", 99, 99));
    }

    @Test
    void previousTownCoreClaimCleanupKeepsManualTownClaim() {
        NationSavedData data = new NationSavedData();
        UUID mayorUuid = UUID.randomUUID();
        net.minecraft.core.BlockPos oldCore = new net.minecraft.core.BlockPos(1024, 64, 1024);
        net.minecraft.world.level.ChunkPos oldChunk = new net.minecraft.world.level.ChunkPos(oldCore);
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea Port", mayorUuid, 1L,
                "minecraft:overworld", oldCore.asLong(), "", "european");
        data.putTown(town);
        data.putClaim(claim(oldChunk.x, oldChunk.z, "alpha", "crimea"));

        assertFalse(TownService.removePreviousTownCoreClaimForTest(data, town, "minecraft:overworld", oldCore.asLong()));

        assertNotNull(data.getClaim("minecraft:overworld", oldChunk.x, oldChunk.z));
    }

    @Test
    void previousTownCoreClaimCleanupDoesNotRemoveOtherTownClaim() {
        NationSavedData data = new NationSavedData();
        UUID mayorUuid = UUID.randomUUID();
        net.minecraft.core.BlockPos oldCore = new net.minecraft.core.BlockPos(1024, 64, 1024);
        net.minecraft.world.level.ChunkPos oldChunk = new net.minecraft.world.level.ChunkPos(oldCore);
        TownRecord town = new TownRecord("crimea", "alpha", "Crimea Port", mayorUuid, 1L,
                "minecraft:overworld", oldCore.asLong(), "", "european");
        data.putTown(town);
        data.putClaim(claim(oldChunk.x, oldChunk.z, "alpha", "other", NationClaimRecord.SOURCE_TOWN_CORE));

        assertFalse(TownService.removePreviousTownCoreClaimForTest(data, town, "minecraft:overworld", oldCore.asLong()));

        assertNotNull(data.getClaim("minecraft:overworld", oldChunk.x, oldChunk.z));
    }

    private static NationClaimRecord claim(int chunkX, int chunkZ, String nationId, String townId) {
        return claim(chunkX, chunkZ, nationId, townId, NationClaimRecord.SOURCE_MANUAL);
    }

    private static NationClaimRecord claim(int chunkX, int chunkZ, String nationId, String townId, String source) {
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
                1L,
                source
        );
    }
}
