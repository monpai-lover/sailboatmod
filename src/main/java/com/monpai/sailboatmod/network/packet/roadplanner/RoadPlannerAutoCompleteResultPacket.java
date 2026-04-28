package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoCompleteResultPacket(UUID sessionId,
                                                  boolean success,
                                                  List<BlockPos> nodes,
                                                  List<RoadPlannerSegmentType> segmentTypes,
                                                  String message) {
    public RoadPlannerAutoCompleteResultPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
        message = message == null ? "" : message;
    }

    public static void encode(RoadPlannerAutoCompleteResultPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeBoolean(packet.success());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.nodes());
        buffer.writeVarInt(packet.segmentTypes().size());
        for (RoadPlannerSegmentType type : packet.segmentTypes()) {
            buffer.writeEnum(type == null ? RoadPlannerSegmentType.ROAD : type);
        }
        RoadPlannerPacketCodec.writeString(buffer, packet.message(), 128);
    }

    public static RoadPlannerAutoCompleteResultPacket decode(FriendlyByteBuf buffer) {
        UUID sessionId = RoadPlannerPacketCodec.readUuid(buffer);
        boolean success = buffer.readBoolean();
        List<BlockPos> nodes = RoadPlannerPacketCodec.readBlockPosList(buffer);
        int segmentCount = buffer.readVarInt();
        List<RoadPlannerSegmentType> segmentTypes = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segmentTypes.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        return new RoadPlannerAutoCompleteResultPacket(sessionId, success, nodes, segmentTypes, buffer.readUtf(128));
    }

    public static void handle(RoadPlannerAutoCompleteResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.applyAutoCompleteResult(packet.sessionId(), packet.success(), packet.nodes(), packet.segmentTypes(), packet.message())));
        contextSupplier.get().setPacketHandled(true);
    }
}
