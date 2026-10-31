package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterRouteCandidateService;
import com.monpai.sailboatmod.route.water.PortCandidateListing;
import com.monpai.sailboatmod.route.terrain.TellusWorlds;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import com.monpai.sailboatmod.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class RequestAutoRouteDocksPacket {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final BlockPos sourceDockPos;
    private final java.util.UUID requestId;

    public RequestAutoRouteDocksPacket(BlockPos sourceDockPos) {
        this(sourceDockPos,java.util.UUID.randomUUID());
    }
    public RequestAutoRouteDocksPacket(BlockPos sourceDockPos,java.util.UUID requestId) {
        this.requestId=requestId;
        this.sourceDockPos = sourceDockPos;
    }

    public static void encode(RequestAutoRouteDocksPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
        buf.writeUUID(msg.requestId);
    }

    public static RequestAutoRouteDocksPacket decode(FriendlyByteBuf buf) {
        return new RequestAutoRouteDocksPacket(buf.readBlockPos(),buf.readUUID());
    }

    public static void handle(RequestAutoRouteDocksPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

            var sourceDock=com.monpai.sailboatmod.route.water.PortDockSnapshot.loaded(serverLevel,msg.sourceDockPos);
            if(sourceDock==null || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(msg.sourceDockPos))>128.0*128.0){
                ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(msg.sourceDockPos,msg.requestId,false,0,0,0,RouteWorkProgressPacket.FAILED,"出发码头不可用，请重新打开"));return;}
            if(!(sourceDock instanceof PostStationBlockEntity)){
                if(!PortCandidateListing.acceptRequest(serverLevel.getServer(),player.getUUID())){
                    ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(msg.sourceDockPos,msg.requestId,false,0,0,0,
                            RouteWorkProgressPacket.BUSY,"请求过于频繁，请稍后重新验证"));return;
                }
                PortCandidateListing.request(serverLevel,sourceDock,player,msg.requestId);return;
            }

            PortCandidateListing.cancelPrevious(serverLevel.getServer(), player.getUUID());
            List<AvailableDockEntry> available = new ArrayList<>();
            List<DockBlockEntity> permittedDocks = new ArrayList<>();
            int scanned = 0;
            boolean postStationMode = sourceDock instanceof PostStationBlockEntity;
            TransportTerminalKind terminalKind = postStationMode ? TransportTerminalKind.POST_STATION : TransportTerminalKind.PORT;
            // 码头(water)候选来自持久坐标清单(不依赖区块加载,远处码头也能列出);驿站仍用内存注册表。
            Iterable<BlockPos> candidates = postStationMode
                    ? PostStationRegistry.get(serverLevel)
                    : com.monpai.sailboatmod.dock.DockLocationSavedData.get(serverLevel)
                            .positionsIn(serverLevel.dimension().location().toString());

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
                    // First filter permissions; Tellus berth/exit checks refine the list asynchronously below.
                    // Never ask the vanilla noise generator whether an edited port is water.
                    WaterRouteResult<?> result = WaterRouteCandidateService.canListCandidate(serverLevel, sourceDock, targetDock);
                    canCreate = result.successful();
                    if (!canCreate) {
                        LOGGER.info("[AutoRoute] 码头候选被刷掉 {} @{}: {}", targetDock.getDockName(), dockPos, result.reason());
                    }
                }
                if (!canCreate) continue;

                permittedDocks.add(targetDock);
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

            ModNetwork.CHANNEL.send(player,new SyncAutoRouteDocksPacket(msg.sourceDockPos,terminalKind,available,msg.requestId));
            ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(msg.sourceDockPos,msg.requestId,false,100,scanned,scanned,
                    RouteWorkProgressPacket.DONE,"驿站检查结束"));
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
