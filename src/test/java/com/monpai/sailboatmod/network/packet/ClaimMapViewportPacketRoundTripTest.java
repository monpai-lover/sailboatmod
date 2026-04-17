package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimMapViewportSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void serverClampBoundsRadiusAndPrefetchRadius() {
        int clamped = RequestClaimMapViewportPacket.clampRadiusForServer(Integer.MAX_VALUE);
        assertTrue(clamped >= 0);
        assertTrue(clamped <= Integer.MAX_VALUE);
        assertEquals(0, RequestClaimMapViewportPacket.clampRadiusForServer(-12));
        assertEquals(RequestClaimMapViewportPacket.MAX_SERVER_PREFETCH_RADIUS, RequestClaimMapViewportPacket.clampPrefetchRadiusForServer(Integer.MAX_VALUE));
        assertEquals(0, RequestClaimMapViewportPacket.clampPrefetchRadiusForServer(-8));
    }

    @Test
    void completeOnlySnapshotReturnsNullWhenVisibleChunkIsMissing() {
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.TOWN,
                "town-a",
                "minecraft:overworld",
                9L,
                1,
                0,
                0,
                1
        );

        ClaimMapViewportSnapshot snapshot = RequestClaimMapViewportPacket.tryBuildCompleteSnapshot(
                packet,
                "minecraft:overworld",
                (dimensionId, chunkX, chunkZ) -> (chunkX == 0 && chunkZ == 0) ? null : new int[] {0xFF112233},
                1,
                1
        );

        assertNull(snapshot);
    }

    @Test
    void completeOnlySnapshotReturnsPixelsWhenVisibleAreaComplete() {
        RequestClaimMapViewportPacket packet = new RequestClaimMapViewportPacket(
                RequestClaimMapViewportPacket.ScreenKind.NATION,
                "nation-a",
                "minecraft:overworld",
                15L,
                1,
                10,
                20,
                2
        );

        ClaimMapViewportSnapshot snapshot = RequestClaimMapViewportPacket.tryBuildCompleteSnapshot(
                packet,
                "minecraft:overworld",
                (dimensionId, chunkX, chunkZ) -> new int[] {chunkX * 100 + chunkZ},
                1,
                2
        );

        assertNotNull(snapshot);
        assertEquals(15L, snapshot.revision());
        assertEquals(1, snapshot.radius());
        assertEquals(10, snapshot.centerChunkX());
        assertEquals(20, snapshot.centerChunkZ());
        assertEquals(9, snapshot.pixels().size());
    }

    @Test
    void refreshVisibleFootprintEnumeratesSquareArea() {
        java.util.Set<Long> visited = new java.util.HashSet<>();
        RefreshClaimMapViewportPacket.forEachVisibleChunk(3, -4, 1, (chunkX, chunkZ) ->
                visited.add(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL))
        );

        assertEquals(9, visited.size());
        assertTrue(visited.contains(((long) 3 << 32) ^ (-4 & 0xffffffffL)));
        assertTrue(visited.contains(((long) 2 << 32) ^ (-5 & 0xffffffffL)));
        assertTrue(visited.contains(((long) 4 << 32) ^ (-3 & 0xffffffffL)));
    }
}
