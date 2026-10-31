package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterAutoRouteService;
import com.monpai.sailboatmod.route.water.WaterMidMode;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.monpai.sailboatmod.network.NetworkEvent;

import java.util.function.Supplier;

public class CreateAutoRoutePacket {
    private final BlockPos sourceDockPos;
    private final java.util.UUID requestId;
    private final BlockPos targetDockPos;
    private final WaterMidMode midMode; // 中段寻路模式(noise/nbt/hybrid),仅水路用

    public CreateAutoRoutePacket(BlockPos sourceDockPos, BlockPos targetDockPos) {
        this(sourceDockPos, targetDockPos, WaterMidMode.current());
    }

    public CreateAutoRoutePacket(BlockPos sourceDockPos, BlockPos targetDockPos, WaterMidMode midMode) {
        this(sourceDockPos,targetDockPos,midMode,java.util.UUID.randomUUID());
    }
    public CreateAutoRoutePacket(BlockPos sourceDockPos,BlockPos targetDockPos,WaterMidMode midMode,java.util.UUID requestId) {
        this.requestId=requestId;
        this.sourceDockPos = sourceDockPos;
        this.targetDockPos = targetDockPos;
        this.midMode = midMode == null ? WaterMidMode.current() : midMode;
    }

    public static void encode(CreateAutoRoutePacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
        buf.writeUUID(msg.requestId);
        buf.writeBlockPos(msg.targetDockPos);
        buf.writeByte(msg.midMode.id());
    }

    public static CreateAutoRoutePacket decode(FriendlyByteBuf buf) {
        BlockPos src = buf.readBlockPos();
        java.util.UUID requestId=buf.readUUID();
        BlockPos tgt = buf.readBlockPos();
        WaterMidMode mode = WaterMidMode.fromId(buf.readByte());
        return new CreateAutoRoutePacket(src, tgt, mode,requestId);
    }

    public static void handle(CreateAutoRoutePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null || !(player.level() instanceof ServerLevel serverLevel)) return;

            DockBlockEntity sourceDock=com.monpai.sailboatmod.route.water.PortDockSnapshot.loaded(serverLevel,msg.sourceDockPos);
            if(sourceDock==null||!sourceDock.canManageDock(player)||player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(msg.sourceDockPos))>128.0*128.0){
                com.monpai.sailboatmod.network.ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(msg.sourceDockPos,msg.requestId,true,0,0,0,RouteWorkProgressPacket.FAILED,"码头不可用或没有管理权限"));return;}
            if(!(sourceDock instanceof PostStationBlockEntity)){
                if(!com.monpai.sailboatmod.dock.DockLocationSavedData.get(serverLevel)
                        .positionsIn(serverLevel.dimension().location().toString()).contains(msg.targetDockPos)){
                    com.monpai.sailboatmod.network.ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(msg.sourceDockPos,msg.requestId,true,0,0,0,RouteWorkProgressPacket.FAILED,"目标码头不在登记清单中"));return;}
                var session=com.monpai.sailboatmod.route.water.RouteUiSessions.begin(player,msg.requestId,msg.sourceDockPos,msg.targetDockPos);
                if(session==null)return;
                // Request completion, never join it on the server thread. A dependent future owns the timeout.
                serverLevel.getChunkSource().getChunkFuture(msg.targetDockPos.getX()>>4,msg.targetDockPos.getZ()>>4,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,true)
                    .thenApply(result->result).orTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((loaded,error)->serverLevel.getServer().execute(()->{
                        if(session.closed||player.isRemoved()||player.level()!=serverLevel)return;
                        DockBlockEntity target=com.monpai.sailboatmod.route.water.PortDockSnapshot.loaded(serverLevel,msg.targetDockPos);
                        DockBlockEntity current=com.monpai.sailboatmod.route.water.PortDockSnapshot.loaded(serverLevel,msg.sourceDockPos);
                        if(error!=null||current!=sourceDock||!com.monpai.sailboatmod.route.water.PortDockSnapshot.isPort(target)){
                            session.end(0,RouteWorkProgressPacket.FAILED,"码头加载失败，请重试");return;
                        }
                        WaterRouteResult<Void> result=WaterAutoRouteService.submitAutoRoute(serverLevel,sourceDock,target,player,msg.midMode);
                        if(!result.successful())session.end(0,RouteWorkProgressPacket.FAILED,WaterAutoRouteService.messageFor(result.reason()).getString());
                    }));
                return;
            }
            DockBlockEntity targetDock=serverLevel.getBlockEntity(msg.targetDockPos) instanceof DockBlockEntity d?d:null;
            if(targetDock==null)return;

            if (sourceDock instanceof PostStationBlockEntity && targetDock instanceof PostStationBlockEntity) {
                boolean success = RoadAutoRouteService.createAndSaveAutoRoute(serverLevel, sourceDock, targetDock);
                if (success) {
                    player.sendSystemMessage(Component.translatable(
                            "message.sailboatmod.auto_route.created",
                            targetDock.getDockName().isBlank() ? Component.translatable("block.sailboatmod.dock") : Component.literal(targetDock.getDockName())
                    ));
                } else {
                    player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.create_failed"));
                }
            } else if (!(sourceDock instanceof PostStationBlockEntity) && !(targetDock instanceof PostStationBlockEntity)) {
                WaterRouteResult<Void> result = WaterAutoRouteService.submitAutoRoute(serverLevel, sourceDock, targetDock, player, msg.midMode);
                if (result.successful()) {
                    player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.water.started"));
                } else {
                    player.sendSystemMessage(WaterAutoRouteService.messageFor(result.reason()));
                }
            } else {
                player.sendSystemMessage(Component.translatable("message.sailboatmod.auto_route.create_failed"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
