package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerPreviewRequestPacketTest {
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
}
