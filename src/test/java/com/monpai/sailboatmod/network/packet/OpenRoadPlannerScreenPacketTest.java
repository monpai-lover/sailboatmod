package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlay;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenRoadPlannerScreenPacketTest {
    @Test
    void roundTripsSessionAndTownAnchors() {
        UUID sessionId = UUID.randomUUID();
        BlockPos source = new BlockPos(12, 64, 34);
        BlockPos destination = new BlockPos(240, 70, -80);
        List<RoadPlannerClaimOverlay> overlays = List.of(
                new RoadPlannerClaimOverlay(1, 2, "start", "Start", "nation", "Nation", RoadPlannerClaimOverlay.Role.START, 0x00AA00, 0x006600),
                new RoadPlannerClaimOverlay(8, 9, "dest", "Dest", "nation", "Nation", RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF3333, 0xAA0000)
        );
        OpenRoadPlannerScreenPacket packet = new OpenRoadPlannerScreenPacket(
                false,
                "Start Town",
                "dest_town",
                List.of(new RoadPlannerClientHooks.TargetEntry("dest_town", "Destination Town", 260)),
                sessionId,
                "start_town",
                source,
                destination,
                overlays
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OpenRoadPlannerScreenPacket.encode(packet, buffer);
        OpenRoadPlannerScreenPacket decoded = OpenRoadPlannerScreenPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(sessionId, decoded.sessionId());
        assertEquals("start_town", decoded.sourceTownId());
        assertEquals("dest_town", decoded.selectedTownId());
        assertEquals(source, decoded.sourceAnchor());
        assertEquals(destination, decoded.destinationAnchor());
        assertEquals(2, decoded.claimOverlays().size());
        assertEquals(RoadPlannerClaimOverlay.Role.DESTINATION, decoded.claimOverlays().get(1).role());
        assertEquals(8, decoded.claimOverlays().get(1).chunkX());
    }
}
