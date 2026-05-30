package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadNetworkGraphSavedDataTest {
    @Test
    void savesAndLoadsNodesEdgesPlacementsOwnedBlocksAndReuseSpans() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID sourceEdge = UUID.randomUUID();
        UUID edge = UUID.randomUUID();

        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        data.putNode(new RoadGraphNodeRecord(nodeA, "minecraft:overworld", new BlockPos(0, 64, 0),
                RoadGraphNodeRecord.Kind.TOWN_CONNECTION, "alpha", "town-a", "town:town-a", 10L, 11L));
        data.putNode(new RoadGraphNodeRecord(nodeB, "minecraft:overworld", new BlockPos(12, 64, 0),
                RoadGraphNodeRecord.Kind.JUNCTION, "alpha", "town-a", "", 10L, 11L));
        data.putEdge(new RoadGraphEdgeRecord(
                edge,
                nodeA,
                nodeB,
                "minecraft:overworld",
                "alpha",
                "town-a",
                "creator-uuid",
                "Planner",
                "Town A",
                "Town B",
                "Town A - Town B",
                5,
                CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT,
                List.of(new BlockPos(0, 64, 0), new BlockPos(6, 64, 0), new BlockPos(12, 64, 0)),
                List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0)),
                List.of(new RoadGraphSegmentPlacement(new BlockPos(6, 64, 0),
                        List.of(new BlockPos(6, 64, -1), new BlockPos(6, 64, 0), new BlockPos(6, 64, 1)))),
                List.of(new BlockPos(6, 64, -1), new BlockPos(6, 64, 0), new BlockPos(6, 64, 1)),
                List.of(new RoadGraphReuseSpan(sourceEdge, 2, 5, 0, 3,
                        new BlockPos(0, 64, 0), new BlockPos(6, 64, 0),
                        RoadGraphReuseSpan.Relationship.OWN)),
                20L,
                21L));

        CompoundTag saved = data.save(new CompoundTag());
        RoadNetworkGraphSavedData loaded = RoadNetworkGraphSavedData.load(saved);

        assertEquals(2, loaded.nodes().size());
        assertEquals(1, loaded.edges().size());
        RoadGraphEdgeRecord loadedEdge = loaded.getEdge(edge).orElseThrow();
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0)), loadedEdge.displayPath());
        assertEquals(1, loadedEdge.placements().size());
        assertEquals(3, loadedEdge.ownedBlockPositions().size());
        assertEquals(sourceEdge, loadedEdge.reuseSpans().get(0).sourceEdgeId());
        assertFalse(loaded.isDirtyForTest());
    }

    @Test
    void invalidEdgesWithMissingNodesAreIgnoredOnLoad() {
        UUID edge = UUID.randomUUID();
        CompoundTag saved = new RoadNetworkGraphSavedData()
                .withEdgeForTest(new RoadGraphEdgeRecord(edge, UUID.randomUUID(), UUID.randomUUID(),
                        "minecraft:overworld", "alpha", "", "", "", "", "", "broken", 3,
                        CompiledRoadSectionType.ROAD, RoadGraphEdgeRecord.Status.BUILT,
                        List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                        List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                        List.of(), List.of(), List.of(), 1L, 1L))
                .save(new CompoundTag());

        RoadNetworkGraphSavedData loaded = RoadNetworkGraphSavedData.load(saved);

        assertFalse(loaded.getEdge(edge).isPresent());
    }
}
