package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class RequestAutoRouteDocksPacket {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final BlockPos sourceDockPos;

    public RequestAutoRouteDocksPacket(BlockPos sourceDockPos) {
        this.sourceDockPos = sourceDockPos;
    }

    public static void encode(RequestAutoRouteDocksPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
    }

    public static RequestAutoRouteDocksPacket decode(FriendlyByteBuf buf) {
        return new RequestAutoRouteDocksPacket(buf.readBlockPos());
    }

    public static void handle(RequestAutoRouteDocksPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

            if (!(serverLevel.getBlockEntity(msg.sourceDockPos) instanceof DockBlockEntity sourceDock)) return;

            List<AvailableDockEntry> available = new ArrayList<>();
            int scanned = 0;
            boolean postStationMode = sourceDock instanceof PostStationBlockEntity;
            TransportTerminalKind terminalKind = postStationMode ? TransportTerminalKind.POST_STATION : TransportTerminalKind.PORT;
            Iterable<BlockPos> candidates = postStationMode ? PostStationRegistry.get(serverLevel) : DockRegistry.get(serverLevel);

            for (BlockPos dockPos : candidates) {
                if (dockPos.equals(msg.sourceDockPos)) continue;

                // 不再用 hasChunkAt 闸门过滤未加载区块的候选驿站：那会导致玩家没走近、目标驿站
                // 区块未加载时被直接跳过 → 派遣 UI「无可达路」，走近一段才出现（间歇性消失 bug）。
                // 可达判定本身只查持久化路图+claim（零区块依赖），这里 getBlockEntity 主动同步加载目标
                // 区块以读取其名字/owner/nationId（驿站数量有限、开 UI 为低频手动操作，开销可接受）。
                if (!(serverLevel.getBlockEntity(dockPos) instanceof DockBlockEntity targetDock)) continue;

                scanned++;
                boolean canCreate;
                if (postStationMode) {
                    canCreate = RoadAutoRouteService.canResolveAutoRoute(serverLevel, sourceDock, targetDock);
                    if (!canCreate) {
                        LOGGER.info("[AutoRoute] 驿站候选被刷掉 {} @{}: 路网不可达", targetDock.getDockName(), dockPos);
                    }
                } else {
                    WaterRouteResult<?> result = WaterAutoRouteService.canListCandidate(serverLevel, sourceDock, targetDock);
                    canCreate = result.successful();
                    if (!canCreate) {
                        LOGGER.info("[AutoRoute] 码头候选被刷掉 {} @{}: {}", targetDock.getDockName(), dockPos, result.reason());
                    }
                }
                if (!canCreate) continue;

                int distance = (int) Math.sqrt(msg.sourceDockPos.distSqr(dockPos));
                available.add(new AvailableDockEntry(
                    dockPos,
                    targetDock.getDockName(),
                    targetDock.getOwnerName(),
                    getNationName(serverLevel, targetDock),
                    distance
                ));
            }

            LOGGER.info("[AutoRoute] 源 @{} 模式={} 共扫描 {} 个其它码头,列出 {} 个可用",
                    msg.sourceDockPos, postStationMode ? "驿站" : "码头", scanned, available.size());

            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncAutoRouteDocksPacket(msg.sourceDockPos, terminalKind, available));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String getNationName(net.minecraft.world.level.Level level, DockBlockEntity dock) {
        if (dock.getNationId().isBlank()) return "-";
        NationSavedData data = NationSavedData.get(level);
        var nation = data.getNation(dock.getNationId());
        return nation == null ? "-" : nation.name();
    }
}
