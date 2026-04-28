package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPreviewRequestPacketTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsNodesAndSegmentTypes() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "Starter Town",
                "Target Town",
                List.of(BlockPos.ZERO, new BlockPos(32, 64, 0), new BlockPos(128, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                new RoadPlannerBuildSettings(7, "stone_bricks", true)
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerPreviewRequestPacket.encode(packet, buffer);
        RoadPlannerPreviewRequestPacket decoded = RoadPlannerPreviewRequestPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(packet.startTownName(), decoded.startTownName());
        assertEquals(packet.destinationTownName(), decoded.destinationTownName());
        assertEquals(packet.nodes(), decoded.nodes());
        assertEquals(packet.segmentTypes(), decoded.segmentTypes());
        assertEquals(packet.settings(), decoded.settings());
    }

    @Test
    void padsMissingSegmentTypesAsRoad() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(BlockPos.ZERO, new BlockPos(16, 64, 0), new BlockPos(32, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertEquals(List.of(RoadPlannerSegmentType.BRIDGE_SMALL, RoadPlannerSegmentType.ROAD), packet.segmentTypes());
    }

    @Test
    void placeholderPreviewUsesCompiledBuildStepsForBridgeDeckGhosts() {
        RoadPlannerPreviewRequestPacket packet = new RoadPlannerPreviewRequestPacket(
                "A",
                "B",
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                new RoadPlannerBuildSettings(5, "stone_bricks", false)
        );

        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = packet.toPreviewPacketForTest().ghostBlocks();

        assertTrue(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.SPRUCE_PLANKS));
        assertFalse(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.DIRT));
        assertFalse(ghostBlocks.stream().anyMatch(block -> block.state().getBlock() == Blocks.COBBLESTONE));
    }

}
