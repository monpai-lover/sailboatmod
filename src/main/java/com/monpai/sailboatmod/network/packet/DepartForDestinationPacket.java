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
        context.get().enqueueWork(()->{var player=context.get().getSender();if(player!=null)
            fail(player,p,"请从“选择目的地并出发”打开已知航路菜单；发船不会创建新航线。");
        });context.get().setPacketHandled(true);
    }
    private static void fail(ServerPlayer player,DepartForDestinationPacket p,String why){
        ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(p.source,p.request,true,0,0,0,RouteWorkProgressPacket.FAILED,why));
    }
}
