package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.network.*;
import com.monpai.sailboatmod.route.water.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import java.util.function.Supplier;

/** User intent: send this carrier to this port, not execute an index in a mutable route list. */
public record DepartForDestinationPacket(BlockPos source,BlockPos target,UUID vessel,int cargoIndex,UUID request,
                                         RoutePlanningPreference preference) {
    public DepartForDestinationPacket {
        preference=preference==null?RoutePlanningPreference.REUSE_FIRST:preference;
    }
    public DepartForDestinationPacket(BlockPos source,BlockPos target,UUID vessel,int cargoIndex,UUID request){
        this(source,target,vessel,cargoIndex,request,RoutePlanningPreference.REUSE_FIRST);
    }
    public static void encode(DepartForDestinationPacket p,FriendlyByteBuf b){
        b.writeBlockPos(p.source);b.writeBlockPos(p.target);b.writeUUID(p.vessel);b.writeVarInt(p.cargoIndex);b.writeUUID(p.request);
        b.writeByte(p.preference.id());
    }
    public static DepartForDestinationPacket decode(FriendlyByteBuf b){
        return new DepartForDestinationPacket(b.readBlockPos(),b.readBlockPos(),b.readUUID(),b.readVarInt(),b.readUUID(),
                RoutePlanningPreference.fromId(b.readByte()));
    }
    public static void handle(DepartForDestinationPacket p,Supplier<NetworkEvent.Context> context){
        context.get().enqueueWork(()->{
            ServerPlayer player=context.get().getSender();if(player==null)return;
            var level=player.serverLevel();DockBlockEntity dock=PortDockSnapshot.loaded(level,p.source);
            if(!PortDockSnapshot.isPort(dock)||player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p.source))>128*128
                    ||!(level.getEntity(p.vessel) instanceof SailboatEntity boat)||!dock.canPlanDestination(boat,player)
                    ||p.cargoIndex < -1 || p.cargoIndex>4096 || p.source.equals(p.target)
                    ||!com.monpai.sailboatmod.dock.DockLocationSavedData.get(level).positionsIn(level.dimension().location().toString()).contains(p.target)) {
                fail(player,p,"船只不可调度、目的地不存在或没有权限");return;
            }
            var cargo=p.cargoIndex<0?null:dock.captureDestinationCargo(player,p.cargoIndex);
            if(p.cargoIndex>=0&&cargo==null){fail(player,p,"仓储选择已改变或没有权限");return;}
            var session=RouteUiSessions.begin(player,p.request,p.source,p.target);if(session==null)return;
            level.getChunkSource().getChunkFuture(p.target.getX()>>4,p.target.getZ()>>4,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL,true).thenApply(v->v)
                    .orTimeout(20,java.util.concurrent.TimeUnit.SECONDS).whenComplete((loaded,error)->level.getServer().execute(()->{
                if(session.closed||player.isRemoved()||player.level()!=level)return;
                var destination=PortDockSnapshot.loaded(level,p.target);
                if(error!=null||PortDockSnapshot.loaded(level,p.source)!=dock||!PortDockSnapshot.isPort(destination)
                        ||!dock.canPlanDestination(boat,player)) {session.end(0,RouteWorkProgressPacket.FAILED,"码头或船只状态已改变");return;}
                try {
                var result=WaterAutoRouteService.submitDestinationRoute(level,dock,destination,player,planned->{
                    if(session.closed||player.isRemoved()||player.level()!=level||!planned.successful())return;
                    if(!dock.canPlanDestination(boat,player)||dock.isRemoved()||destination.isRemoved()
                            ||!WaterRouteCandidateService.canListCandidate(level,dock,destination).successful()) {
                        session.end(0,RouteWorkProgressPacket.FAILED,"航路已准备，但船只或权限已变化；未扣费、未发船");return;
                    }
                    boolean started=cargo==null?dock.assignBoatToDestination(boat,planned.value(),player)
                            :dock.dispatchCargoToDestination(player,boat,planned.value(),cargo);
                    session.end(started?100:0,started?RouteWorkProgressPacket.DONE:RouteWorkProgressPacket.FAILED,
                            started?"已按目的地出发":"航路已准备，但出发条件已变化");
                    player.sendSystemMessage(Component.literal(started?"已出发前往 "+destination.getDockName():"未发船：请检查船只、租金或货物状态"));
                },p.preference);
                if(!result.successful())session.end(0,RouteWorkProgressPacket.FAILED,WaterAutoRouteService.messageFor(result.reason()).getString());
                } catch(RuntimeException failure) {
                    session.end(0,RouteWorkProgressPacket.FAILED,"目的地任务准备失败，未发船");
                    com.mojang.logging.LogUtils.getLogger().warn("[WaterPath] 玩家目的地任务准备失败 {}",p.request,failure);
                }
            }));
        });context.get().setPacketHandled(true);
    }
    private static void fail(ServerPlayer player,DepartForDestinationPacket p,String why){
        ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(p.source,p.request,true,0,0,0,RouteWorkProgressPacket.FAILED,why));
    }
}
