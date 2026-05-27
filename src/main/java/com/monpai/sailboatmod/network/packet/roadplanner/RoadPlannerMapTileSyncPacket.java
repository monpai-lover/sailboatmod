package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClientMapTileCache;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileSyncReceiver;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerMapTileSyncPacket(UUID sessionId,
                                           long requestId,
                                           RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                           String worldId,
                                           String dimensionId,
                                           MapLod lod,
                                           int tileX,
                                           int tileZ,
                                           int pixelWidth,
                                           int pixelHeight,
                                           int[] argbPixels,
                                           boolean[] coverageMask) {
    public RoadPlannerMapTileSyncPacket(UUID sessionId,
                                        long requestId,
                                        RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                        String worldId,
                                        String dimensionId,
                                        MapLod lod,
                                        int tileX,
                                        int tileZ,
                                        int pixelWidth,
                                        int pixelHeight,
                                        int[] argbPixels) {
        this(sessionId, requestId, purpose, worldId, dimensionId, lod, tileX, tileZ, pixelWidth, pixelHeight, argbPixels, null);
    }

    public RoadPlannerMapTileSyncPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        purpose = purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        lod = lod == null ? MapLod.LOD_1 : lod;
        pixelWidth = Math.max(0, pixelWidth);
        pixelHeight = Math.max(0, pixelHeight);
        argbPixels = argbPixels == null ? new int[0] : Arrays.copyOf(argbPixels, argbPixels.length);
        coverageMask = normalizeCoverageMask(coverageMask, argbPixels.length);
    }

    public static void encode(RoadPlannerMapTileSyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
        buffer.writeVarInt(packet.tileX());
        buffer.writeVarInt(packet.tileZ());
        buffer.writeVarInt(packet.pixelWidth());
        buffer.writeVarInt(packet.pixelHeight());
        buffer.writeVarInt(packet.argbPixels().length);
        for (int pixel : packet.argbPixels()) {
            buffer.writeInt(pixel);
        }
        buffer.writeVarInt(packet.coverageMask().length);
        for (boolean covered : packet.coverageMask()) {
            buffer.writeBoolean(covered);
        }
    }

    public static RoadPlannerMapTileSyncPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        long requestId = buffer.readVarLong();
        RoadPlannerMapPreloadRequestPacket.Purpose purpose = buffer.readEnum(RoadPlannerMapPreloadRequestPacket.Purpose.class);
        String worldId = buffer.readUtf(128);
        String dimensionId = buffer.readUtf(128);
        MapLod lod = RoadPlannerPacketCodec.readLod(buffer);
        int tileX = buffer.readVarInt();
        int tileZ = buffer.readVarInt();
        int pixelWidth = buffer.readVarInt();
        int pixelHeight = buffer.readVarInt();
        int count = buffer.readVarInt();
        int[] pixels = new int[count];
        for (int index = 0; index < count; index++) {
            pixels[index] = buffer.readInt();
        }
        boolean[] coverageMask = new boolean[0];
        if (buffer.readableBytes() > 0) {
            int maskCount = buffer.readVarInt();
            coverageMask = new boolean[maskCount];
            for (int index = 0; index < maskCount; index++) {
                coverageMask[index] = buffer.readBoolean();
            }
        }
        return new RoadPlannerMapTileSyncPacket(sessionId, requestId, purpose, worldId, dimensionId, lod, tileX, tileZ, pixelWidth, pixelHeight, pixels, coverageMask);
    }

    @Override
    public int[] argbPixels() {
        return Arrays.copyOf(argbPixels, argbPixels.length);
    }

    @Override
    public boolean[] coverageMask() {
        return Arrays.copyOf(coverageMask, coverageMask.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoadPlannerMapTileSyncPacket packet)) {
            return false;
        }
        return requestId == packet.requestId
                && tileX == packet.tileX
                && tileZ == packet.tileZ
                && pixelWidth == packet.pixelWidth
                && pixelHeight == packet.pixelHeight
                && Objects.equals(sessionId, packet.sessionId)
                && purpose == packet.purpose
                && Objects.equals(worldId, packet.worldId)
                && Objects.equals(dimensionId, packet.dimensionId)
                && lod == packet.lod
                && Arrays.equals(argbPixels, packet.argbPixels)
                && Arrays.equals(coverageMask, packet.coverageMask);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(sessionId, requestId, purpose, worldId, dimensionId, lod, tileX, tileZ, pixelWidth, pixelHeight);
        result = 31 * result + Arrays.hashCode(argbPixels);
        result = 31 * result + Arrays.hashCode(coverageMask);
        return result;
    }

    private static boolean[] normalizeCoverageMask(boolean[] mask, int pixelCount) {
        int safePixelCount = Math.max(0, pixelCount);
        if (mask != null && mask.length == safePixelCount) {
            return Arrays.copyOf(mask, mask.length);
        }
        boolean[] fullCoverage = new boolean[safePixelCount];
        Arrays.fill(fullCoverage, true);
        return fullCoverage;
    }

    public static void handle(RoadPlannerMapTileSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleOnClient(packet)));
        contextSupplier.get().setPacketHandled(true);
    }

    private static void handleOnClient(RoadPlannerMapTileSyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        RoadPlannerClientMapTileCache.applyToDefaultCache(packet);
        RoadPlannerTileSyncReceiver.dispatch(minecraft.screen, packet);
    }
}
