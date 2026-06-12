package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEditableNetworkSavedDataTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void editableRoadAndLedgerRoundTripWithIndexes() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadEditableRecord road = road();
        BlockPos ledgerPos = new BlockPos(1, 63, 0);
        RoadBlockLedgerEntry ledger = new RoadBlockLedgerEntry(
                ledgerPos,
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                Set.of("segment-a"),
                "road-a",
                200L);

        data.putRoad(road);
        data.putLedgerEntry(ledger);

        RoadEditableNetworkSavedData loaded = RoadEditableNetworkSavedData.load(data.save(new net.minecraft.nbt.CompoundTag()));

        Optional<RoadEditableRecord> loadedRoad = loaded.getRoad("road-a");
        assertTrue(loadedRoad.isPresent());
        assertEquals("town-a", loadedRoad.get().sourceTownId());
        assertEquals("town-b", loadedRoad.get().targetTownId());
        assertEquals(List.of("road-a"), loaded.roadsForTownConnection("town-b", "town-a").stream()
                .map(RoadEditableRecord::roadId)
                .toList());
        RoadBlockLedgerEntry loadedLedger = loaded.ledgerAt(ledgerPos).orElseThrow();
        assertEquals(Blocks.DIRT.defaultBlockState(), loadedLedger.originalState());
        assertEquals(Blocks.SMOOTH_STONE.defaultBlockState(), loadedLedger.roadState());
        assertEquals(1, loadedLedger.refCount());
        assertEquals(Set.of("segment-a"), loadedLedger.ownerSegmentIds());
    }

    @Test
    void blockLedgerReferenceCountChangesWhenOwnersChange() {
        RoadBlockLedgerEntry ledger = new RoadBlockLedgerEntry(
                new BlockPos(1, 63, 0),
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                Set.of("segment-a"),
                "road-a",
                100L);

        RoadBlockLedgerEntry shared = ledger.withOwner("segment-b", 110L);
        RoadBlockLedgerEntry released = shared.withoutOwner("segment-a", 120L);

        assertEquals(2, shared.refCount());
        assertEquals(Set.of("segment-b"), released.ownerSegmentIds());
        assertEquals(1, released.refCount());
    }

    private static RoadEditableRecord road() {
        RoadEditableNode source = new RoadEditableNode("node-a", new BlockPos(0, 64, 0),
                RoadEditableNode.Kind.SOURCE_TOWN, "Alpha");
        RoadEditableNode target = new RoadEditableNode("node-b", new BlockPos(8, 64, 0),
                RoadEditableNode.Kind.TARGET_TOWN, "Beta");
        RoadEditableSegment segment = new RoadEditableSegment(
                "segment-a",
                "node-a",
                "node-b",
                List.of(source.pos(), target.pos()),
                List.of(source.pos(), target.pos()),
                3,
                "ROAD",
                "minecraft:smooth_stone",
                List.of(new BlockPos(1, 63, 0).asLong()));
        return new RoadEditableRecord(
                "road-a",
                "edge-a",
                "minecraft:overworld",
                "nation-a",
                "town-a",
                "creator-uuid",
                "Builder",
                "town-a",
                "town-b",
                "Alpha",
                "Beta",
                3,
                "minecraft:smooth_stone",
                RoadEditableRecord.Status.BUILT,
                false,
                List.of(source, target),
                List.of(segment),
                100L,
                200L);
    }
}
