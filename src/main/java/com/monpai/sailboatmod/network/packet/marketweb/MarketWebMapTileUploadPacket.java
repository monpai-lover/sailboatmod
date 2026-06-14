package com.monpai.sailboatmod.network.packet.marketweb;

import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapTileUploadService;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Arrays;
import java.util.function.Supplier;

public record MarketWebMapTileUploadPacket(String dimensionId,
                                           MapLod lod,
                                           int tileX,
                                           int tileZ,
                                           int uploadId,
                                           int totalBytes,
                                           int chunkIndex,
                                           int chunkCount,
                                           byte[] chunkBytes) {
    public MarketWebMapTileUploadPacket {
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        lod = lod == null ? MapLod.LOD_1 : lod;
        totalBytes = Math.max(0, totalBytes);
        chunkIndex = Math.max(0, chunkIndex);
        chunkCount = Math.max(0, chunkCount);
        chunkBytes = chunkBytes == null ? new byte[0] : Arrays.copyOf(chunkBytes, chunkBytes.length);
    }

    public static void encode(MarketWebMapTileUploadPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.dimensionId(), 128);
        buffer.writeEnum(packet.lod());
        buffer.writeVarInt(packet.tileX());
        buffer.writeVarInt(packet.tileZ());
        buffer.writeVarInt(packet.uploadId());
        buffer.writeVarInt(packet.totalBytes());
        buffer.writeVarInt(packet.chunkIndex());
        buffer.writeVarInt(packet.chunkCount());
        byte[] chunk = packet.chunkBytes();
        if (chunk.length > MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES) {
            throw new IllegalArgumentException("Market web map tile chunk is too large: " + chunk.length);
        }
        buffer.writeByteArray(chunk);
    }

    public static MarketWebMapTileUploadPacket decode(FriendlyByteBuf buffer) {
        String dimensionId = buffer.readUtf(128);
        MapLod lod = buffer.readEnum(MapLod.class);
        int tileX = buffer.readVarInt();
        int tileZ = buffer.readVarInt();
        int uploadId = buffer.readVarInt();
        int totalBytes = buffer.readVarInt();
        int chunkIndex = buffer.readVarInt();
        int chunkCount = buffer.readVarInt();
        if (totalBytes <= 0 || totalBytes > MarketWebMapConstants.MAX_TILE_BYTES) {
            throw new IllegalArgumentException("Invalid market web map tile byte count: " + totalBytes);
        }
        if (chunkCount <= 0 || chunkCount > MarketWebMapConstants.MAX_UPLOAD_CHUNKS) {
            throw new IllegalArgumentException("Invalid market web map tile chunk count: " + chunkCount);
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Invalid market web map tile chunk index: " + chunkIndex);
        }
        byte[] chunk = buffer.readByteArray(MarketWebMapConstants.MAX_UPLOAD_CHUNK_BYTES);
        if (chunk.length == 0) {
            throw new IllegalArgumentException("Empty market web map tile chunk");
        }
        return new MarketWebMapTileUploadPacket(dimensionId, lod, tileX, tileZ, uploadId, totalBytes, chunkIndex, chunkCount, chunk);
    }

    @Override
    public byte[] chunkBytes() {
        return Arrays.copyOf(chunkBytes, chunkBytes.length);
    }

    public static void handle(MarketWebMapTileUploadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                MarketWebMapTileUploadService.acceptClientUpload(sender, packet);
            }
        });
        context.setPacketHandled(true);
    }
}
