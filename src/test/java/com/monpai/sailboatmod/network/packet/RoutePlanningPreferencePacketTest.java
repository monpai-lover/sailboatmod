package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.route.water.RoutePlanningPreference;
import com.monpai.sailboatmod.route.water.WaterMidMode;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoutePlanningPreferencePacketTest {
    @Test
    void destinationRequestsCarryThePlanningPreferenceAndDefaultToReuse() {
        for (RoutePlanningPreference preference : RoutePlanningPreference.values()) {
            DepartForDestinationPacket sent = new DepartForDestinationPacket(new BlockPos(1, 63, 2), new BlockPos(900, 63, -40),
                    UUID.randomUUID(), 3, UUID.randomUUID(), preference);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            DepartForDestinationPacket.encode(sent, buf);
            assertEquals(sent, DepartForDestinationPacket.decode(buf));
            assertEquals(0, buf.readableBytes());
        }
        assertEquals(RoutePlanningPreference.REUSE_FIRST,
                new DepartForDestinationPacket(BlockPos.ZERO, BlockPos.ZERO, UUID.randomUUID(), -1, UUID.randomUUID()).preference());
    }

    @Test
    void routeCreationRequestsCarryThePlanningPreference() {
        for (RoutePlanningPreference preference : RoutePlanningPreference.values()) {
            CreateAutoRoutePacket sent = new CreateAutoRoutePacket(new BlockPos(5, 63, 6), new BlockPos(-700, 63, 80),
                    WaterMidMode.NBT, UUID.randomUUID(), preference);
            FriendlyByteBuf first = new FriendlyByteBuf(Unpooled.buffer());
            CreateAutoRoutePacket.encode(sent, first);
            byte[] bytes = new byte[first.readableBytes()];
            first.getBytes(first.readerIndex(), bytes);
            CreateAutoRoutePacket decoded = CreateAutoRoutePacket.decode(first);
            assertEquals(0, first.readableBytes());
            FriendlyByteBuf second = new FriendlyByteBuf(Unpooled.buffer());
            CreateAutoRoutePacket.encode(decoded, second);
            byte[] again = new byte[second.readableBytes()];
            second.getBytes(second.readerIndex(), again);
            assertArrayEquals(bytes, again, "preference " + preference + " survives the round trip");
        }
    }

    @Test
    void unknownPreferenceIdsFallBackToOptimal() {
        assertEquals(RoutePlanningPreference.OPTIMAL, RoutePlanningPreference.fromId(7));
        assertEquals(RoutePlanningPreference.REUSE_FIRST, RoutePlanningPreference.fromId(1));
    }
}
