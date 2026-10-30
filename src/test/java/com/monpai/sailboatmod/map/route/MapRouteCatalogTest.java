package com.monpai.sailboatmod.map.route;

import com.monpai.sailboatmod.integration.bluemap.BlueMapMarkerSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSync;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MapRouteCatalogTest {
    @Test void onlyCurrentDimensionPublishedCatalogsAndBuiltRoadsAreCapturedWithoutGraphLegacyDuplicates() {
        RoadNetworkRecord road = new RoadNetworkRecord("road-one", "nation", "town", "minecraft:overworld", "a", "b",
                List.of(new BlockPos(-20, 64, 5), new BlockPos(20, 64, 5)), 1, "MANUAL");
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        RoadGraphSync.syncRoad(graph, road);
        RouteDefinition published = new RouteDefinition("Harbour link", List.of(new Vec3(-10, 64, 3), new Vec3(30, 64, 3)), "", "", 1, 40, "A", "B");
        var here = new BlueMapMarkerSavedData.DockSnapshot("minecraft:overworld", BlockPos.ZERO, "A", "", "", List.of(published));
        var elsewhere = new BlueMapMarkerSavedData.DockSnapshot("minecraft:the_nether", BlockPos.ZERO, "Hidden", "", "", List.of(published));
        var station = new StationMapRouteSavedData.StationSnapshot("minecraft:overworld", BlockPos.ZERO, "Station", List.of(published));
        List<MapRouteEntry> captured = MapRouteCatalog.capture("minecraft:overworld", graph.edges(), List.of(road), List.of(here, elsewhere), List.of(station));
        assertEquals(3, captured.size());
        assertEquals(1, captured.stream().filter(entry -> entry.kind() == MapRouteKind.ROAD).count());
        assertEquals(1, captured.stream().filter(entry -> entry.kind() == MapRouteKind.LAND).count());
        assertEquals(1, captured.stream().filter(entry -> entry.kind() == MapRouteKind.SEA).count());
        assertEquals(40, captured.stream().filter(entry -> entry.kind() == MapRouteKind.SEA).findFirst().orElseThrow().lengthBlocks());
    }

    @Test void stationCatalogSurvivesSaveReloadAndUnloadingUntilExplicitDestruction() {
        var data = new StationMapRouteSavedData();
        var route = new RouteDefinition("registered", List.of(new Vec3(1, 64, 2), new Vec3(20, 64, 2)));
        data.put(new StationMapRouteSavedData.StationSnapshot("minecraft:overworld", BlockPos.ZERO, "A", List.of(route)));
        var restored = StationMapRouteSavedData.load(data.save(new CompoundTag()));
        assertEquals(1, restored.stations("minecraft:overworld").size(), "unloading has no removal side effect");
        assertEquals(route, restored.stations("minecraft:overworld").getFirst().routes().getFirst());
        restored.remove("minecraft:overworld", BlockPos.ZERO);
        assertTrue(restored.stations("minecraft:overworld").isEmpty());
    }

    @Test void aCancelledGraphRoadDoesNotReappearFromItsLegacyRecord() {
        var road = new RoadNetworkRecord("cancelled", "", "", "minecraft:overworld", "a", "b",
                List.of(BlockPos.ZERO, new BlockPos(10, 64, 0)), 1, "MANUAL");
        var graph = new RoadNetworkGraphSavedData();
        RoadGraphSync.syncRoad(graph, road);
        var tag = graph.edges().iterator().next().save();
        tag.putString("Status", "CANCELLED");
        graph.putEdge(com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord.load(tag));
        assertTrue(MapRouteCatalog.capture("minecraft:overworld", graph.edges(), List.of(road), List.of(), List.of()).isEmpty());
    }

    @Test void replyReportsOverflowAndNeverExceedsGeometryBudget() {
        List<MapRouteEntry> routes = new ArrayList<>();
        for (int i = 0; i < 140; i++) routes.add(new MapRouteEntry("r" + i, MapRouteKind.ROAD, "R", "A", "B", 20,
                List.of(List.of(new MapRoutePoint(-10, 5), new MapRoutePoint(20, 5)))));
        MapRouteSnapshot snapshot = MapRouteCatalog.viewport(routes, new MapRouteViewport(0, 0, 10, 10, 1));
        assertEquals(128, snapshot.routes().size());
        assertTrue(snapshot.truncated());
        assertTrue(snapshot.routes().stream().flatMap(entry -> entry.paths().stream()).mapToInt(List::size).sum() <= 4096);
        assertEquals(new MapRoutePoint(0, 5), snapshot.routes().getFirst().paths().getFirst().getFirst());
    }

    @Test void crowdedRoadViewsRetainRepresentativeLandAndSeaRoutesWithinTheSameReplyBudget() {
        List<MapRouteEntry> routes = new ArrayList<>();
        List<List<MapRoutePoint>> shape = List.of(List.of(new MapRoutePoint(-10, 5), new MapRoutePoint(20, 5)));
        for (int i = 0; i < 140; i++) routes.add(new MapRouteEntry("road" + i, MapRouteKind.ROAD, "Road", "A", "B", 30, shape));
        routes.add(new MapRouteEntry("station-link", MapRouteKind.LAND, "Station link", "A", "B", 30, shape));
        routes.add(new MapRouteEntry("port-link", MapRouteKind.SEA, "Port link", "A", "B", 30, shape));
        var snapshot = MapRouteCatalog.viewport(routes, new MapRouteViewport(0, 0, 10, 10, 1));
        assertEquals(128, snapshot.routes().size());
        assertTrue(snapshot.truncated());
        assertEquals(java.util.Set.of(MapRouteKind.ROAD, MapRouteKind.LAND, MapRouteKind.SEA),
                snapshot.routes().stream().map(MapRouteEntry::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(snapshot.routes().stream().flatMap(entry -> entry.paths().stream()).mapToInt(List::size).sum() <= 4096);
    }

    @Test void oneComplexRoadCannotConsumeTheOtherPublishedLayersMinimumGeometry() {
        List<MapRoutePoint> zigzag = new ArrayList<>();
        for (int i = 0; i < 16; i++) zigzag.add(new MapRoutePoint(i % 2 == 0 ? 1 : 9, 1 + i * .5));
        var complex = new MapRouteEntry("complex", MapRouteKind.ROAD, "Road", "A", "B", 1000,
                java.util.Collections.nCopies(256, List.copyOf(zigzag)));
        var simple = List.of(List.of(new MapRoutePoint(0, 5), new MapRoutePoint(10, 5)));
        var land = new MapRouteEntry("land", MapRouteKind.LAND, "Land", "A", "B", 10, simple);
        var sea = new MapRouteEntry("sea", MapRouteKind.SEA, "Sea", "A", "B", 10, simple);
        var snapshot = MapRouteCatalog.viewport(List.of(complex, land, sea), new MapRouteViewport(0, 0, 10, 10, 1e6));
        assertEquals(java.util.Set.of(MapRouteKind.ROAD, MapRouteKind.LAND, MapRouteKind.SEA),
                snapshot.routes().stream().map(MapRouteEntry::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(snapshot.truncated());
        assertTrue(snapshot.routes().stream().mapToInt(entry -> entry.paths().size()).sum() <= 256);
        assertTrue(snapshot.routes().stream().flatMap(entry -> entry.paths().stream()).mapToInt(List::size).sum() <= 4096);
    }

    @Test void generatedStationCatalogPersistsSeparatelyFromEditableRegisteredRoutes() {
        var generated = new RouteDefinition("network link", List.of(new Vec3(2, 64, 3), new Vec3(8, 64, 3)));
        var data = new StationMapRouteSavedData();
        data.put(new StationMapRouteSavedData.StationSnapshot("minecraft:overworld", BlockPos.ZERO, "A", List.of(generated), List.of(generated)));
        var restored = StationMapRouteSavedData.load(data.save(new CompoundTag()));
        assertEquals(List.of(generated), restored.station("minecraft:overworld", BlockPos.ZERO).generatedRoutes());
        assertEquals(List.of(generated), restored.station("minecraft:overworld", BlockPos.ZERO).routes());
    }

    @Test void routeIdentitySurvivesTerminalCatalogReordering() {
        var a = new RouteDefinition("alpha", List.of(new Vec3(1, 64, 1), new Vec3(2, 64, 2)));
        var b = new RouteDefinition("beta", List.of(new Vec3(2, 64, 2), new Vec3(4, 64, 4)));
        var before = new StationMapRouteSavedData.StationSnapshot("minecraft:overworld", BlockPos.ZERO, "Station", List.of(a, b));
        var after = new StationMapRouteSavedData.StationSnapshot("minecraft:overworld", BlockPos.ZERO, "Station", List.of(b, a));
        var first = MapRouteCatalog.capture("minecraft:overworld", List.of(), List.of(), List.of(), List.of(before));
        var second = MapRouteCatalog.capture("minecraft:overworld", List.of(), List.of(), List.of(), List.of(after));
        assertEquals(first.getFirst().id(), second.getLast().id());
        assertEquals(first.getLast().id(), second.getFirst().id());
    }

    @Test void aVeryLongRoadKeepsItsCompressedDisplayAndUsesBoundedApproximateLength() {
        List<BlockPos> excessive = java.util.stream.IntStream.rangeClosed(0, 40000).mapToObj(x -> new BlockPos(x, 64, 0)).toList();
        var road = new RoadNetworkRecord("oversized", "", "", "minecraft:overworld", "a", "b", excessive,
                List.of(new BlockPos(0, 64, 0), new BlockPos(40000, 64, 0)), List.of(), 1, 1, "", "", "MANUAL", "", "");
        var captured = MapRouteCatalog.captureBounded("minecraft:overworld", List.of(), List.of(road), List.of(), List.of());
        assertFalse(captured.truncated());
        assertEquals(1, captured.routes().size());
        assertEquals(2, captured.routes().getFirst().paths().getFirst().size());
        assertEquals(40000, captured.routes().getFirst().lengthBlocks(), 0.01);
    }

    @Test void aNewSessionSupersedesOldWorkAndDisconnectCancelsPublication() {
        var tracker = new MapRouteRequestTracker();
        UUID owner = UUID.randomUUID(), firstSession = UUID.randomUUID(), secondSession = UUID.randomUUID();
        var first = tracker.accept(owner, firstSession, 10, "world", "minecraft:overworld", 0);
        assertNotNull(first);
        assertNull(tracker.accept(owner, firstSession, 9, "world", "minecraft:overworld", 20));
        var second = tracker.accept(owner, secondSession, 1, "world", "minecraft:overworld", 20);
        assertNotNull(second);
        assertFalse(tracker.current(owner, first, "minecraft:overworld"));
        assertTrue(tracker.current(owner, second, "minecraft:overworld"));
        assertFalse(tracker.current(owner, second, "minecraft:the_nether"));
        tracker.forget(owner);
        assertFalse(tracker.current(owner, second, "minecraft:overworld"));
    }

    @Test void mapViewServiceCannotReachPrivateInventoryMissionsOrTriggerChunkAndRouteWork() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/map/route/MapRouteCatalogService.java"));
        for (String prohibited : List.of(".getInventory(", ".getBlockEntity(", ".getChunk(", "RoadAutoRouteService", "LandTransportNetworkService", "canManageRoad", "ShippingOrder"))
            assertFalse(source.contains(prohibited), "Map views must stay on registered public catalogs: " + prohibited);
    }
}
