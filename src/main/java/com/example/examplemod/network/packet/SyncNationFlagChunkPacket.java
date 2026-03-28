package com.example.examplemod.network.packet;

import com.example.examplemod.client.texture.NationFlagTextureCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncNationFlagChunkPacket {
    private final String flagId;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] bytes;

    public SyncNationFlagChunkPacket(String flagId, int chunkIndex, int chunkCount, byte[] bytes) {
        this.flagId = flagId == null ? "" : flagId;
        this.chunkIndex = Math.max(0, chunkIndex);
        this.chunkCount = Math.max(0, chunkCount);
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    public static void encode(SyncNationFlagChunkPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.flagId, 128);
        buffer.writeVarInt(packet.chunkIndex);
        buffer.writeVarInt(packet.chunkCount);
        buffer.writeVarInt(packet.bytes.length);
        buffer.writeByteArray(packet.bytes);
    }

    public static SyncNationFlagChunkPacket decode(FriendlyByteBuf buffer) {
        String flagId = buffer.readUtf(128);
        int chunkIndex = buffer.readVarInt();
        int chunkCount = buffer.readVarInt();
        int ignoredLen = buffer.readVarInt();
        byte[] bytes = buffer.readByteArray();
        return new SyncNationFlagChunkPacket(flagId, chunkIndex, chunkCount, bytes);
    }

    public static void handle(SyncNationFlagChunkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                NationFlagTextureCache.acceptChunk(packet.flagId, packet.chunkIndex, packet.chunkCount, packet.bytes)));
        context.setPacketHandled(true);
    }
}
