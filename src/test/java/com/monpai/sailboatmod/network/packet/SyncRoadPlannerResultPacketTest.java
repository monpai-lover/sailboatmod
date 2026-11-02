package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncRoadPlannerResultPacketTest {
    @Test
    void roundTripsPlanningResultPayload() {
        SyncRoadPlannerResultPacket packet = new SyncRoadPlannerResultPacket(
                "alpha",
                "beta",
                List.of(
                        new SyncRoadPlannerResultPacket.OptionEntry("detour", "Detour", 24, false),
                        new SyncRoadPlannerResultPacket.OptionEntry("bridge", "Bridge", 17, true)
                ),
                "bridge"
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncRoadPlannerResultPacket.encode(packet, buffer);
        SyncRoadPlannerResultPacket decoded = SyncRoadPlannerResultPacket.decode(new FriendlyByteBuf(buffer.copy()));

        assertEquals(packet, decoded);
    }

    @Test
    void planningResultMovesClientIntoOptionSelectionPhase() {
        RoadPlannerClientHooks.resetStateForTest();

        SyncRoadPlannerResultPacket.handleClientForTest(new SyncRoadPlannerResultPacket(
                "alpha",
                "beta",
                List.of(
                        new SyncRoadPlannerResultPacket.OptionEntry("detour", "Detour", 24, false),
                        new SyncRoadPlannerResultPacket.OptionEntry("bridge", "Bridge", 17, true)
                ),
                "detour"
        ));

        assertEquals(RoadPlannerClientHooks.UiPhase.OPTION_SELECTION, RoadPlannerClientHooks.uiPhaseForTest());
        assertEquals(2, RoadPlannerClientHooks.latestPlanningResultForTest().options().size());
        assertEquals("detour", RoadPlannerClientHooks.latestPlanningResultForTest().selectedOptionId());
    }
}
