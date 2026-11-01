package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.market.terminal.TerminalNetworkSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.KnownDestinationsPacket;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Dispatch and route construction intentionally have different entry points.
 * Menu opening computes an immutable graph view off-thread. Clicking a dock destination
 * consumes that view and does NO terrain planning, remote chunk loading, or route registration.
 * Ship-origin requests alone may compute a bounded local connector, never a new long route.
 */
@EventBusSubscriber(modid=SailboatMod.MODID)
public final class KnownVoyageService {
    record Catalog(long revision,Map<BlockPos,TerminalNetworkSavedData.TerminalEntry> ports,
                   List<WaterRouteNetwork.Route> routes) { }
    private static final class Lease {
        final UUID query,owner,vessel; final ServerLevel level; final BlockPos source;
        final boolean aboard; final int cargo; final long created; final Catalog catalog;
        volatile boolean cancelled; boolean consumed; KnownWaterRouteGraph graph;
        Map<BlockPos,KnownWaterRouteGraph.Itinerary> trips=Map.of();Set<BlockPos> targets=Set.of();
        Lease(UUID q,ServerPlayer p,UUID v,BlockPos s,boolean a,int c,Catalog cat){
            query=q;owner=p.getUUID();vessel=v;level=p.serverLevel();source=s;aboard=a;cargo=c;catalog=cat;created=level.getGameTime();
        }
    }
    private static final Map<MinecraftServer,Map<UUID,Lease>> LEASES=new WeakHashMap<>();
    private KnownVoyageService() { }
    static Catalog catalog(ServerLevel level,BlockPos source,ServerPlayer player) {
        String dim=level.dimension().location().toString();
        Map<BlockPos,TerminalNetworkSavedData.TerminalEntry> ports=new LinkedHashMap<>();
        for(var p:TerminalNetworkSavedData.get(level).all())if(p.dimId().equals(dim)&&p.kind()==TerminalNetworkSavedData.Kind.PORT)
            ports.put(BlockPos.of(p.packedPos()),p);
        String nation="";
        if(source!=null&&ports.containsKey(source))nation=ports.get(source).nationId();
        else if(player!=null){var member=NationSavedData.get(level).getMember(player.getUUID());if(member!=null)nation=member.nationId();}
        final String actorNation=nation;
        ports.entrySet().removeIf(e->!allowed(level,actorNation,e.getValue()));
        var data=WaterRouteNetworkSavedData.get(level);
        var routes=data.snapshot().stream().filter(r->ports.containsKey(r.sourceDock())&&ports.containsKey(r.targetDock())).toList();
        return new Catalog(data.revision(),Map.copyOf(ports),routes);
    }
    private static boolean allowed(ServerLevel level,String nation,TerminalNetworkSavedData.TerminalEntry p) {
        if(nation==null||nation.isBlank()||p.nationId().isBlank()||p.townId().isBlank())return false;
        if(nation.equals(p.nationId()))return true;
        var relation=NationSavedData.get(level).getDiplomacy(nation,p.nationId());
        return relation!=null&&("allied".equalsIgnoreCase(relation.statusId())||"trade".equalsIgnoreCase(relation.statusId()));
    }
    private static SailboatEntity authorized(ServerPlayer player,BlockPos source,UUID vessel,boolean aboard) {
        if(!(player.serverLevel().getEntity(vessel) instanceof SailboatEntity boat)||!boat.isAlive()||boat.isAutopilotActive())return null;
        if(aboard)return player.getVehicle()==boat&&boat.isCaptain(player)&&boat.getPendingShipmentManifest().isEmpty()?boat:null;
        var dock=PortDockSnapshot.loaded(player.serverLevel(),source);
        return PortDockSnapshot.isPort(dock)&&player.distanceToSqr(Vec3.atCenterOf(source))<=128*128
                &&dock.canPlanDestination(boat,player)?boat:null;
    }
    public static void query(ServerPlayer player,UUID query,UUID vessel,BlockPos source,boolean aboard,int cargo) {
        if(player==null||cargo < -1||cargo>4096||aboard&&cargo!=-1)return;
        SailboatEntity boat=authorized(player,source,vessel,aboard);
        if(boat==null){reply(player,query,List.of(),"船只不可调度：请先停止航行，并确认驾驶/调度权限。",false);return;}
        var leases=LEASES.computeIfAbsent(player.serverLevel().getServer(),s->new HashMap<>());
        leases.values().removeIf(l->{if(l.level.getGameTime()-l.created>2400||l.cancelled){l.cancelled=true;return true;}return false;});
        Lease previous=leases.get(player.getUUID());
        if(previous!=null&&previous.level==player.serverLevel()&&previous.level.getGameTime()-previous.created<10){
            reply(player,query,List.of(),"查询过于频繁，请稍后刷新。",false);return;
        }
        if(previous!=null)previous.cancelled=true;
        if(leases.size()>=32&&!leases.containsKey(player.getUUID())){reply(player,query,List.of(),"目的地菜单繁忙，请稍后重开。",false);return;}
        Catalog cat=catalog(player.serverLevel(),aboard?null:source,player);
        Lease lease=new Lease(query,player,vessel,source,aboard,cargo,cat);leases.put(player.getUUID(),lease);
        Vec3 origin=boat.position();
        WaterRouteTaskExecutor.submit(()->WaterRouteCancellation.run(()->lease.cancelled,()->{
            var graph=KnownWaterRouteGraph.build(cat.routes());
            var trips=aboard?Map.<BlockPos,KnownWaterRouteGraph.Itinerary>of():graph.fromDock(source);
            return new Object[]{graph,trips,aboard?graph.reachableNear(origin):trips.keySet()};
        })).whenComplete((result,error)->lease.level.getServer().execute(()->{
            if(!validLease(player,lease)||lease.cancelled)return;
            if(error!=null||cat.revision()!=WaterRouteNetworkSavedData.get(lease.level).revision()){
                lease.cancelled=true;reply(player,query,List.of(),"航路网络已变化或菜单查询失败，请刷新。",false);return;
            }
            lease.graph=(KnownWaterRouteGraph)result[0];
            @SuppressWarnings("unchecked") var trips=(Map<BlockPos,KnownWaterRouteGraph.Itinerary>)result[1];lease.trips=trips;
            @SuppressWarnings("unchecked") var targets=(Set<BlockPos>)result[2];lease.targets=Set.copyOf(targets);
            List<KnownDestinationsPacket.Port> rows=new ArrayList<>();
            for(BlockPos target:lease.targets){
                var port=cat.ports.get(target);if(port==null)continue;
                var trip=trips.get(target);double distance=trip==null?Math.sqrt(target.distSqr(boat.blockPosition())):trip.length();
                boolean returns=!aboard&&trip!=null&&reversible(cat,trip.routeIds());
                rows.add(new KnownDestinationsPacket.Port(target,port.dockName(),distance,returns));
            }
            rows.sort(Comparator.comparingDouble(KnownDestinationsPacket.Port::distance));
            reply(player,query,rows,rows.isEmpty()?"没有连通的已知航路。请在航线管理中先创建航线。"
                    :aboard?"只补算当前位置到已知航路的安全接入段；点击目的地前往。":"只使用已知航路；点击目的地立即出发。",false);
        }));
    }
    private static boolean reversible(Catalog c,Set<String> used){return c.routes.stream().filter(r->used.contains(r.id())).allMatch(WaterRouteNetwork.Route::reversible);}
    private static boolean validLease(ServerPlayer p,Lease l){return !p.isRemoved()&&p.serverLevel()==l.level&&l.level.getGameTime()-l.created<=2400;}
    public static void select(ServerPlayer player,UUID query,BlockPos target,boolean autoReturn,boolean autoUnload) {
        if(player==null)return;var leases=LEASES.get(player.serverLevel().getServer());Lease l=leases==null?null:leases.get(player.getUUID());
        if(l==null||l.cancelled||l.consumed||!l.query.equals(query)||!validLease(player,l)||l.graph==null||!l.targets.contains(target))return;
        var boat=authorized(player,l.source,l.vessel,l.aboard);
        Catalog fresh=catalog(l.level,l.aboard?null:l.source,player);
        if(boat==null||fresh.revision!=l.catalog.revision||!fresh.routes.equals(l.catalog.routes)){
            l.cancelled=true;reply(player,query,List.of(),"船只、权限或航路已改变；未扣费，未出发。",false);return;
        }
        l.consumed=true;
        if(!l.aboard){
            var trip=l.trips.get(target);
            if(trip==null||autoReturn&&!reversible(fresh,trip.routeIds())){reply(player,query,List.of(),"当前航程不可原路返航。",false);return;}
            var dock=PortDockSnapshot.loaded(l.level,l.source);
            RouteDefinition route=definition(trip,l.catalog.ports.get(l.source).dockName(),l.catalog.ports.get(target).dockName());
            // A dock can berth a ship several blocks from the saved entry. Do not skip that entry.
            // This is a bounded LIVE collision check, not terrain search or route construction.
            route=boat.prepareKnownDeparture(route);
            if(route==null){reply(player,query,List.of(),"船只尚未安全接入起始泊位。请驶入泊位，或上船按 R 接入航路。",false);return;}
            var cargo=l.cargo<0?null:dock.captureDestinationCargo(player,l.cargo);
            boolean started=l.cargo<0?dock.assignBoatToDestination(boat,route,player,autoReturn,autoUnload)
                    :cargo!=null&&dock.dispatchCargoToDestination(player,boat,route,cargo);
            if(started){boat.setAllowNonOrderAutoReturn(autoReturn);boat.setAllowNonOrderAutoUnload(autoUnload);boat.setKnownDestination(target);}
            reply(player,query,List.of(),started?"已出发前往 "+l.catalog.ports.get(target).dockName():"出发条件或货物已变化；未发船。",started);
            return;
        }
        // R-menu origin has no dock to return to. Do not guess a home port or silently create one.
        if(autoReturn){reply(player,query,List.of(),"海上临时航程没有出发码头，不支持自动返港。",false);return;}
        Vec3 origin=boat.position();
        reply(player,query,List.of(),"正在验证短接入段，未启动全程寻路…",false);
        WaterRouteTaskExecutor.submit(()->WaterRouteCancellation.run(()->l.cancelled,()->{
            var entries=l.graph.entries(origin,target,8);
            return KnownWaterEntryPlanner.plan(l.level,origin,entries,()->l.cancelled);
        })).whenComplete((connected,error)->l.level.getServer().execute(()->{
            if(!validLease(player,l)||l.cancelled)return;
            var current=authorized(player,l.source,l.vessel,true);
            var now=catalog(l.level,null,player);
            if(current!=boat||origin.distanceToSqr(boat.position())>2.25||now.revision!=fresh.revision||!now.routes.equals(fresh.routes)){
                reply(player,query,List.of(),"接入期间船位或航路改变；未启航，请重新选择。",false);return;
            }
            if(error!=null||connected==null||!connected.successful()){
                reply(player,query,List.of(),"无法安全接入已知航路；可能地形未就绪或存在障碍，未创建新航线。",false);return;
            }
            var path=connected.value();
            var itinerary=new KnownWaterRouteGraph.Itinerary(path,KnownWaterRouteGraph.length(path),Set.of());
            RouteDefinition route=definition(itinerary,"当前位置",fresh.ports.get(target).dockName());
            boolean started=boat.startKnownVoyageFromPosition(route,null,target,player,false,autoUnload);
            reply(player,query,List.of(),started?"已接入航路，前往 "+fresh.ports.get(target).dockName():"当前状态不允许出发。",started);
        }));
    }
    public static void cancel(ServerPlayer p,UUID query){var leases=LEASES.get(p.serverLevel().getServer());if(leases==null)return;var l=leases.get(p.getUUID());if(l!=null&&l.query.equals(query)){l.cancelled=true;leases.remove(p.getUUID());}}
    static RouteDefinition definition(KnownWaterRouteGraph.Itinerary p,String source,String target){return new RouteDefinition("Auto-"+source+"-"+target,p.points(),"Known network","",System.currentTimeMillis(),p.length(),source,target);}
    private static void reply(ServerPlayer p,UUID q,List<KnownDestinationsPacket.Port> ports,String message,boolean departed){
        ModNetwork.CHANNEL.send(p,new KnownDestinationsPacket(q,ports.stream().limit(512).toList(),message,departed));
    }
    @SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e){
        var leases=LEASES.get(e.getServer());if(leases==null)return;
        leases.values().removeIf(l->{var p=e.getServer().getPlayerList().getPlayer(l.owner);
            boolean expired=l.cancelled||p==null||p.serverLevel()!=l.level||l.level.getGameTime()-l.created>2400;
            if(expired)l.cancelled=true;return expired;});
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent e){var leases=LEASES.remove(e.getServer());if(leases!=null)leases.values().forEach(l->l.cancelled=true);}
}
