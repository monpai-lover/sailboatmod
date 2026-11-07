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

    public record Destination(BlockPos pos, String name, double distance) { }
    public record Departures(boolean pending, List<Destination> destinations) { }

    public static Departures knownDestinations(ServerLevel level, DockBlockEntity source, boolean requireReturn) {
        if (!PortDockSnapshot.isPort(source)) return new Departures(false, List.of());
        BlockPos origin = source.getBlockPos();
        var catalog = KnownVoyageService.catalog(level, origin, null);
        if (!catalog.ports().containsKey(origin) || catalog.routes().isEmpty()) return new Departures(false, List.of());
        View view = view(level, origin, catalog);
        if (view == null || !view.ready) return new Departures(true, List.of());
        List<Destination> destinations = view.trips.entrySet().stream()
                .filter(entry -> !origin.equals(entry.getKey()) && catalog.ports().containsKey(entry.getKey()))
                .filter(entry -> !requireReturn || KnownVoyageService.reversible(catalog, entry.getValue().routeIds()))
                .map(entry -> new Destination(entry.getKey(), catalog.ports().get(entry.getKey()).dockName(), entry.getValue().length()))
                .sorted(Comparator.comparingDouble(Destination::distance).thenComparingLong(value -> value.pos().asLong()))
                .limit(512)
                .toList();
        return new Departures(false, destinations);
    }

    public static RouteDefinition knownItinerary(ServerLevel level,DockBlockEntity source,BlockPos target) {
        return knownItinerary(level, source, target, false);
    }

    public static RouteDefinition knownItinerary(ServerLevel level, DockBlockEntity source, BlockPos target, boolean requireReturn) {
        if (!PortDockSnapshot.isPort(source) || target.equals(source.getBlockPos())) return null;
        var catalog = KnownVoyageService.catalog(level, source.getBlockPos(), null);
        var port = catalog.ports().get(target);
        if (port == null || !catalog.ports().containsKey(source.getBlockPos())) return null;
        View view = view(level, source.getBlockPos(), catalog);
        if (view == null || !view.ready) return null;
        var trip = view.trips.get(target);
        if (trip == null || requireReturn && !KnownVoyageService.reversible(catalog, trip.routeIds())) return null;
        return KnownVoyageService.definition(trip, source.getDockName(), port.dockName());
    }

    private static View view(ServerLevel level, BlockPos origin, KnownVoyageService.Catalog catalog) {
        var cache = VIEWS.computeIfAbsent(level, ignored -> new LinkedHashMap<>(16, .75F, true));
        View current = cache.get(origin);
        if (current != null && !current.cancelled && current.catalog.revision() == catalog.revision()
                && current.catalog.routes().equals(catalog.routes())) return current;
        if (current != null) current.cancelled = true;
        if (cache.values().stream().filter(value -> !value.ready && !value.cancelled).count() >= 8) return null;
        if (cache.size() >= 64 && current == null) {
            BlockPos oldestReady = cache.entrySet().stream()
                    .filter(entry -> entry.getValue().ready || entry.getValue().cancelled)
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (oldestReady == null) return null;
            cache.remove(oldestReady);
        }
        View requested = new View(catalog);
        BlockPos source = origin.immutable();
        cache.put(source, requested);
        WaterRouteTaskExecutor.submit(() -> WaterRouteCancellation.run(() -> requested.cancelled,
                () -> KnownWaterRouteGraph.build(catalog.routes()).fromDock(source)))
                .whenComplete((trips, error) -> level.getServer().execute(() -> {
                    if (requested.cancelled) return;
                    requested.trips = error == null ? trips : Map.of();
                    requested.ready = true;
                }));
        return requested;
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
