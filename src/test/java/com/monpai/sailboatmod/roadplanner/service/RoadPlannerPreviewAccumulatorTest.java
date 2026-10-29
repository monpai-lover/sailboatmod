package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerPreviewAccumulatorTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test
    void aFullLongRoadOverviewAndNearbyGhostsFitOneBoundedPayload() throws Exception {
        RoadPlannerStagedPlan plan = new RoadPlannerStagedPlan(List.of(new BlockPos(0, 64, 0), new BlockPos(40_000, 64, 0)), List.of(RoadPlannerSegmentType.ROAD));
        Object accumulator;
        try {
            accumulator = Class.forName("com.monpai.sailboatmod.roadplanner.service.RoadPlannerPreviewAccumulator")
                    .getConstructor(RoadPlannerStagedPlan.class, String.class, String.class, BlockPos.class)
                    .newInstance(plan, "A", "B", new BlockPos(0, 64, 0));
        } catch (ClassNotFoundException missing) {
            accumulator = fail("long previews must stream bounded geometry instead of sending the full road's ghost blocks");
        }
        for (int stageIndex = 0; stageIndex < plan.totalStages(); stageIndex++) {
            var stage = plan.stage(stageIndex);
            List<SyncRoadPlannerPreviewPacket.GhostBlock> blocks = new ArrayList<>();
            for (int x = stage.coreFrom().getX(); x < stage.coreTo().getX(); x++) {
                blocks.add(new SyncRoadPlannerPreviewPacket.GhostBlock(new BlockPos(x, 63, 0), Blocks.STONE.defaultBlockState()));
            }
            var update = new SyncRoadPlannerPreviewPacket("A", "B", blocks, List.of(stage.coreFrom(), stage.coreTo()),
                    40_001, stage.coreFrom(), stage.coreTo(), stage.coreFrom(), false, List.of(), "", List.of());
            accumulator.getClass().getMethod("add", RoadPlannerStagedPlan.Stage.class, SyncRoadPlannerPreviewPacket.class).invoke(accumulator, stage, update);
        }
        var result = (SyncRoadPlannerPreviewPacket) accumulator.getClass().getMethod("snapshot", boolean.class).invoke(accumulator, true);
        assertTrue(result.ghostBlocks().size() <= 2048);
        assertTrue(result.pathNodes().size() <= 64);
        assertEquals(0, result.pathNodes().get(0).getX());
        assertEquals(40_000, result.pathNodes().get(result.pathNodes().size() - 1).getX());
        assertTrue(result.ghostBlocks().stream().allMatch(block -> block.pos().getX() < 2048), "nearby ghosts are retained as remote stages complete");
        assertTrue(result.awaitingConfirmation());
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SyncRoadPlannerPreviewPacket.encode(result, bytes);
            assertTrue(bytes.readableBytes() < 768 * 1024, "one update stays below the NeoForge client payload bound");
        } finally { bytes.release(); }
    }
}
