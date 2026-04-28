package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerAutoCompletePacketTest {
    @Test
    void resultPacketRoundTripsNodesAndSegmentTypes() {
        RoadPlannerAutoCompleteResultPacket packet = new RoadPlannerAutoCompleteResultPacket(
                UUID.randomUUID(), true,
                List.of(BlockPos.ZERO, new BlockPos(8, 64, 8)),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
                "ok"
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RoadPlannerAutoCompleteResultPacket.encode(packet, buffer);
        RoadPlannerAutoCompleteResultPacket decoded = RoadPlannerAutoCompleteResultPacket.decode(buffer);

        assertEquals(packet.sessionId(), decoded.sessionId());
        assertEquals(packet.nodes(), decoded.nodes());
        assertEquals(packet.segmentTypes(), decoded.segmentTypes());
        assertEquals(packet.message(), decoded.message());
    }
}
