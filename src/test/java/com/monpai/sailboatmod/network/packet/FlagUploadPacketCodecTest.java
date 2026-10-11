package com.monpai.sailboatmod.network.packet;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagUploadPacketCodecTest {
    @Test
    void nationFlagChunkRoundTrips() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            UploadNationFlagChunkPacket.encode(new UploadNationFlagChunkPacket("upload", 3, 0, 1, new byte[]{1, 2, 3}), buffer);
            byte[] encoded = ByteBufUtil.getBytes(buffer);
            UploadNationFlagChunkPacket decoded = UploadNationFlagChunkPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            UploadNationFlagChunkPacket.encode(decoded, buffer);
            assertArrayEquals(encoded, ByteBufUtil.getBytes(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void townFlagChunkRoundTrips() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            UploadTownFlagChunkPacket.encode(new UploadTownFlagChunkPacket("town", "upload", 3, 0, 1, new byte[]{1, 2, 3}), buffer);
            byte[] encoded = ByteBufUtil.getBytes(buffer);
            UploadTownFlagChunkPacket decoded = UploadTownFlagChunkPacket.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            UploadTownFlagChunkPacket.encode(decoded, buffer);
            assertArrayEquals(encoded, ByteBufUtil.getBytes(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsNationFlagLengthMismatch() {
        FriendlyByteBuf buffer = flagHeader(false, 4);
        try {
            buffer.writeByteArray(new byte[]{1, 2, 3});
            assertThrows(IllegalArgumentException.class, () -> UploadNationFlagChunkPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsTownFlagLengthMismatch() {
        FriendlyByteBuf buffer = flagHeader(true, 4);
        try {
            buffer.writeByteArray(new byte[]{1, 2, 3});
            assertThrows(IllegalArgumentException.class, () -> UploadTownFlagChunkPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedFlagChunks() {
        FriendlyByteBuf buffer = flagHeader(false, 30 * 1024 + 1);
        try {
            buffer.writeByteArray(new byte[30 * 1024 + 1]);
            assertThrows(RuntimeException.class, () -> UploadNationFlagChunkPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static FriendlyByteBuf flagHeader(boolean town, int declaredLength) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        if (town) buffer.writeUtf("town", 40);
        buffer.writeUtf("upload", 128);
        buffer.writeVarInt(3);
        buffer.writeVarInt(0);
        buffer.writeVarInt(1);
        buffer.writeVarInt(declaredLength);
        return buffer;
    }
}
