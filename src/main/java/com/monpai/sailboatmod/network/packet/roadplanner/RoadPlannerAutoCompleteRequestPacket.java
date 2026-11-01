package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteExecutor;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteResult;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerAutoCompleteService;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerPathfinderRunnerFactory;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.monpai.sailboatmod.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerAutoCompleteRequestPacket(UUID sessionId,
                                                   BlockPos start,
                                                   BlockPos destination,
                                                   List<BlockPos> manualNodes,
                                                   int spacingBlocks,
                                                   boolean useRealChunk) {
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
        buffer.writeBoolean(packet.useRealChunk()); // 9→10:寻路精度(true=真实区块HIGH / false=噪声NORMAL)
    }

    public static RoadPlannerAutoCompleteRequestPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerAutoCompleteRequestPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readBlockPos(),
                buffer.readBlockPos(),
                RoadPlannerPacketCodec.readBlockPosList(buffer),
                buffer.readVarInt(),
                buffer.readBoolean()
        );
    }

    public static void handle(RoadPlannerAutoCompleteRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (packet.manualNodes().size() > 1024) {
                ModNetwork.CHANNEL.send(player, new RoadPlannerAutoCompleteResultPacket(packet.sessionId(),
                        false, List.of(), List.of(), "手工节点超过1024，请分段规划", List.of()));
                return;
            }
            var requestedDimension = player.level().dimension();
            RoadPlannerAutoCompleteService service = RoadPlannerPathfinderRunnerFactory.serverService(player.serverLevel(), packet.useRealChunk());
            RoadPlannerAutoCompleteService chosen = service == null ? new RoadPlannerAutoCompleteService() : service;
            var server = player.getServer();
            UUID playerId = player.getUUID();
            // 寻路在后台线程跑(超远两城曾在主线程卡服);同一玩家的新请求会中断旧请求;结果回主线程发送。
            RoadPlannerAutoCompleteExecutor.submit(playerId,
                    () -> {
                        try {
                            return RoadSearchBudget.run(RoadPlannerPathfinderRunnerFactory.AUTO_COMPLETE_BUDGET_MILLIS,
                                    () -> chosen.complete(packet.start(), packet.destination(), packet.manualNodes(), packet.spacingBlocks()));
                        } catch (RoadSearchBudget.DeadlineExceeded expired) {
                            return RoadPlannerAutoCompleteResult.failure("道路规划超时，请缩短范围或增加途经节点；未生成直线兜底");
                        }
                    },
                    server::execute,
                    result -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                        if (target != player || !target.level().dimension().equals(requestedDimension)) return;
                        RoadPlannerAutoCompleteResult sent = result == null
                                ? RoadPlannerAutoCompleteResult.failure("自动寻路失败") : result;
                        ModNetwork.CHANNEL.send(target, new RoadPlannerAutoCompleteResultPacket(
                                packet.sessionId(), sent.success(), sent.nodes(), sent.segmentTypes(),
                                sent.message(), sent.terrainSamples()));
                    },
                    () -> ModNetwork.CHANNEL.send(player, new RoadPlannerAutoCompleteResultPacket(
                            packet.sessionId(), false, List.of(), List.of(), "服务器自动补全队列已满，请稍后再试", List.of())));
        });
        context.setPacketHandled(true);
    }
}
