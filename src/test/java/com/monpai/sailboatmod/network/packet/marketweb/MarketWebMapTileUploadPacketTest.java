package com.monpai.sailboatmod.network.packet.marketweb;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapTileUploadPacketTest {
    private static final int FORGE_CUSTOM_PAYLOAD_LIMIT = 32_767;

    @Test
    void packetRoundTripsTileChunkPayload() {
        byte[] chunk = new byte[] {1, 2, 3, 4, 5};
        MarketWebMapTileUploadPacket original = new MarketWebMapTileUploadPacket(
                "minecraft:overworld", MapLod.LOD_1, 2, -3, 42, 12345, 1, 3, chunk);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MarketWebMapTileUploadPacket.encode(original, buffer);
        MarketWebMapTileUploadPacket decoded = MarketWebMapTileUploadPacket.decode(buffer);

        assertEquals("minecraft:overworld", decoded.dimensionId());
        assertEquals(MapLod.LOD_1, decoded.lod());
        assertEquals(2, decoded.tileX());
        assertEquals(-3, decoded.tileZ());
        assertEquals(42, decoded.uploadId());
        assertEquals(12345, decoded.totalBytes());
        assertEquals(1, decoded.chunkIndex());
        assertEquals(3, decoded.chunkCount());
        assertArrayEquals(chunk, decoded.chunkBytes());
    }

    @Test
    void encodedChunkStaysBelowForgeCustomPayloadLimit() {
        byte[] chunk = new byte[28 * 1024];
        MarketWebMapTileUploadPacket packet = new MarketWebMapTileUploadPacket(
                "minecraft:overworld", MapLod.LOD_1, 0, 0, 7, chunk.length, 0, 1, chunk);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MarketWebMapTileUploadPacket.encode(packet, buffer);

        assertTrue(buffer.readableBytes() < FORGE_CUSTOM_PAYLOAD_LIMIT,
                "Market web tile upload chunks must stay below Forge's 32767-byte custom payload limit");
    }
}
