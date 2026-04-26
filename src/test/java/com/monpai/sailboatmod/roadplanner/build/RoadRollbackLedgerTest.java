package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadRollbackLedgerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recordsOriginalStateBlockEntityNbtAndLookupInReverseOrder() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        RoadRollbackLedger ledger = new RoadRollbackLedger();
        CompoundTag blockEntityNbt = new CompoundTag();
        blockEntityNbt.putString("CustomName", "Chest A");

        RoadRollbackEntry first = ledger.record(routeId, edgeId, jobId, actorId, 100L,
                new BlockPos(0, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), blockEntityNbt);
        RoadRollbackEntry second = ledger.record(routeId, edgeId, jobId, actorId, 101L,
                new BlockPos(1, 64, 0), Blocks.OAK_LOG.defaultBlockState(), null);

        blockEntityNbt.putString("CustomName", "Mutated");
        List<RoadRollbackEntry> byJob = ledger.entriesForJob(jobId);

        assertEquals(List.of(second, first), byJob);
        assertEquals("Chest A", first.blockEntityNbt().orElseThrow().getString("CustomName"));
        assertEquals(List.of(second, first), ledger.entriesForEdge(edgeId));
        assertEquals(List.of(second, first), ledger.entriesForRoute(routeId));
        assertTrue(second.blockEntityNbt().isEmpty());
    }
}
