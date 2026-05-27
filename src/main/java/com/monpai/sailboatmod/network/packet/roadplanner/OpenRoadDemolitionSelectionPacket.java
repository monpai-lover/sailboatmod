package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public record OpenRoadDemolitionSelectionPacket(List<Entry> roads) {
    private static final int MAX_ROADS = 256;

    public OpenRoadDemolitionSelectionPacket {
        roads = roads == null ? List.of() : roads.stream().filter(java.util.Objects::nonNull).limit(MAX_ROADS).toList();
    }

    public static void encode(OpenRoadDemolitionSelectionPacket packet, FriendlyByteBuf buffer) {
        List<Entry> roads = packet.roads();
        buffer.writeVarInt(Math.min(MAX_ROADS, roads.size()));
        for (Entry entry : roads) {
            RoadPlannerPacketCodec.writeString(buffer, entry.roadId(), 128);
            RoadPlannerPacketCodec.writeString(buffer, entry.sourceName(), 96);
            RoadPlannerPacketCodec.writeString(buffer, entry.targetName(), 96);
            RoadPlannerPacketCodec.writeString(buffer, entry.sourceType(), 32);
            buffer.writeVarInt(entry.nodeCount());
            buffer.writeVarInt(entry.lengthBlocks());
        }
    }

    public static OpenRoadDemolitionSelectionPacket decode(FriendlyByteBuf buffer) {
        int count = Math.min(MAX_ROADS, Math.max(0, buffer.readVarInt()));
        java.util.ArrayList<Entry> roads = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            roads.add(new Entry(
                    buffer.readUtf(128),
                    buffer.readUtf(96),
                    buffer.readUtf(96),
                    buffer.readUtf(32),
                    buffer.readVarInt(),
                    buffer.readVarInt()));
        }
        return new OpenRoadDemolitionSelectionPacket(roads);
    }

    public static void handle(OpenRoadDemolitionSelectionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.openDemolitionSelection(packet.roads())));
        contextSupplier.get().setPacketHandled(true);
    }

    public record Entry(String roadId,
                        String sourceName,
                        String targetName,
                        String sourceType,
                        int nodeCount,
                        int lengthBlocks) {
        public Entry {
            roadId = roadId == null ? "" : roadId;
            sourceName = sourceName == null || sourceName.isBlank() ? "-" : sourceName;
            targetName = targetName == null || targetName.isBlank() ? "-" : targetName;
            sourceType = sourceType == null || sourceType.isBlank() ? "UNKNOWN" : sourceType;
            nodeCount = Math.max(0, nodeCount);
            lengthBlocks = Math.max(0, lengthBlocks);
        }
    }
}
