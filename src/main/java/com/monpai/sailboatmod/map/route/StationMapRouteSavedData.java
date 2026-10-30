package com.monpai.sailboatmod.map.route;

import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.RouteNbtUtil;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Predicate;

/** Published station map geometry. Chunk unloading deliberately does not remove these snapshots. */
public final class StationMapRouteSavedData extends SavedData {
    public static final String DATA_NAME = "sailboatmod_station_map_routes";
    private final Map<String, StationSnapshot> stations = new LinkedHashMap<>();

    public static StationMapRouteSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return new StationMapRouteSavedData();
        return serverLevel.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(StationMapRouteSavedData::new, (tag, provider) -> load(tag)), DATA_NAME);
    }

    public void put(StationSnapshot snapshot) {
        String key = key(snapshot.dimension(), snapshot.pos());
        if (!snapshot.equals(stations.get(key))) { stations.put(key, snapshot); setDirty(); }
    }

    /** Only the real block-removal lifecycle invokes this; never block entity setRemoved. */
    public void remove(String dimension, BlockPos pos) {
        StationSnapshot removed = stations.get(key(dimension, pos));
        if (removed == null) return;
        for (StationSnapshot source : List.copyOf(stations.values())) {
            if (!dimension.equals(source.dimension()) || source.pos().equals(pos)) continue;
            List<RouteDefinition> retained = source.generatedRoutes().stream()
                    .filter(route -> !matchesTarget(route, removed)).toList();
            if (retained.size() != source.generatedRoutes().size()) put(new StationSnapshot(source.dimension(), source.pos(), source.name(),
                    RoadAutoRouteService.mergeRoutes(source.registeredRoutes(), retained), retained, source.registeredRoutes()));
        }
        stations.remove(key(dimension, pos));
        setDirty();
    }

    public List<StationSnapshot> stations(String dimension) {
        return stations.values().stream().filter(station -> station.dimension().equals(dimension)).toList();
    }

    public StationSnapshot station(String dimension, BlockPos position) { return stations.get(key(dimension, position)); }

    /** Normal terminal catalog refresh, without inspecting an unloaded destination or generating extra routes. */
    public StationSnapshot refreshGeneratedCatalog(String dimension, BlockPos source, String name, List<RouteDefinition> registered,
                                                    List<RouteDefinition> fresh, Predicate<BlockPos> targetLoaded) {
        StationSnapshot previous = station(dimension, source);
        List<RouteDefinition> retained = new ArrayList<>();
        if (previous != null) for (RouteDefinition route : previous.generatedRoutes()) {
            StationSnapshot target = uniqueTarget(dimension, source, route);
            if (target != null && !targetLoaded.test(target.pos())) retained.add(route);
        }
        List<RouteDefinition> generated = RoadAutoRouteService.mergeRoutes(fresh, retained);
        StationSnapshot updated = new StationSnapshot(dimension, source, name,
                RoadAutoRouteService.mergeRoutes(registered, generated), generated, registered);
        put(updated);
        return updated;
    }

    /** A still-loaded source may hold an older field value after another station was destroyed. */
    public List<RouteDefinition> retainPublishedGenerated(String dimension, BlockPos source, List<RouteDefinition> cached) {
        StationSnapshot published = station(dimension, source);
        return published == null ? List.of() : cached.stream().filter(published.generatedRoutes()::contains).toList();
    }

    private StationSnapshot uniqueTarget(String dimension, BlockPos source, RouteDefinition route) {
        StationSnapshot result = null;
        for (StationSnapshot target : stations.values()) {
            if (!dimension.equals(target.dimension()) || source.equals(target.pos()) || !matchesTarget(route, target)) continue;
            if (result != null) return null; // Duplicate nearby names cannot establish a safe persistent destination identity.
            result = target;
        }
        return result;
    }

    private static boolean matchesTarget(RouteDefinition route, StationSnapshot target) {
        if (route.endDockName().isBlank() || !route.endDockName().equals(target.name()) || route.waypoints().isEmpty()) return false;
        var end = route.waypoints().getLast();
        // Generated paths may end at a road attachment or terminal-zone edge rather than the station block.
        // The terminal editor limits either zone axis to fifty blocks; matching must also have a unique name/zone.
        return Math.abs(end.x - (target.pos().getX() + .5)) <= 50.5
                && Math.abs(end.z - (target.pos().getZ() + .5)) <= 50.5;
    }

    public static StationMapRouteSavedData load(CompoundTag tag) {
        var data = new StationMapRouteSavedData();
        for (Tag raw : tag.getList("Stations", Tag.TAG_COMPOUND)) {
            if (raw instanceof CompoundTag station) {
                var routes = RouteNbtUtil.readRoutes(station, "Routes");
                var generated = RouteNbtUtil.readRoutes(station, "GeneratedRoutes");
                var registered = station.contains("RegisteredRoutes", Tag.TAG_LIST)
                        ? RouteNbtUtil.readRoutes(station, "RegisteredRoutes") : legacyRegistered(routes, generated);
                var snapshot = new StationSnapshot(station.getString("Dimension"), BlockPos.of(station.getLong("Pos")),
                        station.getString("Name"), routes, generated, registered);
                data.stations.put(key(snapshot.dimension(), snapshot.pos()), snapshot);
            }
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) { return save(tag); }

    public CompoundTag save(CompoundTag tag) {
        var list = new ListTag();
        for (StationSnapshot snapshot : stations.values()) {
            var station = new CompoundTag();
            station.putString("Dimension", snapshot.dimension());
            station.putLong("Pos", snapshot.pos().asLong());
            station.putString("Name", snapshot.name());
            RouteNbtUtil.writeRoutes(station, "Routes", snapshot.routes());
            RouteNbtUtil.writeRoutes(station, "GeneratedRoutes", snapshot.generatedRoutes());
            RouteNbtUtil.writeRoutes(station, "RegisteredRoutes", snapshot.registeredRoutes());
            list.add(station);
        }
        tag.put("Stations", list);
        return tag;
    }

    private static String key(String dimension, BlockPos pos) { return dimension + "|" + pos.asLong(); }

    /** Old snapshots did not distinguish an exact registered/generated overlap; normal terminal refresh restores it. */
    private static List<RouteDefinition> legacyRegistered(List<RouteDefinition> routes, List<RouteDefinition> generated) {
        if (routes == null) return List.of();
        return generated == null ? List.copyOf(routes) : routes.stream().filter(route -> !generated.contains(route)).toList();
    }

    /** routes is the published merged catalog; generatedRoutes separately restores its normal refresh cache. */
    public record StationSnapshot(String dimension, BlockPos pos, String name, List<RouteDefinition> routes,
                                  List<RouteDefinition> generatedRoutes, List<RouteDefinition> registeredRoutes) {
        public StationSnapshot(String dimension, BlockPos pos, String name, List<RouteDefinition> routes) {
            this(dimension, pos, name, routes, List.of(), routes);
        }
        public StationSnapshot(String dimension, BlockPos pos, String name, List<RouteDefinition> routes, List<RouteDefinition> generatedRoutes) {
            this(dimension, pos, name, routes, generatedRoutes, legacyRegistered(routes, generatedRoutes));
        }
        public StationSnapshot {
            dimension = dimension == null ? "" : dimension;
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            name = name == null ? "" : name;
            routes = routes == null ? List.of() : routes.stream().map(RouteDefinition::copy).toList();
            generatedRoutes = generatedRoutes == null ? List.of() : generatedRoutes.stream().map(RouteDefinition::copy).toList();
            registeredRoutes = registeredRoutes == null ? List.of() : registeredRoutes.stream().map(RouteDefinition::copy).toList();
        }
    }
}
