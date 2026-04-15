package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SyncManualRoadPlanningProgressPacketTest {
    @Test
    void roundTripsPlanningProgressPayload() {
        SyncManualRoadPlanningProgressPacket packet = new SyncManualRoadPlanningProgressPacket(
                9L,
                "Alpha",
                "Beta",
                "sampling_terrain",
                "采样地形",
                18,
                45,
                SyncManualRoadPlanningProgressPacket.Status.RUNNING
        );

        FriendlyByteBuf encoded = encode(packet);
        SyncManualRoadPlanningProgressPacket decoded = SyncManualRoadPlanningProgressPacket.decode(copy(encoded));
        FriendlyByteBuf roundTrip = encode(decoded);

        assertArrayEquals(toByteArray(encoded), toByteArray(roundTrip));
    }

    @Test
    void handleUpdatesDedicatedPlanningHudState() {
        RoadPlannerClientHooks.resetStateForTest();
        SyncManualRoadPlanningProgressPacket.handleClientForTest(new SyncManualRoadPlanningProgressPacket(
                11L,
                "Alpha",
                "Beta",
                "building_preview",
                "生成预览",
                94,
                70,
                SyncManualRoadPlanningProgressPacket.Status.SUCCESS
        ));

        RoadPlannerClientHooks.PlanningProgressState state = RoadPlannerClientHooks.activePlanningProgressForTest(System.currentTimeMillis());
        assertNotNull(state);
        assertEquals(11L, state.requestId());
        assertEquals("building_preview", state.stageKey());
        assertEquals(94, state.serverPercent());
    }

    private static FriendlyByteBuf encode(SyncManualRoadPlanningProgressPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncManualRoadPlanningProgressPacket.encode(packet, buffer);
        return buffer;
    }

    private static FriendlyByteBuf copy(FriendlyByteBuf buffer) {
        return new FriendlyByteBuf(buffer.copy());
    }

    private static byte[] toByteArray(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);
        return bytes;
    }
}
