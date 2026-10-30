package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.map.route.*;
import com.monpai.sailboatmod.network.packet.maproute.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MapRoutePacketTest {
    @Test void finiteDoubleCoordinatesRoundTripBeyondPackedBlockPositionRange() {
        var request = new MapRouteViewportRequestPacket(UUID.randomUUID(), 5, "world", "minecraft:overworld", new MapRouteViewport(-1e10, -12, 1e10, 12, 0.1));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MapRouteViewportRequestPacket.encode(request, buffer);
            assertEquals(request, MapRouteViewportRequestPacket.decode(buffer));
            var entry = new MapRouteEntry("sea", MapRouteKind.SEA, "航线", "A", "B", 99,
                    List.of(List.of(new MapRoutePoint(-1e10, 0.125), new MapRoutePoint(1e10, -0.125))));
            var response = new MapRouteSnapshotPacket(request.sessionId(), request.requestId(), request.worldId(), request.dimensionId(), new MapRouteSnapshot(List.of(entry), false));
            buffer.clear();
            MapRouteSnapshotPacket.encode(response, buffer);
            assertTrue(buffer.readableBytes() < 128 * 1024);
            assertEquals(response, MapRouteSnapshotPacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void malformedRouteCountIsRejectedBeforeAllocation() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(UUID.randomUUID()); buffer.writeLong(1); buffer.writeUtf("world"); buffer.writeUtf("minecraft:overworld");
            buffer.writeBoolean(false); buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> MapRouteSnapshotPacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void invalidDimensionIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MapRouteViewportRequestPacket(UUID.randomUUID(), 1, "world", "INVALID DIMENSION", new MapRouteViewport(0, 0, 10, 10, 1)));
    }

    @Test void malformedNestedPointCountIsRejectedBeforeAllocation() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(UUID.randomUUID()); buffer.writeLong(1); buffer.writeUtf("world"); buffer.writeUtf("minecraft:overworld");
            buffer.writeBoolean(false); buffer.writeVarInt(1);
            buffer.writeUtf("route"); buffer.writeByte(0); buffer.writeUtf("R"); buffer.writeUtf("A"); buffer.writeUtf("B");
            buffer.writeDouble(10); buffer.writeVarInt(1); buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> MapRouteSnapshotPacket.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void aggregateEncodedBytesStayBelowThePayloadBudgetWithLongUnicodeMetadata() {
        String text = "航".repeat(256);
        var routes = new java.util.ArrayList<MapRouteEntry>();
        for (int i = 0; i < 128; i++) routes.add(new MapRouteEntry("route" + i, MapRouteKind.SEA, text, text, text, 10,
                List.of(List.of(new MapRoutePoint(0, 5), new MapRoutePoint(10, 5)))));
        var snapshot = MapRouteCatalog.viewport(routes, new MapRouteViewport(0, 0, 10, 10, 1));
        var packet = new MapRouteSnapshotPacket(UUID.randomUUID(), 1, "world", "minecraft:overworld", snapshot);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MapRouteSnapshotPacket.encode(packet, buffer);
            assertTrue(snapshot.truncated());
            assertTrue(buffer.readableBytes() < 128 * 1024);
            assertEquals(packet, MapRouteSnapshotPacket.decode(buffer));
        } finally { buffer.release(); }
    }
}
