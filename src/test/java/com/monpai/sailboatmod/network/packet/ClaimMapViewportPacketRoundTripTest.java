package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimMapViewportPacketRoundTripTest {
    @Test
    void requestPacketRoundTripsViewportCoordinatesAndPrefetchRadius() {
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a",
                "minecraft:overworld",
                7L,
                8,
                40,
                -12,
                3
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RequestClaimMapViewportPacket.encode(packet, buffer);
        RequestClaimMapViewportPacket decoded = RequestClaimMapViewportPacket.decode(buffer);

        assertEquals(40, decoded.centerChunkX());
        assertEquals(-12, decoded.centerChunkZ());
        assertEquals(3, decoded.prefetchRadius());
    }

    @Test
    void syncPacketRoundTripsFullViewportPixels() {
        SyncClaimPreviewMapPacket packet = new SyncClaimPreviewMapPacket(
                SyncClaimPreviewMapPacket.ScreenKind.NATION,
                new ClaimMapViewportSnapshot("minecraft:overworld", 11L, 6, 2, 3, List.of(0xFF010203, 0xFF0A0B0C))
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncClaimPreviewMapPacket.encode(packet, buffer);
        SyncClaimPreviewMapPacket decoded = SyncClaimPreviewMapPacket.decode(buffer);

        assertEquals(11L, decoded.snapshot().revision());
        assertEquals(List.of(0xFF010203, 0xFF0A0B0C), decoded.snapshot().pixels());
    }
}
