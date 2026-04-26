package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadMapSnapshotRequestPacket(UUID sessionId,
                                           String worldId,
                                           String dimensionId,
                                           BlockPos startPos,
                                           BlockPos destinationPos,
                                           List<BlockPos> manualNodes,
                                           int regionSize,
                                           MapLod lod) {
    public RoadMapSnapshotRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        startPos = startPos == null ? BlockPos.ZERO : startPos.immutable();
        destinationPos = destinationPos == null ? BlockPos.ZERO : destinationPos.immutable();
        manualNodes = manualNodes == null ? List.of() : manualNodes.stream().map(BlockPos::immutable).toList();
        regionSize = Math.max(16, regionSize);
        lod = lod == null ? MapLod.LOD_4 : lod;
    }

    public static void encode(RoadMapSnapshotRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeBlockPos(packet.startPos());
        buffer.writeBlockPos(packet.destinationPos());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.manualNodes());
        buffer.writeVarInt(packet.regionSize());
        RoadPlannerPacketCodec.writeLod(buffer, packet.lod());
    }

    public static RoadMapSnapshotRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadMapSnapshotRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                RoadPlannerPacketCodec.readBlockPosList(buffer),
                buffer.readVarInt(),
                RoadPlannerPacketCodec.readLod(buffer));
    }

    public static void handle(RoadMapSnapshotRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }
}
