package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerGraphSyncPacket(UUID routeId, List<EdgeDto> edges) {
    public RoadPlannerGraphSyncPacket {
        routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static void encode(RoadPlannerGraphSyncPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.routeId());
        buffer.writeVarInt(packet.edges().size());
        for (EdgeDto edge : packet.edges()) {
            RoadPlannerPacketCodec.writeUuid(buffer, edge.edgeId());
            RoadPlannerPacketCodec.writeUuid(buffer, edge.fromNodeId());
            RoadPlannerPacketCodec.writeUuid(buffer, edge.toNodeId());
            RoadPlannerPacketCodec.writeString(buffer, edge.roadName(), 64);
            RoadPlannerPacketCodec.writeString(buffer, edge.fromTownName(), 64);
            RoadPlannerPacketCodec.writeString(buffer, edge.toTownName(), 64);
            buffer.writeVarInt(edge.lengthBlocks());
            buffer.writeVarInt(edge.width());
            buffer.writeEnum(edge.type());
        }
    }

    public static RoadPlannerGraphSyncPacket decode(FriendlyByteBuf buffer) {
        UUID routeId = RoadPlannerPacketCodec.readUuid(buffer);
        int size = buffer.readVarInt();
        List<EdgeDto> edges = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            edges.add(new EdgeDto(
                    RoadPlannerPacketCodec.readUuid(buffer),
                    RoadPlannerPacketCodec.readUuid(buffer),
                    RoadPlannerPacketCodec.readUuid(buffer),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readEnum(CompiledRoadSectionType.class)
            ));
        }
        return new RoadPlannerGraphSyncPacket(routeId, edges);
    }

    public static void handle(RoadPlannerGraphSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().setPacketHandled(true);
    }

    public record EdgeDto(UUID edgeId,
                          UUID fromNodeId,
                          UUID toNodeId,
                          String roadName,
                          String fromTownName,
                          String toTownName,
                          int lengthBlocks,
                          int width,
                          CompiledRoadSectionType type) {
        public EdgeDto {
            edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
            fromNodeId = fromNodeId == null ? new UUID(0L, 0L) : fromNodeId;
            toNodeId = toNodeId == null ? new UUID(0L, 0L) : toNodeId;
            roadName = roadName == null ? "" : roadName.trim();
            fromTownName = fromTownName == null ? "" : fromTownName.trim();
            toTownName = toTownName == null ? "" : toTownName.trim();
            type = type == null ? CompiledRoadSectionType.ROAD : type;
        }
    }
}
