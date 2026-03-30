package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.nation.service.TownFlagUploadService;
import com.monpai.sailboatmod.nation.service.TownOverviewService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class UploadTownFlagChunkPacket {
    private final String townId;
    private final String uploadId;
    private final int totalBytes;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] bytes;

    public UploadTownFlagChunkPacket(String townId, String uploadId, int totalBytes, int chunkIndex, int chunkCount, byte[] bytes) {
        this.townId = townId == null ? "" : townId;
        this.uploadId = uploadId == null ? "" : uploadId;
        this.totalBytes = Math.max(0, totalBytes);
        this.chunkIndex = Math.max(0, chunkIndex);
        this.chunkCount = Math.max(0, chunkCount);
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    public static void encode(UploadTownFlagChunkPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.townId, 40);
        PacketStringCodec.writeUtfSafe(buffer, packet.uploadId, 128);
        buffer.writeVarInt(packet.totalBytes);
        buffer.writeVarInt(packet.chunkIndex);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeVarInt(packet.bytes.length);
        buffer.writeByteArray(packet.bytes);
    }

    public static UploadTownFlagChunkPacket decode(FriendlyByteBuf buffer) {
        String townId = buffer.readUtf(40);
        String uploadId = buffer.readUtf(128);
        int totalBytes = buffer.readVarInt();
        int chunkIndex = buffer.readVarInt();
        int chunkCount = buffer.readVarInt();
        buffer.readVarInt();
        byte[] bytes = buffer.readByteArray();
        return new UploadTownFlagChunkPacket(townId, uploadId, totalBytes, chunkIndex, chunkCount, bytes);
    }

    public static void handle(UploadTownFlagChunkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            NationResult result = TownFlagUploadService.acceptChunk(player, packet.townId, packet.uploadId, packet.totalBytes, packet.chunkIndex, packet.chunkCount, packet.bytes);
            if (!result.message().getString().isBlank()) {
                player.sendSystemMessage(result.message());
            }
            if (result.success() && packet.chunkIndex == packet.chunkCount - 1) {
                TownOverviewData data = TownOverviewService.buildFor(player, packet.townId);
                ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTownScreenPacket(data));
            }
        });
        context.setPacketHandled(true);
    }
}