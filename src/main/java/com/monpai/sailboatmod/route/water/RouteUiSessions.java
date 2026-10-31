package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;

/** Server-thread-owned route UI sessions; stale/cross-player cancellation is rejected. */
@EventBusSubscriber(modid=SailboatMod.MODID)
public final class RouteUiSessions {
    private static final Map<MinecraftServer,Map<UUID,Session>> SESSIONS=new WeakHashMap<>();
    private static final net.minecraft.server.level.TicketType<UUID> ROUTE_TICKET=net.minecraft.server.level.TicketType.create("sailboatmod_route_ui",UUID::compareTo);
    private RouteUiSessions() {}
    public static final class Session {
        public final ServerPlayer player;
        public final UUID request;
        public final BlockPos source,target;
        public final String key;
        public volatile boolean closed;
        public final net.minecraft.server.level.ServerLevel level;
        private final long started=System.nanoTime();
        Session(ServerPlayer p,UUID r,BlockPos s,BlockPos t) {
            player=p;request=r;source=s.immutable();target=t.immutable();level=p.serverLevel();
            level.getChunkSource().addRegionTicket(ROUTE_TICKET,new net.minecraft.world.level.ChunkPos(source),0,request);
            level.getChunkSource().addRegionTicket(ROUTE_TICKET,new net.minecraft.world.level.ChunkPos(target),0,request);
            key=p.level().dimension().location()+"|"+s.asLong()+"|"+t.asLong();
        }
        public void send(int percent,int state,String stage) {
            if(!player.isRemoved()) ModNetwork.CHANNEL.send(player,
                new RouteWorkProgressPacket(source,request,true,percent,0,0,state,stage));
        }
        public void end(int percent,int state,String stage) {
            if(closed)return;send(percent,state,stage);closed=true;
            level.getChunkSource().removeRegionTicket(ROUTE_TICKET,new net.minecraft.world.level.ChunkPos(source),0,request);
            level.getChunkSource().removeRegionTicket(ROUTE_TICKET,new net.minecraft.world.level.ChunkPos(target),0,request);
        }
    }
    public static Session current(ServerPlayer p) {
        var map=p==null?null:SESSIONS.get(p.getServer());return map==null?null:map.get(p.getUUID());
    }
    public static Session begin(ServerPlayer p,UUID id,BlockPos src,BlockPos dst) {
        Session old=current(p);
        if(old!=null&&!old.closed) {
            ModNetwork.CHANNEL.send(p,new RouteWorkProgressPacket(src,id,true,0,0,0,
                RouteWorkProgressPacket.BUSY,"已有航线任务运行，请先取消或等其完成"));return null;
        }
        Session next=new Session(p,id,src,dst);
        SESSIONS.computeIfAbsent(p.getServer(),k->new HashMap<>()).put(p.getUUID(),next);
        next.send(0,RouteWorkProgressPacket.RUNNING,"异步载入两端码头");return next;
    }
    public static void cancel(ServerPlayer p,UUID id) {
        Session s=current(p);if(s==null||!s.request.equals(id)||s.closed)return;
        WaterRouteTaskService.global().cancel(s.key);
        s.end(0,RouteWorkProgressPacket.CANCELLED,"已取消");
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if(e.getEntity() instanceof ServerPlayer p){Session s=current(p);if(s!=null)cancel(p,s.request);
            var map=SESSIONS.get(p.getServer());if(map!=null)map.remove(p.getUUID());}
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        var map=SESSIONS.get(e.getServer());if(map==null)return;
        for(Session s:map.values())if(!s.closed&&(s.player.level()!=s.level||System.nanoTime()-s.started>600_000_000_000L)){
            WaterRouteTaskService.global().cancel(s.key);s.end(0,RouteWorkProgressPacket.CANCELLED,"任务已超时或玩家离开维度");}
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent e){
        var map=SESSIONS.remove(e.getServer());if(map!=null)map.values().forEach(s->s.end(0,RouteWorkProgressPacket.CANCELLED,"服务器停止"));
    }
}
