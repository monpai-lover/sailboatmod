package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapServerColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshotService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadMapSnapshotRequestPacket(UUID sessionId,
                                           String worldId,
                                           String dimensionId,
                                           long requestId,
                                           Purpose purpose,
                                           BlockPos regionCenter,
                                           int regionSize,
                                           MapLod lod) {
    public static final int MIN_REGION_SIZE = 128;
    public static final int MAX_REGION_SIZE = 512;

    public RoadMapSnapshotRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        purpose = purpose == null ? Purpose.VIEWPORT : purpose;
        regionCenter = regionCenter == null ? BlockPos.ZERO : regionCenter.immutable();
        lod = lod == null ? MapLod.LOD_4 : lod;
        regionSize = normalizeRegionSize(regionSize, lod);
    }

    public static void encode(RoadMapSnapshotRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        buffer.writeBlockPos(packet.regionCenter());
        buffer.writeVarInt(packet.regionSize());
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
    }

    public static RoadMapSnapshotRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadMapSnapshotRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readVarLong(),
                buffer.readEnum(Purpose.class),
                buffer.readBlockPos(),
                buffer.readVarInt(),
                RoadPlannerPacketCodec.readLod(buffer));
    }

    public static void handle(RoadMapSnapshotRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void handleOnServer(RoadMapSnapshotRequestPacket packet, ServerPlayer sender) {
        if (sender == null || !(sender.level() instanceof ServerLevel level)) {
            return;
        }
        RoadMapRegion region = RoadMapRegion.centeredOn(packet.regionCenter(), packet.regionSize(), packet.lod());
        RoadMapSnapshotService service = RoadMapSnapshotService.directExecutorForTest(new RoadMapColorizer());
        RoadMapSnapshot snapshot = service.buildSnapshotAsync(level.getGameTime(), region, new RoadMapServerColumnSampler(level)).join();
        RoadMapSnapshotSyncPacket response = new RoadMapSnapshotSyncPacket(
                packet.sessionId(),
                packet.worldId(),
                level.dimension().location().toString(),
                packet.requestId(),
                packet.purpose(),
                region.center().getX(),
                region.center().getZ(),
                region.regionSize(),
                region.lod(),
                region.pixelWidth(),
                region.pixelHeight(),
                snapshot.argbPixels());
        ModNetwork.CHANNEL.sendTo(response, sender.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    public static int normalizeRegionSize(int requestedSize, MapLod lod) {
        MapLod safeLod = lod == null ? MapLod.LOD_4 : lod;
        int clamped = Math.max(MIN_REGION_SIZE, Math.min(MAX_REGION_SIZE, requestedSize));
        int remainder = clamped % safeLod.blocksPerPixel();
        if (remainder != 0) {
            clamped += safeLod.blocksPerPixel() - remainder;
        }
        return Math.min(MAX_REGION_SIZE, clamped);
    }

    public enum Purpose {
        INITIAL_VIEWPORT,
        VIEWPORT,
        FORCE_RENDER
    }
}
