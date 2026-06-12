package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadEditDiffPlannerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void plansRemovedKeptAndAddedBlocks() {
        RoadEditableRecord current = road("road-a", "road-a:segment:0",
                List.of(pos(0), pos(1), pos(2)));
        List<RoadEditBlockPlacement> proposed = List.of(
                placement("road-a:segment:0", pos(1)),
                placement("road-a:segment:0", pos(2)),
                placement("road-a:segment:0", pos(3)));

        RoadEditDiff diff = RoadEditDiffPlanner.plan(current, proposed);

        assertEquals(List.of(pos(0)), diff.removedBlocks().stream().map(RoadEditDiff.RemovedBlock::pos).toList());
        assertEquals(List.of(pos(1), pos(2)), diff.keptBlocks().stream().map(RoadEditDiff.KeptBlock::pos).toList());
        assertEquals(List.of(pos(3)), diff.addedBlocks().stream().map(RoadEditBlockPlacement::pos).toList());
        assertEquals(List.of(), diff.conflicts());
    }

    @Test
    void emptyNewPlanRemovesAllBlocks() {
        RoadEditableRecord current = road("road-a", "road-a:segment:0",
                List.of(pos(0), pos(1)));

        RoadEditDiff diff = RoadEditDiffPlanner.plan(current, List.of());

        assertEquals(List.of(pos(0), pos(1)), diff.removedBlocks().stream().map(RoadEditDiff.RemovedBlock::pos).toList());
        assertEquals(List.of(), diff.keptBlocks());
        assertEquals(List.of(), diff.addedBlocks());
    }

    @Test
    void duplicateNewPlacementWithDifferentStateIsConflict() {
        RoadEditableRecord current = road("road-a", "road-a:segment:0", List.of(pos(0)));
        RoadEditBlockPlacement stone = placement("road-a:segment:0", pos(1));
        RoadEditBlockPlacement diamond = new RoadEditBlockPlacement(
                "road-a:segment:0",
                pos(1),
                Blocks.DIAMOND_BLOCK.defaultBlockState());

        RoadEditDiff diff = RoadEditDiffPlanner.plan(current, List.of(stone, diamond));

        assertEquals(List.of(pos(1)), diff.conflicts().stream().map(RoadEditDiff.Conflict::pos).toList());
        assertEquals(List.of(), diff.addedBlocks());
    }

    private static RoadEditBlockPlacement placement(String segmentId, BlockPos pos) {
        return new RoadEditBlockPlacement(segmentId, pos, Blocks.SMOOTH_STONE.defaultBlockState());
    }

    private static RoadEditableRecord road(String roadId, String segmentId, List<BlockPos> blockPositions) {
        RoadEditableNode source = new RoadEditableNode(roadId + ":source", pos(0),
                RoadEditableNode.Kind.SOURCE_TOWN, "Alpha");
        RoadEditableNode target = new RoadEditableNode(roadId + ":target", pos(16),
                RoadEditableNode.Kind.TARGET_TOWN, "Beta");
        RoadEditableSegment segment = new RoadEditableSegment(
                segmentId,
                source.nodeId(),
                target.nodeId(),
                List.of(source.pos(), target.pos()),
                List.of(source.pos(), target.pos()),
                3,
                "ROAD",
                "minecraft:smooth_stone",
                blockPositions.stream().map(BlockPos::asLong).toList());
        return new RoadEditableRecord(
                roadId,
                "",
                "minecraft:overworld",
                "nation-a",
                "town-a",
                "creator",
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

    private static BlockPos pos(int x) {
        return new BlockPos(x, 63, 0);
    }
}
