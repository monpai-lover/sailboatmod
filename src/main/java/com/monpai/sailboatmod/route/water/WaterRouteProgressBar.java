package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.server.level.ServerBossEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Worker writes a mailbox; the server tick coalesces UI/bossbar updates to <=5 Hz. */
@EventBusSubscriber(modid=SailboatMod.MODID)
public final class WaterRouteProgressBar {
    private static final Set<WaterRouteProgressBar> ACTIVE=ConcurrentHashMap.newKeySet();
    private final MinecraftServer server;private final ServerPlayer player;private final String name;
    private final ServerBossEvent bar;private final RouteUiSessions.Session session;
    private volatile Update latest=new Update(0,"排队中",0);
    private long lastSend,removeAt;private Update sent;private boolean shown;
    private record Update(int percent,String stage,int state){}
    public WaterRouteProgressBar(MinecraftServer server,ServerPlayer player,String source,String target){
        this(server, player, source, target, null, null);
    }
    public WaterRouteProgressBar(MinecraftServer server, ServerPlayer player, String source, String target,
                                 net.minecraft.core.BlockPos sourcePos, net.minecraft.core.BlockPos targetPos) {
        this.server=server;this.player=player;name=(source==null?"":source)+" → "+(target==null?"":target);
        session=sourcePos == null || targetPos == null ? RouteUiSessions.current(player)
                : RouteUiSessions.forRoute(player, sourcePos, targetPos);
        bar=new ServerBossEvent(Component.literal("航线计算 "+name),BossEvent.BossBarColor.BLUE,BossEvent.BossBarOverlay.PROGRESS);
    }
    public void show(){if(server!=null&&player!=null)ACTIVE.add(this);}
    public synchronized void update(int percent,String stage){
        if(latest.state()!=0)return;
        latest=new Update(Math.max(latest.percent(),Math.clamp(percent,0,99)),stage==null?"计算中":stage,0);
    }
    public synchronized void done(){latest=new Update(100,"完成",RouteWorkProgressPacket.DONE);}
    public synchronized void fail(String reason){latest=new Update(latest.percent(),reason==null?"失败":reason,RouteWorkProgressPacket.FAILED);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        long now=System.nanoTime();
        for(WaterRouteProgressBar p:ACTIVE){
            if(p.server!=e.getServer())continue;
            if(p.player.isRemoved() || (p.session!=null && p.session.closed && (p.sent==null || p.sent.state()==0))){
                p.bar.removeAllPlayers();ACTIVE.remove(p);continue;
            }
            Update u=p.latest;
            if(!p.shown){p.bar.addPlayer(p.player);p.shown=true;}
            if(!u.equals(p.sent)&&(u.state()!=0||now-p.lastSend>=200_000_000L)){
                p.bar.setName(Component.literal("航线计算 "+p.name+"："+u.stage()+" "+u.percent()+"%"));
                p.bar.setProgress(u.percent()/100f);
                p.bar.setColor(u.state()==0?BossEvent.BossBarColor.BLUE:u.state()==1?BossEvent.BossBarColor.GREEN:BossEvent.BossBarColor.RED);
                if(p.session!=null){if(u.state()==0)p.session.send(u.percent(),u.state(),u.stage());else p.session.end(u.percent(),u.state(),u.stage());}
                p.sent=u;p.lastSend=now;if(u.state()!=0)p.removeAt=now+2_500_000_000L;
            }
            if(p.removeAt!=0&&now>=p.removeAt){p.bar.removeAllPlayers();ACTIVE.remove(p);}
        }
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent e){
        ACTIVE.removeIf(p->{if(p.server!=e.getServer())return false;p.bar.removeAllPlayers();return true;});
    }
}
