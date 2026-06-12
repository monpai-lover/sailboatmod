package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public record OpenRoadEditSelectionPacket(List<Entry> roads) {
    private static final int MAX_ROADS = 256;

    public OpenRoadEditSelectionPacket {
        roads = roads == null ? List.of() : roads.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ROADS)
                .toList();
    }

    public static void encode(OpenRoadEditSelectionPacket packet, FriendlyByteBuf buffer) {
        List<Entry> roads = packet.roads();
        buffer.writeVarInt(Math.min(MAX_ROADS, roads.size()));
        for (Entry entry : roads) {
            RoadPlannerPacketCodec.writeString(buffer, entry.roadId(), 128);
            RoadPlannerPacketCodec.writeString(buffer, entry.sourceName(), 96);
            RoadPlannerPacketCodec.writeString(buffer, entry.targetName(), 96);
            RoadPlannerPacketCodec.writeString(buffer, entry.sourceType(), 32);
            buffer.writeVarInt(entry.nodeCount());
            buffer.writeVarInt(entry.lengthBlocks());
            buffer.writeVarInt(entry.width());
            buffer.writeBoolean(entry.legacyMigrated());
            RoadPlannerPacketCodec.writeString(buffer, entry.status(), 32);
        }
    }

    public static OpenRoadEditSelectionPacket decode(FriendlyByteBuf buffer) {
        int count = Math.min(MAX_ROADS, Math.max(0, buffer.readVarInt()));
        java.util.ArrayList<Entry> roads = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            roads.add(new Entry(
                    buffer.readUtf(128),
                    buffer.readUtf(96),
                    buffer.readUtf(96),
                    buffer.readUtf(32),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readUtf(32)));
        }
        return new OpenRoadEditSelectionPacket(roads);
    }

    public static void handle(OpenRoadEditSelectionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.openEditSelection(packet.roads())));
        contextSupplier.get().setPacketHandled(true);
    }

    public record Entry(String roadId,
                        String sourceName,
                        String targetName,
                        String sourceType,
                        int nodeCount,
                        int lengthBlocks,
                        int width,
                        boolean legacyMigrated,
                        String status) {
        public Entry {
            roadId = roadId == null ? "" : roadId.trim();
            sourceName = sourceName == null || sourceName.isBlank() ? "-" : sourceName.trim();
            targetName = targetName == null || targetName.isBlank() ? "-" : targetName.trim();
            sourceType = sourceType == null || sourceType.isBlank() ? "UNKNOWN" : sourceType.trim();
            nodeCount = Math.max(0, nodeCount);
            lengthBlocks = Math.max(0, lengthBlocks);
            width = Math.max(1, width);
            status = status == null || status.isBlank() ? "BUILT" : status.trim();
        }
    }
}
