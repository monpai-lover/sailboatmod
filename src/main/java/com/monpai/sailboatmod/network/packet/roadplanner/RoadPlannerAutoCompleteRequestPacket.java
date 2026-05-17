package com.monpai.sailboatmod.network.packet.roadplanner;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteResult;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteService;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactory;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoCompleteRequestPacket(UUID sessionId,
                                                   BlockPos start,
                                                   BlockPos destination,
                                                   List<BlockPos> manualNodes,
                                                   int spacingBlocks,
                                                   int algorithmIndex) {
    private static final Logger LOGGER = LogUtils.getLogger();

    public RoadPlannerAutoCompleteRequestPacket(UUID sessionId, BlockPos start, BlockPos destination, List<BlockPos> manualNodes, int spacingBlocks) {
        this(sessionId, start, destination, manualNodes, spacingBlocks, 1);
    }

    public RoadPlannerAutoCompleteRequestPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        start = start == null ? BlockPos.ZERO : start.immutable();
        destination = destination == null ? BlockPos.ZERO : destination.immutable();
        manualNodes = manualNodes == null ? List.of() : manualNodes.stream().map(BlockPos::immutable).toList();
        spacingBlocks = Math.max(4, spacingBlocks);
        algorithmIndex = Math.max(0, Math.min(3, algorithmIndex));
    }

    private static PathfindingConfig.Algorithm algorithmFromIndex(int index) {
        return switch (index) {
            case 0 -> PathfindingConfig.Algorithm.BASIC_ASTAR;
            case 2 -> PathfindingConfig.Algorithm.GRADIENT_DESCENT;
            case 3 -> PathfindingConfig.Algorithm.POTENTIAL_FIELD;
            default -> PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR;
        };
    }

    public static void encode(RoadPlannerAutoCompleteRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeBlockPos(packet.start());
        buffer.writeBlockPos(packet.destination());
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.manualNodes());
        buffer.writeVarInt(packet.spacingBlocks());
        buffer.writeVarInt(packet.algorithmIndex());
    }

    public static RoadPlannerAutoCompleteRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerAutoCompleteRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                RoadPlannerPacketCodec.readBlockPosList(buffer),
                buffer.readVarInt(),
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
            RoadPlannerAutoCompleteResult result;
            try {
                PathfindingConfig.Algorithm algo = algorithmFromIndex(packet.algorithmIndex());
                RoadPlannerAutoCompleteService service = RoadPlannerPathfinderRunnerFactory.serverService(player.serverLevel(), algo);
                if (service == null) {
                    service = new RoadPlannerAutoCompleteService();
                }
                result = service.complete(packet.start(), packet.destination(), packet.manualNodes(), packet.spacingBlocks());
            } catch (Exception e) {
                LOGGER.error("Auto-complete pathfinding failed", e);
                result = RoadPlannerAutoCompleteResult.failure("寻路异常: " + e.getMessage());
            }
            ModNetwork.CHANNEL.sendTo(
                    new RoadPlannerAutoCompleteResultPacket(packet.sessionId(), result.success(), result.nodes(), result.segmentTypes(), result.message()),
                    player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        });
        context.setPacketHandled(true);
    }
}
