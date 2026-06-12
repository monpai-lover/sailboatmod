package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEditCommitServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void queueEditMarksRoadEditingAndCompletionMarksBuilt() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadEditableRecord current = road(List.of(pos(0)), RoadEditableRecord.Status.BUILT);
        data.putRoad(current);
        data.putLedgerEntry(new RoadBlockLedgerEntry(
                pos(0),
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                java.util.Set.of("road-a:segment:0"),
                "road-a",
                100L));
        RoadEditableSegment proposedSegment = segment("road-a:segment:0", List.of(pos(1)));
        RoadEditTaskService tasks = new RoadEditTaskService();

        RoadEditCommitService.Result result = new RoadEditCommitService()
                .queueEdit(data, current, List.of(proposedSegment),
                        List.of(new RoadEditBlockPlacement("road-a:segment:0", pos(1), Blocks.SMOOTH_STONE.defaultBlockState())),
                        tasks,
                        300L);

        assertTrue(result.success());
        assertTrue(result.jobId().isPresent());
        assertEquals(RoadEditableRecord.Status.EDITING, data.getRoad("road-a").orElseThrow().status());

        FakeBlocks blocks = new FakeBlocks();
        blocks.put(pos(0), Blocks.SMOOTH_STONE.defaultBlockState());
        blocks.put(pos(1), Blocks.GRASS_BLOCK.defaultBlockState());
        tasks.processBudgetedWorkForTest(data, blocks, 8, 301L);

        RoadEditableRecord completed = data.getRoad("road-a").orElseThrow();
        assertEquals(RoadEditableRecord.Status.BUILT, completed.status());
        assertEquals(List.of(pos(1).asLong()), completed.segments().get(0).blockPositions());
        assertFalse(tasks.hasJob(result.jobId().orElseThrow()));
    }

    @Test
    void conflictingEditDoesNotQueueJob() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadEditableRecord current = road(List.of(pos(0)), RoadEditableRecord.Status.BUILT);
        RoadEditableSegment proposedSegment = segment("road-a:segment:0", List.of(pos(1)));
        RoadEditTaskService tasks = new RoadEditTaskService();

        RoadEditCommitService.Result result = new RoadEditCommitService()
                .queueEdit(data, current, List.of(proposedSegment),
                        List.of(
                                new RoadEditBlockPlacement("road-a:segment:0", pos(1), Blocks.SMOOTH_STONE.defaultBlockState()),
                                new RoadEditBlockPlacement("road-a:segment:0", pos(1), Blocks.DIAMOND_BLOCK.defaultBlockState())),
                        tasks,
                        300L);

        assertFalse(result.success());
        assertEquals(Optional.empty(), result.jobId());
        assertEquals(RoadEditableRecord.Status.BUILT, current.status());
    }

    @Test
    void queueEditStoresProposedNodesOnCompletion() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadEditableRecord current = road(List.of(pos(0)), RoadEditableRecord.Status.BUILT);
        data.putRoad(current);
        data.putLedgerEntry(new RoadBlockLedgerEntry(
                pos(0),
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                java.util.Set.of("road-a:segment:0"),
                "road-a",
                100L));
        List<RoadEditableNode> proposedNodes = List.of(
                new RoadEditableNode("road-a:node:0", pos(0), RoadEditableNode.Kind.SOURCE_TOWN, "Alpha"),
                new RoadEditableNode("road-a:node:1", pos(8), RoadEditableNode.Kind.NORMAL, ""),
                new RoadEditableNode("road-a:node:2", pos(16), RoadEditableNode.Kind.TARGET_TOWN, "Beta"));

        RoadEditCommitService.Result result = new RoadEditCommitService()
                .queueEdit(data, current, proposedNodes, List.of(segment("road-a:segment:0", List.of(pos(0)))),
                        List.of(new RoadEditBlockPlacement("road-a:segment:0", pos(0), Blocks.SMOOTH_STONE.defaultBlockState())),
                        new RoadEditTaskService(),
                        300L);

        assertTrue(result.success());
        assertEquals(proposedNodes, data.getRoad("road-a").orElseThrow().nodes());
        assertEquals(RoadEditableRecord.Status.BUILT, data.getRoad("road-a").orElseThrow().status());
    }

    @Test
    void queueEditStoresProposedWidthAndMaterial() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        RoadEditableRecord current = road(List.of(pos(0)), RoadEditableRecord.Status.BUILT);
        data.putRoad(current);
        data.putLedgerEntry(new RoadBlockLedgerEntry(
                pos(0),
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                java.util.Set.of("road-a:segment:0"),
                "road-a",
                100L));

        RoadEditCommitService.Result result = new RoadEditCommitService()
                .queueEdit(data, current, current.nodes(),
                        List.of(segment("road-a:segment:0", List.of(pos(0)), 7, "cobblestone")),
                        List.of(new RoadEditBlockPlacement("road-a:segment:0", pos(0), Blocks.SMOOTH_STONE.defaultBlockState())),
                        new RoadEditTaskService(),
                        300L);

        RoadEditableRecord completed = data.getRoad("road-a").orElseThrow();
        assertTrue(result.success());
        assertEquals(7, completed.width());
        assertEquals("cobblestone", completed.materialId());
    }

    private static RoadEditableRecord road(List<BlockPos> blockPositions, RoadEditableRecord.Status status) {
        return new RoadEditableRecord(
                "road-a",
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
                status,
                false,
                List.of(
                        new RoadEditableNode("road-a:source", pos(0), RoadEditableNode.Kind.SOURCE_TOWN, "Alpha"),
                        new RoadEditableNode("road-a:target", pos(16), RoadEditableNode.Kind.TARGET_TOWN, "Beta")),
                List.of(segment("road-a:segment:0", blockPositions)),
                100L,
                200L);
    }

    private static RoadEditableSegment segment(String segmentId, List<BlockPos> blockPositions) {
        return segment(segmentId, blockPositions, 3, "minecraft:smooth_stone");
    }

    private static RoadEditableSegment segment(String segmentId, List<BlockPos> blockPositions, int width, String materialId) {
        return new RoadEditableSegment(
                segmentId,
                "road-a:source",
                "road-a:target",
                List.of(pos(0), pos(16)),
                List.of(pos(0), pos(16)),
                width,
                "ROAD",
                materialId,
                blockPositions.stream().map(BlockPos::asLong).toList());
    }

    private static BlockPos pos(int x) {
        return new BlockPos(x, 63, 0);
    }

    private static final class FakeBlocks implements RoadEditTaskService.BlockAccess {
        private final LinkedHashMap<BlockPos, BlockState> states = new LinkedHashMap<>();

        void put(BlockPos pos, BlockState state) {
            states.put(pos.immutable(), state);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public void setBlock(BlockPos pos, BlockState state) {
            states.put(pos.immutable(), state);
        }
    }
}
