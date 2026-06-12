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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEditTaskServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void releasingSharedBlockKeepsBlockAndRemainingLedgerOwner() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        BlockPos shared = pos(0);
        data.putLedgerEntry(new RoadBlockLedgerEntry(
                shared,
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                Set.of("road-a:segment:0", "road-b:segment:0"),
                "road-a",
                100L));
        FakeBlocks blocks = new FakeBlocks();
        blocks.put(shared, Blocks.SMOOTH_STONE.defaultBlockState());
        RoadEditDiff diff = new RoadEditDiff("road-a",
                List.of(new RoadEditDiff.RemovedBlock("road-a:segment:0", shared)),
                List.of(),
                List.of(),
                List.of());
        RoadEditTaskService service = new RoadEditTaskService();
        UUID jobId = service.submit(diff);

        service.processBudgetedWorkForTest(data, blocks, 1, 200L);

        assertFalse(service.hasJob(jobId));
        assertEquals(Blocks.SMOOTH_STONE.defaultBlockState(), blocks.getBlockState(shared));
        RoadBlockLedgerEntry ledger = data.ledgerAt(shared).orElseThrow();
        assertEquals(Set.of("road-b:segment:0"), ledger.ownerSegmentIds());
    }

    @Test
    void playerModifiedRemovedBlockIsNotOverwritten() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        BlockPos removed = pos(0);
        data.putLedgerEntry(new RoadBlockLedgerEntry(
                removed,
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                Set.of("road-a:segment:0"),
                "road-a",
                100L));
        FakeBlocks blocks = new FakeBlocks();
        blocks.put(removed, Blocks.DIAMOND_BLOCK.defaultBlockState());
        RoadEditTaskService service = new RoadEditTaskService();
        UUID jobId = service.submit(new RoadEditDiff("road-a",
                List.of(new RoadEditDiff.RemovedBlock("road-a:segment:0", removed)),
                List.of(),
                List.of(),
                List.of()));

        service.processBudgetedWorkForTest(data, blocks, 1, 200L);

        assertFalse(service.hasJob(jobId));
        assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), blocks.getBlockState(removed));
        assertTrue(data.ledgerAt(removed).isEmpty());
    }

    @Test
    void perTickBudgetLimitsEditOperations() {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        BlockPos first = pos(0);
        BlockPos second = pos(1);
        BlockPos added = pos(2);
        data.putLedgerEntry(singleOwner(first));
        data.putLedgerEntry(singleOwner(second));
        FakeBlocks blocks = new FakeBlocks();
        blocks.put(first, Blocks.SMOOTH_STONE.defaultBlockState());
        blocks.put(second, Blocks.SMOOTH_STONE.defaultBlockState());
        blocks.put(added, Blocks.GRASS_BLOCK.defaultBlockState());
        RoadEditTaskService service = new RoadEditTaskService();
        UUID jobId = service.submit(new RoadEditDiff("road-a",
                List.of(
                        new RoadEditDiff.RemovedBlock("road-a:segment:0", first),
                        new RoadEditDiff.RemovedBlock("road-a:segment:0", second)),
                List.of(),
                List.of(new RoadEditBlockPlacement("road-a:segment:0", added, Blocks.SMOOTH_STONE.defaultBlockState())),
                List.of()));

        service.processBudgetedWorkForTest(data, blocks, 2, 200L);

        assertTrue(service.hasJob(jobId));
        assertEquals(Blocks.DIRT.defaultBlockState(), blocks.getBlockState(first));
        assertEquals(Blocks.DIRT.defaultBlockState(), blocks.getBlockState(second));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), blocks.getBlockState(added));

        service.processBudgetedWorkForTest(data, blocks, 2, 201L);

        assertFalse(service.hasJob(jobId));
        assertEquals(Blocks.SMOOTH_STONE.defaultBlockState(), blocks.getBlockState(added));
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), data.ledgerAt(added).orElseThrow().originalState());
    }

    private static RoadBlockLedgerEntry singleOwner(BlockPos pos) {
        return new RoadBlockLedgerEntry(
                pos,
                Blocks.DIRT.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(),
                Set.of("road-a:segment:0"),
                "road-a",
                100L);
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
