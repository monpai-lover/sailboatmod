package com.example.examplemod.network.packet;

import com.example.examplemod.nation.service.NationFlagUploadService;
import com.example.examplemod.nation.service.NationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class UploadNationFlagChunkPacket {
    private final String uploadId;
    private final int totalBytes;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] bytes;

    public UploadNationFlagChunkPacket(String uploadId, int totalBytes, int chunkIndex, int chunkCount, byte[] bytes) {
        this.uploadId = uploadId == null ? "" : uploadId;
        this.totalBytes = Math.max(0, totalBytes);
        this.chunkIndex = Math.max(0, chunkIndex);
        this.chunkCount = Math.max(0, chunkCount);
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    public static void encode(UploadNationFlagChunkPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.uploadId, 128);
        buffer.writeVarInt(packet.totalBytes);
        buffer.writeVarInt(packet.chunkIndex);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeVarInt(packet.bytes.length);
        buffer.writeByteArray(packet.bytes);
    }

    public static UploadNationFlagChunkPacket decode(FriendlyByteBuf buffer) {
        String uploadId = buffer.readUtf(128);
        int totalBytes = buffer.readVarInt();
        int chunkIndex = buffer.readVarInt();
        int chunkCount = buffer.readVarInt();
        int ignoredLength = buffer.readVarInt();
        byte[] bytes = buffer.readByteArray();
        return new UploadNationFlagChunkPacket(uploadId, totalBytes, chunkIndex, chunkCount, bytes);
    }

    public static void handle(UploadNationFlagChunkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            NationResult result = NationFlagUploadService.acceptChunk(player, packet.uploadId, packet.totalBytes, packet.chunkIndex, packet.chunkCount, packet.bytes);
            if (!result.message().getString().isBlank()) {
                player.sendSystemMessage(result.message());
            }
        });
        context.setPacketHandled(true);
    }
}
