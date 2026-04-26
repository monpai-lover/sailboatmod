package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadMapSnapshotSyncPacket(UUID sessionId,
                                        String worldId,
                                        String dimensionId,
                                        int regionCenterX,
                                        int regionCenterZ,
                                        int regionSize,
                                        MapLod lod,
                                        int pixelWidth,
                                        int pixelHeight,
                                        int[] argbPixels) {
    public RoadMapSnapshotSyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        lod = lod == null ? MapLod.LOD_4 : lod;
        argbPixels = argbPixels == null ? new int[0] : Arrays.copyOf(argbPixels, argbPixels.length);
    }

    public static void encode(RoadMapSnapshotSyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeInt(packet.regionCenterX());
        buffer.writeInt(packet.regionCenterZ());
        buffer.writeVarInt(packet.regionSize());
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
        buffer.writeVarInt(packet.pixelWidth());
        buffer.writeVarInt(packet.pixelHeight());
        buffer.writeVarInt(packet.argbPixels().length);
        for (int pixel : packet.argbPixels()) {
            buffer.writeInt(pixel);
        }
    }

    public static RoadMapSnapshotSyncPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        String worldId = buffer.readUtf(128);
        String dimensionId = buffer.readUtf(128);
        int regionCenterX = buffer.readInt();
        int regionCenterZ = buffer.readInt();
        int regionSize = buffer.readVarInt();
        MapLod lod = RoadPlannerPacketCodec.readLod(buffer);
        int pixelWidth = buffer.readVarInt();
        int pixelHeight = buffer.readVarInt();
        int count = buffer.readVarInt();
        int[] pixels = new int[count];
        for (int index = 0; index < count; index++) {
            pixels[index] = buffer.readInt();
        }
        return new RoadMapSnapshotSyncPacket(sessionId, worldId, dimensionId, regionCenterX, regionCenterZ, regionSize, lod, pixelWidth, pixelHeight, pixels);
    }

    @Override
    public int[] argbPixels() {
        return Arrays.copyOf(argbPixels, argbPixels.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoadMapSnapshotSyncPacket packet)) {
            return false;
        }
        return regionCenterX == packet.regionCenterX
                && regionCenterZ == packet.regionCenterZ
                && regionSize == packet.regionSize
                && pixelWidth == packet.pixelWidth
                && pixelHeight == packet.pixelHeight
                && Objects.equals(sessionId, packet.sessionId)
                && Objects.equals(worldId, packet.worldId)
                && Objects.equals(dimensionId, packet.dimensionId)
                && lod == packet.lod
                && Arrays.equals(argbPixels, packet.argbPixels);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sessionId, worldId, dimensionId, regionCenterX, regionCenterZ, regionSize, lod, pixelWidth, pixelHeight);
        result = 31 * result + Arrays.hashCode(argbPixels);
        return result;
    }

    public static void handle(RoadMapSnapshotSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
