package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerEditorPacketRoundTripTest {
    @Test
    void renameDemolishAndGraphSyncPacketsRoundTrip() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        assertEquals(new RoadPlannerRenameRoadPacket(routeId, edgeId, "港口大道"),
                roundTrip(new RoadPlannerRenameRoadPacket(routeId, edgeId, "港口大道"), RoadPlannerRenameRoadPacket::encode, RoadPlannerRenameRoadPacket::decode));
        assertEquals(new RoadPlannerDemolishRoadPacket(routeId, edgeId, RoadPlannerDemolishRoadPacket.Scope.BRANCH),
                roundTrip(new RoadPlannerDemolishRoadPacket(routeId, edgeId, RoadPlannerDemolishRoadPacket.Scope.BRANCH), RoadPlannerDemolishRoadPacket::encode, RoadPlannerDemolishRoadPacket::decode));
        RoadPlannerGraphSyncPacket packet = new RoadPlannerGraphSyncPacket(routeId, List.of(
                new RoadPlannerGraphSyncPacket.EdgeDto(edgeId, nodeA, nodeB, "港口大道", "主城", "港口镇", 384, 5, CompiledRoadSectionType.BRIDGE)
        ));
        assertEquals(packet, roundTrip(packet, RoadPlannerGraphSyncPacket::encode, RoadPlannerGraphSyncPacket::decode));
    }

    private <T> T roundTrip(T packet, PacketEncoder<T> encoder, PacketDecoder<T> decoder) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        encoder.encode(packet, buffer);
        return decoder.decode(buffer);
    }

    @FunctionalInterface
    private interface PacketEncoder<T> {
        void encode(T packet, FriendlyByteBuf buffer);
    }

    @FunctionalInterface
    private interface PacketDecoder<T> {
        T decode(FriendlyByteBuf buffer);
    }
}
