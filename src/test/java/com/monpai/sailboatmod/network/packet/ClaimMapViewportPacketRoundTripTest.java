package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimMapViewportPacketRoundTripTest {
    @Test
    void requestPacketRoundTripsAllFields() {
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

        assertEquals(RequestClaimMapViewportPacket.ScreenKind.TOWN, decoded.screenKind());
        assertEquals("town-a", decoded.ownerId());
        assertEquals("minecraft:overworld", decoded.dimensionId());
        assertEquals(7L, decoded.revision());
        assertEquals(8, decoded.radius());
        assertEquals(40, decoded.centerChunkX());
        assertEquals(-12, decoded.centerChunkZ());
        assertEquals(3, decoded.prefetchRadius());
    }

    @Test
    void refreshPacketRoundTripsAllFields() {
        RefreshClaimMapViewportPacket packet = new RefreshClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.NATION,
                "nation-a",
                "minecraft:the_nether",
                23L,
                5,
                -31,
                72
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        RefreshClaimMapViewportPacket.encode(packet, buffer);
        RefreshClaimMapViewportPacket decoded = RefreshClaimMapViewportPacket.decode(buffer);

        assertEquals(RequestClaimMapViewportPacket.ScreenKind.NATION, decoded.screenKind());
        assertEquals("nation-a", decoded.ownerId());
        assertEquals("minecraft:the_nether", decoded.dimensionId());
        assertEquals(23L, decoded.revision());
        assertEquals(5, decoded.radius());
        assertEquals(-31, decoded.centerChunkX());
        assertEquals(72, decoded.centerChunkZ());
    }

    @Test
    void syncPacketRoundTripsAllSnapshotFields() {
        SyncClaimPreviewMapPacket packet = new SyncClaimPreviewMapPacket(
                SyncClaimPreviewMapPacket.ScreenKind.NATION,
                new ClaimMapViewportSnapshot("minecraft:overworld", 11L, 6, 2, 3, List.of(0xFF010203, 0xFF0A0B0C))
        );

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        SyncClaimPreviewMapPacket.encode(packet, buffer);
        SyncClaimPreviewMapPacket decoded = SyncClaimPreviewMapPacket.decode(buffer);

        assertEquals(SyncClaimPreviewMapPacket.ScreenKind.NATION, decoded.screenKind());
        assertEquals("minecraft:overworld", decoded.snapshot().dimensionId());
        assertEquals(11L, decoded.snapshot().revision());
        assertEquals(6, decoded.snapshot().radius());
        assertEquals(2, decoded.snapshot().centerChunkX());
        assertEquals(3, decoded.snapshot().centerChunkZ());
        assertEquals(List.of(0xFF010203, 0xFF0A0B0C), decoded.snapshot().pixels());
    }
}
