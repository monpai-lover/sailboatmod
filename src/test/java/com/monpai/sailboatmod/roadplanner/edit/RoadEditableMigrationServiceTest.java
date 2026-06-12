package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEditableMigrationServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void completedRoadCreatesEditableLedgerFromBuildSteps() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadNetworkRecord road = road("road-a", "town-a", "town-b");
        BlockPos first = new BlockPos(0, 63, 0);
        BlockPos second = new BlockPos(1, 63, 0);
        List<BuildStep> buildSteps = List.of(
                new BuildStep(0, first, Blocks.SMOOTH_STONE.defaultBlockState(), BuildPhase.SURFACE),
                new BuildStep(1, second, Blocks.OAK_FENCE.defaultBlockState(), BuildPhase.RAILING));
        List<ConstructionQueue.RollbackEntry> rollbackEntries = List.of(
                new ConstructionQueue.RollbackEntry(first, Blocks.DIRT.defaultBlockState()),
                new ConstructionQueue.RollbackEntry(second, Blocks.AIR.defaultBlockState()));

        RoadEditableMigrationService.registerCompletedRoad(data, road, buildSteps, rollbackEntries, 3,
                "minecraft:smooth_stone", 300L);

        RoadEditableRecord editable = data.getRoad("road-a").orElseThrow();
        assertEquals("town-a", editable.sourceTownId());
        assertEquals("town-b", editable.targetTownId());
        assertEquals(1, editable.segments().size());
        assertEquals(List.of(first.asLong(), second.asLong()), editable.segments().get(0).blockPositions());
        RoadBlockLedgerEntry firstLedger = data.ledgerAt(first).orElseThrow();
        assertEquals(Blocks.DIRT.defaultBlockState(), firstLedger.originalState());
        assertEquals(Blocks.SMOOTH_STONE.defaultBlockState(), firstLedger.roadState());
        assertEquals(1, firstLedger.refCount());
    }

    @Test
    void legacyMigrationCreatesCurrentStateSnapshotOnce() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadNetworkRecord road = road("legacy-road", "town-a", "town-b");

        Optional<RoadEditableRecord> first = RoadEditableMigrationService.ensureLegacyLedger(
                data,
                road,
                pos -> Blocks.COBBLESTONE.defaultBlockState(),
                100L);
        Optional<RoadEditableRecord> second = RoadEditableMigrationService.ensureLegacyLedger(
                data,
                road,
                pos -> Blocks.DIAMOND_BLOCK.defaultBlockState(),
                200L);

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(1, data.roads().size());
        assertTrue(second.get().legacyMigrated());
        RoadBlockLedgerEntry ledger = data.ledgerAt(road.path().get(0)).orElseThrow();
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), ledger.originalState());
        assertEquals(Blocks.COBBLESTONE.defaultBlockState(), ledger.roadState());
        assertEquals(100L, ledger.updatedAt());
    }

    private static RoadNetworkRecord road(String roadId, String sourceTownId, String targetTownId) {
        return new RoadNetworkRecord(
                roadId,
                "nation-a",
                sourceTownId,
                "minecraft:overworld",
                "town:" + sourceTownId,
                "town:" + targetTownId,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                100L,
                100L,
                UUID.randomUUID().toString(),
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta",
                sourceTownId,
                targetTownId);
    }
}
