package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadDemolitionPlannerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void edgeDemolitionRestoresLedgerEntriesInReverseOrder() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        RoadRollbackLedger ledger = ledgerWithTwoEntries(routeId, edgeId);

        RoadDemolitionJob job = new RoadDemolitionPlanner().planEdge(routeId, edgeId, ledger, Map.of());

        assertEquals(RoadDemolitionJob.Status.PLANNED, job.status());
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(0, 64, 0)), job.restoreEntries().stream().map(RoadRollbackEntry::pos).toList());
    }

    @Test
    void branchDemolitionCollectsDescendantEdges() {
        UUID routeId = UUID.randomUUID();
        UUID rootEdge = UUID.randomUUID();
        UUID childEdge = UUID.randomUUID();
        UUID grandChildEdge = UUID.randomUUID();

        Set<UUID> descendants = new RoadDemolitionPlanner().collectBranchEdges(rootEdge, Map.of(
                rootEdge, List.of(childEdge),
                childEdge, List.of(grandChildEdge)
        ));

        assertEquals(Set.of(rootEdge, childEdge, grandChildEdge), descendants);
    }

    @Test
    void conflictsWarnAndDoNotOverwritePlayerEdits() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        RoadRollbackLedger ledger = ledgerWithTwoEntries(routeId, edgeId);
        Map<BlockPos, Boolean> conflicts = Map.of(new BlockPos(1, 64, 0), true);

        RoadDemolitionJob job = new RoadDemolitionPlanner().planEdge(routeId, edgeId, ledger, conflicts);

        assertEquals(1, job.restoreEntries().size());
        assertEquals(1, job.issues().size());
        assertTrue(job.issues().get(0).blocking());
    }

    private RoadRollbackLedger ledgerWithTwoEntries(UUID routeId, UUID edgeId) {
        RoadRollbackLedger ledger = new RoadRollbackLedger();
        UUID jobId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ledger.record(routeId, edgeId, jobId, actorId, 0L, new BlockPos(0, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), null);
        ledger.record(routeId, edgeId, jobId, actorId, 1L, new BlockPos(1, 64, 0), Blocks.OAK_LOG.defaultBlockState(), null);
        return ledger;
    }
}
