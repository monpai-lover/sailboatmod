package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.dock.DockLocationSavedData;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.RouteWorkProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncAutoRouteDocksPacket;
import com.monpai.sailboatmod.route.terrain.TellusTerrain;
import com.monpai.sailboatmod.route.terrain.TellusWorlds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.monpai.sailboatmod.route.water.PortBerthValidator.*;

/** Metadata IO and independent port checks run on bounded workers. No target chunk loads in the UI handler. */
@EventBusSubscriber(modid=SailboatMod.MODID)
public final class PortCandidateListing {
    private static final int MAX_PORTS=256;
    private static final Map<MinecraftServer,Map<UUID,Request>> REQUESTS=new WeakHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Long>> LAST_REQUEST=new WeakHashMap<>();
    private PortCandidateListing(){}
    public static synchronized boolean acceptRequest(MinecraftServer server,UUID player){
        long now=System.nanoTime();var times=LAST_REQUEST.computeIfAbsent(server,k->new HashMap<>());
        Long old=times.get(player);if(old!=null&&now-old<TimeUnit.SECONDS.toNanos(1))return false;
        times.put(player,now);return true;
    }
    public static synchronized void cancelPrevious(MinecraftServer server,UUID player){
        var requests=REQUESTS.get(server);if(requests!=null){Request old=requests.remove(player);if(old!=null)old.cancelled.set(true);}
    }
    public static synchronized void cancel(ServerPlayer player,UUID id){
        var requests=REQUESTS.get(player.getServer());Request r=requests==null?null:requests.get(player.getUUID());
        if(r!=null&&r.id.equals(id))cancelPrevious(player.getServer(),player.getUUID());
    }
    public static void request(ServerLevel level,DockBlockEntity source,ServerPlayer player,UUID id){
        cancelPrevious(level.getServer(),player.getUUID());
        List<BlockPos> positions=DockLocationSavedData.get(level).positionsIn(level.dimension().location().toString())
                .stream().filter(p->!p.equals(source.getBlockPos())).toList();
        Request r=new Request(level,source,player,id,positions.subList(0,Math.min(MAX_PORTS,positions.size())),positions.size()>MAX_PORTS);
        synchronized(PortCandidateListing.class){REQUESTS.computeIfAbsent(level.getServer(),k->new HashMap<>()).put(player.getUUID(),r);}
        r.publishNow(false,"读取码头 / 准备出发泊位");
        PortAsyncRuntime.worker(level.getServer(),()->{
            if(r.cancelled.get())return null;
            if(r.tellus){r.sourceProbe.start(false,4000,r.cancelled::get);r.start=r.sourceProbe.resolve(r.source.zone());}
            if(r.positions.isEmpty()){r.schedulePublish(true,"没有其它登记码头");return null;}
            // Three independent lanes, each with request-local terrain caches. No worker waits for another worker.
            for(int lane=0;lane<3;lane++)PortAsyncRuntime.worker(level.getServer(),()->{r.scan();return null;})
                    .whenComplete((v,e)->{if(e!=null)r.failLane();});
            return null;
        }).whenComplete((v,e)->{if(e!=null)r.schedulePublish(true,"检查队列繁忙，请重新验证");});
    }
    private static final class Request {
        final ServerLevel level;final ServerPlayer player;final UUID id;final PortDockSnapshot source;
        final List<BlockPos> positions;final boolean truncated,tellus;final PortTerrainProbe sourceProbe;
        final AtomicBoolean cancelled=new AtomicBoolean(),publishQueued=new AtomicBoolean();
        final AtomicInteger next=new AtomicInteger(),completed=new AtomicInteger(),lanes=new AtomicInteger(3),unreadable=new AtomicInteger();
        final ConcurrentMap<Integer,AvailableDockEntry> entries=new ConcurrentHashMap<>();
        volatile Result start=Result.pending();volatile boolean finished;volatile String stage="检查中";volatile long lastPublish;
        Request(ServerLevel l,DockBlockEntity d,ServerPlayer p,UUID i,List<BlockPos> ps,boolean more){
            level=l;player=p;id=i;source=PortDockSnapshot.of(d);positions=List.copyOf(ps);truncated=more;tellus=TellusWorlds.isTellusLevel(l);
            sourceProbe=PortTerrainProbe.capture(l,List.of(source.zone()),TellusTerrain.forLevel(l).orElse(null));
        }
        void scan(){
            try{
                int index;
                while(!cancelled.get()&&(index=next.getAndIncrement())<positions.size()){
                    BlockPos pos=positions.get(index);var target=PortDockSnapshot.read(level,pos);
                    if(cancelled.get())return;
                    if(target.isEmpty()){
                        unreadable.incrementAndGet();
                    }else{
                        PortDockSnapshot dock=target.get();
                        // SavedData is server-thread-owned; this callback performs no IO.
                        var access=PortAsyncRuntime.onMain(level.getServer(),()->{
                            NationSavedData data=NationSavedData.get(level);
                            var permission=WaterRoutePermissionService.evaluate(source.access(),dock.access(),(a,b)->{
                                var relation=data.getDiplomacy(a,b);return relation==null?"":relation.statusId();});
                            var nation=data.getNation(dock.nation());
                            return permission.successful()?new AvailableDockEntry(pos,dock.name(),dock.owner(),
                                    nation==null?"-":nation.name(),(int)Math.sqrt(source.pos().distSqr(pos))):null;
                        });
                        AvailableDockEntry entry=access.get(1,TimeUnit.SECONDS);
                        if(entry!=null){
                            Verdict verdict=Verdict.PENDING;
                            if(tellus){
                                PortTerrainProbe probe=PortAsyncRuntime.onMain(level.getServer(),()->PortTerrainProbe.capture(
                                    level,List.of(dock.zone()),TellusTerrain.forLevel(level).orElse(null))).get(1,TimeUnit.SECONDS);
                                probe.start(false,1500,cancelled::get);verdict=combine(start.verdict(),probe.resolve(dock.zone()).verdict());
                            }
                            if(verdict!=Verdict.INVALID)entries.put(index,labelled(entry,verdict));
                        }
                    }
                    completed.incrementAndGet();schedulePublish(false,"泊位与出港水域检查");
                }
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(Exception unavailable){unreadable.incrementAndGet();}
            finally{if(lanes.decrementAndGet()==0)schedulePublish(true,"检查结束");}
        }
        void failLane(){if(lanes.decrementAndGet()==0)schedulePublish(true,"检查队列繁忙，请重新验证");}
        void schedulePublish(boolean done,String text){
            if(cancelled.get())return;finished|=done;stage=text;
            if(!done&&System.nanoTime()-lastPublish<200_000_000L)return;
            if(!publishQueued.compareAndSet(false,true))return;
            PortAsyncRuntime.onMain(level.getServer(),()->{
                publishQueued.set(false);if(!cancelled.get())publishNow(finished,stage);return null;
            }).exceptionally(e->{publishQueued.set(false);return null;});
        }
        void publishNow(boolean done,String text){
            if(cancelled.get()||player.isRemoved()||player.level()!=level)return;
            DockBlockEntity live=PortDockSnapshot.loaded(level,source.pos());
            if(!PortDockSnapshot.isPort(live)||!PortDockSnapshot.of(live).equals(source)){
                cancelled.set(true);ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(source.pos(),id,false,0,0,
                        positions.size(),RouteWorkProgressPacket.FAILED,"出发码头已改变，请重新打开"));return;
            }
            List<AvailableDockEntry> visible=entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
            ModNetwork.CHANNEL.send(player,new SyncAutoRouteDocksPacket(source.pos(),TransportTerminalKind.PORT,visible,id));
            int count=Math.min(completed.get(),positions.size());
            int percent=positions.isEmpty()?100:100*count/positions.size();
            String label=text+(unreadable.get()>0?"；部分码头数据未读到，请重试":"")+(truncated?"；本轮最多256个码头":"");
            ModNetwork.CHANNEL.send(player,new RouteWorkProgressPacket(source.pos(),id,false,percent,count,positions.size(),
                    done?RouteWorkProgressPacket.DONE:RouteWorkProgressPacket.RUNNING,label));
            lastPublish=System.nanoTime();
        }
    }
    static Verdict combine(Verdict source,Verdict target){
        if(source==Verdict.INVALID||target==Verdict.INVALID)return Verdict.INVALID;
        if(source==Verdict.PENDING||target==Verdict.PENDING)return Verdict.PENDING;
        if(source==Verdict.PREDICTED||target==Verdict.PREDICTED)return Verdict.PREDICTED;return Verdict.VERIFIED;
    }
    static AvailableDockEntry labelled(AvailableDockEntry e,Verdict v){
        String prefix=switch(v){case PENDING->"[地形待验证] ";case PREDICTED->"[Tellus预检] ";case VERIFIED->"[泊位已验证] ";default->"";};
        String name=e.dockName()==null?"":e.dockName();int end=Math.min(name.length(),64-prefix.length());
        if(end>0&&end<name.length()&&Character.isHighSurrogate(name.charAt(end-1)))end--;
        return new AvailableDockEntry(e.pos(),prefix+name.substring(0,end),e.ownerName(),e.nationName(),e.distance());
    }
    public static Zone zone(DockBlockEntity d){BlockPos p=d.getBlockPos();return new Zone(p.getX(),p.getY(),p.getZ(),d.getZoneMinX(),d.getZoneMaxX(),d.getZoneMinZ(),d.getZoneMaxZ());}
    @SubscribeEvent public static synchronized void logout(PlayerEvent.PlayerLoggedOutEvent e){
        if(e.getEntity() instanceof ServerPlayer p){cancelPrevious(p.getServer(),p.getUUID());
            var times=LAST_REQUEST.get(p.getServer());if(times!=null)times.remove(p.getUUID());}
    }
    @SubscribeEvent public static synchronized void stopped(ServerStoppingEvent e){
        LAST_REQUEST.remove(e.getServer());var requests=REQUESTS.remove(e.getServer());
        if(requests!=null)requests.values().forEach(r->r.cancelled.set(true));
    }
}
