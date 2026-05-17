package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteResult;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteService;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactory;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoCompleteRequestPacket(UUID sessionId,
                                                   BlockPos start,
                                                   BlockPos destination,
                                                   List<BlockPos> manualNodes,
                                                   int spacingBlocks) {
    public RoadPlannerAutoCompleteRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        start = start == null ? BlockPos.ZERO : start.immutable();
        destination = destination == null ? BlockPos.ZERO : destination.immutable();
        manualNodes = manualNodes == null ? List.of() : manualNodes.stream().map(BlockPos::immutable).toList();
        spacingBlocks = Math.max(4, spacingBlocks);
    }

    public static void encode(RoadPlannerAutoCompleteRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeBlockPos(packet.start());
        buffer.writeBlockPos(packet.destination());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.manualNodes());
        buffer.writeVarInt(packet.spacingBlocks());
    }

    public static RoadPlannerAutoCompleteRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerAutoCompleteRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                RoadPlannerPacketCodec.readBlockPosList(buffer),
                buffer.readVarInt()
        );
    }

    public static void handle(RoadPlannerAutoCompleteRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RoadPlannerAutoCompleteService service = RoadPlannerPathfinderRunnerFactory.serverService(player.serverLevel());
            if (service == null) {
                service = new RoadPlannerAutoCompleteService();
            }
            RoadPlannerAutoCompleteResult result = service.complete(packet.start(), packet.destination(), packet.manualNodes(), packet.spacingBlocks());
            ModNetwork.CHANNEL.sendTo(
                    new RoadPlannerAutoCompleteResultPacket(packet.sessionId(), result.success(), result.nodes(), result.segmentTypes(), result.message()),
                    player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        });
        context.setPacketHandled(true);
    }
}
