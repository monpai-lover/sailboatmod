package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Market dispatch reads the same saved graph as player dispatch. NEVER creates a missing route.
 * Async work is graph traversal only. Pending graph views do not reserve cargo, money or world chunks.
 */
@EventBusSubscriber(modid=SailboatMod.MODID)
public final class WaterDestinationService {
    private static final class View {
        final KnownVoyageService.Catalog catalog; volatile boolean cancelled; boolean ready;
        Map<BlockPos,KnownWaterRouteGraph.Itinerary> trips=Map.of();
        View(KnownVoyageService.Catalog catalog){this.catalog=catalog;}
    }
    private static final Map<ServerLevel,Map<BlockPos,View>> VIEWS=new WeakHashMap<>();
    private WaterDestinationService() { }
    public static RouteDefinition knownItinerary(ServerLevel level,DockBlockEntity source,BlockPos target) {
        if(!PortDockSnapshot.isPort(source)||target.equals(source.getBlockPos()))return null;
        var catalog=KnownVoyageService.catalog(level,source.getBlockPos(),null);
        if(!catalog.ports().containsKey(target))return null;
        var cache=VIEWS.computeIfAbsent(level,l->new LinkedHashMap<>());View view=cache.get(source.getBlockPos());
        if(view==null||view.catalog.revision()!=catalog.revision()||!view.catalog.routes().equals(catalog.routes())) {
            if(view!=null)view.cancelled=true;
            if(cache.size()>=64&&!cache.containsKey(source.getBlockPos()))return null;
            if(cache.values().stream().filter(v->!v.ready&&!v.cancelled).count()>=8)return null;
            View requested=new View(catalog);cache.put(source.getBlockPos().immutable(),requested);
            BlockPos origin=source.getBlockPos().immutable();
            WaterRouteTaskExecutor.submit(()->WaterRouteCancellation.run(()->requested.cancelled,
                    ()->KnownWaterRouteGraph.build(catalog.routes()).fromDock(origin)))
                    .whenComplete((trips,error)->level.getServer().execute(()->{
                        if(requested.cancelled)return;
                        requested.trips=error==null?trips:Map.of();requested.ready=true;
                    }));
            return null;
        }
        if(!view.ready)return null;
        var trip=view.trips.get(target);if(trip==null)return null;
        return KnownVoyageService.definition(trip,source.getDockName(),catalog.ports().get(target).dockName());
    }
    public static int ensureMarketRoute(ServerLevel level,DockBlockEntity source,DockBlockEntity target) {
        if(!PortDockSnapshot.isPort(target))return -1;
        var itinerary=knownItinerary(level,source,target.getBlockPos());
        return itinerary==null?-1:source.installKnownVoyage(target.getBlockPos(),itinerary,WaterRouteNetworkSavedData.get(level).revision());
    }
    public static boolean pending(ServerLevel level,BlockPos from,BlockPos to){
        var views=VIEWS.get(level);var view=views==null?null:views.get(from);return view!=null&&!view.ready&&!view.cancelled;
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){
        VIEWS.entrySet().removeIf(e->{if(e.getKey().getServer()!=event.getServer())return false;e.getValue().values().forEach(v->v.cancelled=true);return true;});
    }
}
