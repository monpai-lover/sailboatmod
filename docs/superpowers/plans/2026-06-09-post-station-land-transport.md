# Post Station Land Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild post station and market land transport around town reachability so A-C and A-B-C road networks expose reachable town destinations, dispatch one complete carriage route, and prevent unreachable cross-town market listings.

**Architecture:** Add `LandTransportNetworkService` as the server-side source of truth for land reachability and route plans. Post stations consume it for destinations, dispatch, recall, and route preview; markets consume the same service for visibility, purchase rechecks, and POST_STATION dispatch. Existing `DockScreen` remains the port UI, while post stations get a land-specific screen data record, packets, and screen.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1, Forge networking `SimpleChannel`, JUnit 5, existing road graph services (`RoadGraphRoutingService`, `RoadAutoRouteService`), existing route model (`RouteDefinition`).

---

## File Map

- Create `src/main/java/com/monpai/sailboatmod/route/LandTransportNetworkService.java`: town reachability, station pair selection, route plan generation, route failure reasons, ETA and distance calculations.
- Create `src/test/java/com/monpai/sailboatmod/route/LandTransportNetworkServiceTest.java`: pure unit tests for direct, indirect, unreachable, and shortest station pair behavior.
- Create `src/main/java/com/monpai/sailboatmod/dock/PostStationScreenData.java`: land-specific screen payload with reachable towns, local vehicles, selected route summary, and advanced legacy dock data.
- Create `src/main/java/com/monpai/sailboatmod/client/PostStationClientHooks.java`: client-side payload handoff for `PostStationScreen`.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/OpenPostStationScreenPacket.java`: server-to-client post station data sync.
- Create `src/main/java/com/monpai/sailboatmod/network/packet/PostStationGuiActionPacket.java`: client-to-server post station actions.
- Modify `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`: register the new packets and bump `PROTOCOL_VERSION` because market overview packet shape changes later.
- Modify `src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java`: build land screen data, store selected town/vehicle/auto-return UI state, dispatch selected destination, recall parked carriage, and keep legacy route book behavior under advanced actions.
- Modify `src/main/java/com/monpai/sailboatmod/menu/DockMenu.java`: send the correct refresh packet for post station menus when the shared route book/storage slots change.
- Modify `src/main/java/com/monpai/sailboatmod/client/screen/PostStationScreen.java`: replace the `DockScreen` subclass with an independent land dispatch screen.
- Modify `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`: persist land task state, honor auto-return, park at destination, and support recall routes without teleporting.
- Modify `src/main/java/com/monpai/sailboatmod/entity/TransportEntity.java`: add default no-op land task methods only where call sites need polymorphism.
- Modify `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`: add stable `listingId` and `orderId` fields to client entries.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacket.java`: serialize and deserialize new market entry ids.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java`: send `listingId` instead of a visible index.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/CancelMarketListingPacket.java`: send `listingId` instead of a visible index.
- Modify `src/main/java/com/monpai/sailboatmod/network/packet/DispatchMarketOrderPacket.java`: send `orderId` instead of a visible index.
- Modify `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`: use stable ids from entries when buying, cancelling, and dispatching.
- Modify `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`: filter listings by reachable transport modes and use `LandTransportNetworkService` for POST_STATION previews and dispatch.
- Create or extend tests:
  - `src/test/java/com/monpai/sailboatmod/network/packet/PostStationScreenPacketTest.java`
  - `src/test/java/com/monpai/sailboatmod/entity/CarriageTaskStateTest.java`
  - `src/test/java/com/monpai/sailboatmod/market/MarketReachabilityTest.java`

## Task 1: Land Transport Reachability Service

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/route/LandTransportNetworkService.java`
- Test: `src/test/java/com/monpai/sailboatmod/route/LandTransportNetworkServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Add these tests before implementation:

```java
package com.monpai.sailboatmod.route;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandTransportNetworkServiceTest {
    @Test
    void directRoadMakesTargetTownReachable() {
        TestWorld world = new TestWorld()
                .station("a1", "town-a", "Alpha", new BlockPos(0, 64, 0))
                .station("c1", "town-c", "Cedar", new BlockPos(100, 64, 0))
                .road("a1", "c1", 100);

        LandTransportNetworkService service = world.service();

        List<LandTransportNetworkService.ReachableTown> towns =
                service.reachableTowns(world.level(), world.station("a1"), true);

        assertEquals(List.of("town-c"), towns.stream().map(LandTransportNetworkService.ReachableTown::townId).toList());
        assertEquals("Cedar", towns.get(0).townName());
    }

    @Test
    void chainRoadExposesIntermediateAndFinalTown() {
        TestWorld world = new TestWorld()
                .station("a1", "town-a", "Alpha", new BlockPos(0, 64, 0))
                .station("b1", "town-b", "Bridge", new BlockPos(50, 64, 0))
                .station("c1", "town-c", "Cedar", new BlockPos(100, 64, 0))
                .road("a1", "b1", 50)
                .road("b1", "c1", 50);

        List<LandTransportNetworkService.ReachableTown> towns =
                world.service().reachableTowns(world.level(), world.station("a1"), true);

        assertEquals(List.of("town-b", "town-c"), towns.stream().map(LandTransportNetworkService.ReachableTown::townId).toList());
    }

    @Test
    void selectingFinalTownCreatesCompleteRoute() {
        TestWorld world = new TestWorld()
                .station("a1", "town-a", "Alpha", new BlockPos(0, 64, 0))
                .station("b1", "town-b", "Bridge", new BlockPos(50, 64, 0))
                .station("c1", "town-c", "Cedar", new BlockPos(100, 64, 0))
                .road("a1", "b1", 50)
                .road("b1", "c1", 50);

        LandTransportNetworkService.RouteAvailability availability =
                world.service().planRouteToTown(world.level(), world.station("a1"), "town-c", true);

        assertTrue(availability.reachable());
        LandTransportNetworkService.LandRoutePlan plan = availability.plan();
        assertEquals("town-c", plan.targetTownId());
        assertEquals(List.of("Bridge"), plan.passThroughTownNames());
        assertEquals(new BlockPos(100, 64, 0), plan.targetStationPos());
        assertEquals(3, plan.route().waypoints().size());
    }

    @Test
    void unreachableTownIsExcluded() {
        TestWorld world = new TestWorld()
                .station("a1", "town-a", "Alpha", new BlockPos(0, 64, 0))
                .station("c1", "town-c", "Cedar", new BlockPos(100, 64, 0));

        assertTrue(world.service().reachableTowns(world.level(), world.station("a1"), false).isEmpty());
        assertFalse(world.service().planRouteToTown(world.level(), world.station("a1"), "town-c", false).reachable());
    }

    @Test
    void multipleTargetStationsChooseShortestPlan() {
        TestWorld world = new TestWorld()
                .station("a1", "town-a", "Alpha", new BlockPos(0, 64, 0))
                .station("c-far", "town-c", "Cedar", new BlockPos(200, 64, 0))
                .station("c-near", "town-c", "Cedar", new BlockPos(60, 64, 0))
                .road("a1", "c-far", 200)
                .road("a1", "c-near", 60);

        LandTransportNetworkService.RouteAvailability availability =
                world.service().planRouteToTown(world.level(), world.station("a1"), "town-c", true);

        assertTrue(availability.reachable());
        assertEquals(new BlockPos(60, 64, 0), availability.plan().targetStationPos());
    }

    private static final class TestWorld {
        private final LandTransportNetworkService.TestLevelRef level = new LandTransportNetworkService.TestLevelRef("minecraft:overworld");
        private final java.util.LinkedHashMap<String, LandTransportNetworkService.StationRef> stations = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, Integer>> roads = new java.util.LinkedHashMap<>();

        TestWorld station(String id, String townId, String townName, BlockPos pos) {
            stations.put(id, new LandTransportNetworkService.StationRef(id, townId, townName, pos, id));
            return this;
        }

        TestWorld road(String left, String right, int meters) {
            roads.computeIfAbsent(left, ignored -> new java.util.LinkedHashMap<>()).put(right, meters);
            roads.computeIfAbsent(right, ignored -> new java.util.LinkedHashMap<>()).put(left, meters);
            return this;
        }

        LandTransportNetworkService.StationRef station(String id) {
            return stations.get(id);
        }

        LandTransportNetworkService service() {
            return LandTransportNetworkService.forTests(
                    () -> List.copyOf(stations.values()),
                    (source, target, allowTerrainFallback) -> {
                        List<String> ids = shortestStationPath(source.stationId(), target.stationId());
                        if (ids.isEmpty()) {
                            return LandTransportNetworkService.RouteAvailability.unavailable(
                                    LandTransportNetworkService.RouteFailureReason.NO_ROAD_ROUTE);
                        }
                        List<Vec3> waypoints = ids.stream().map(stations::get)
                                .map(ref -> Vec3.atCenterOf(ref.pos())).toList();
                        return LandTransportNetworkService.RouteAvailability.reachable(
                                LandTransportNetworkService.LandRoutePlan.fromWaypoints(
                                        source, target, ids.stream().map(stations::get).toList(), waypoints,
                                        "System", LandTransportNetworkService.RouteSource.ROAD_GRAPH));
                    });
        }

        private List<String> shortestStationPath(String start, String end) {
            java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(java.util.Comparator.comparingInt(Node::distance));
            Map<String, Integer> dist = new java.util.HashMap<>();
            Map<String, String> prev = new java.util.HashMap<>();
            open.add(new Node(start, 0));
            dist.put(start, 0);
            while (!open.isEmpty()) {
                Node current = open.poll();
                if (current.stationId().equals(end)) break;
                for (Map.Entry<String, Integer> edge : roads.getOrDefault(current.stationId(), new java.util.LinkedHashMap<>()).entrySet()) {
                    int candidate = current.distance() + edge.getValue();
                    if (candidate < dist.getOrDefault(edge.getKey(), Integer.MAX_VALUE)) {
                        dist.put(edge.getKey(), candidate);
                        prev.put(edge.getKey(), current.stationId());
                        open.add(new Node(edge.getKey(), candidate));
                    }
                }
            }
            if (!dist.containsKey(end)) return List.of();
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            String cursor = end;
            out.add(cursor);
            while (!cursor.equals(start)) {
                cursor = prev.get(cursor);
                if (cursor == null) return List.of();
                out.add(cursor);
            }
            java.util.Collections.reverse(out);
            return out;
        }

        private record Node(String stationId, int distance) {
        }
    }
}
```

- [ ] **Step 2: Run the failing tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.LandTransportNetworkServiceTest"
```

Expected: compile fails because `LandTransportNetworkService` does not exist.

- [ ] **Step 3: Implement DTOs and pure test seam**

Create `LandTransportNetworkService.java` with this public surface:

```java
package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public final class LandTransportNetworkService {
    public static final double POST_STATION_SPEED_MPS = 5.0D;
    private final StationSource stationSource;
    private final RouteResolver routeResolver;

    public LandTransportNetworkService() {
        this(LandTransportNetworkService::collectServerStations, LandTransportNetworkService::resolveServerRoute);
    }

    private LandTransportNetworkService(StationSource stationSource, RouteResolver routeResolver) {
        this.stationSource = stationSource;
        this.routeResolver = routeResolver;
    }

    public static LandTransportNetworkService forTests(Supplier<List<StationRef>> stations, RouteResolver routeResolver) {
        return new LandTransportNetworkService(level -> stations.get(), routeResolver);
    }

    public List<ReachableTown> reachableTowns(Object level, StationRef source, boolean allowTerrainFallback) {
        if (source == null || source.townId().isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, ReachableTown> bestByTown = new LinkedHashMap<>();
        for (StationRef target : stationSource.stations(level)) {
            if (target == null || target.townId().isBlank() || target.townId().equals(source.townId())) {
                continue;
            }
            RouteAvailability availability = routeResolver.resolve(source, target, allowTerrainFallback);
            if (!availability.reachable()) {
                continue;
            }
            LandRoutePlan plan = availability.plan();
            ReachableTown candidate = new ReachableTown(
                    target.townId(),
                    target.townName().isBlank() ? target.townId() : target.townName(),
                    target.pos(),
                    target.stationName(),
                    plan.distanceMeters(),
                    estimateEtaSeconds(plan.distanceMeters()),
                    plan.source(),
                    plan.passThroughTownNames(),
                    plan.route().name()
            );
            ReachableTown existing = bestByTown.get(candidate.townId());
            if (existing == null || candidate.distanceMeters() < existing.distanceMeters()) {
                bestByTown.put(candidate.townId(), candidate);
            }
        }
        return bestByTown.values().stream()
                .sorted(Comparator.comparingInt(ReachableTown::distanceMeters).thenComparing(ReachableTown::townName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public RouteAvailability planRouteToTown(Object level, StationRef source, String targetTownId, boolean allowTerrainFallback) {
        String normalizedTargetTown = normalize(targetTownId);
        if (source == null || normalizedTargetTown.isBlank()) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_TOWN);
        }
        LandRoutePlan best = null;
        for (StationRef target : stationSource.stations(level)) {
            if (!normalizedTargetTown.equals(target.townId())) {
                continue;
            }
            RouteAvailability availability = routeResolver.resolve(source, target, allowTerrainFallback);
            if (!availability.reachable()) {
                continue;
            }
            if (best == null || availability.plan().distanceMeters() < best.distanceMeters()) {
                best = availability.plan();
            }
        }
        return best == null ? RouteAvailability.unavailable(RouteFailureReason.NO_ROAD_ROUTE) : RouteAvailability.reachable(best);
    }

    public RouteAvailability planRouteBetweenStations(Object level, StationRef source, StationRef target, boolean allowTerrainFallback) {
        if (source == null || target == null) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_STATION);
        }
        return routeResolver.resolve(source, target, allowTerrainFallback);
    }

    public StationRef stationRef(Level level, PostStationBlockEntity station) {
        if (level == null || station == null) {
            return null;
        }
        String townId = DockTownResolver.resolveTownForArrival(level, station.getBlockPos(), station.getTownId());
        TownRecord town = townId.isBlank() ? null : NationSavedData.get(level).getTown(townId);
        String townName = town == null ? townId : town.name();
        return new StationRef(DockTownResolver.dockId(level, station.getBlockPos()), normalize(townId), townName,
                station.getBlockPos().immutable(), station.getDockName());
    }

    public static RouteDefinition routeDefinition(String name, StationRef source, StationRef target, List<Vec3> waypoints, String author, RouteSource sourceKind) {
        double length = routeLength(waypoints);
        return new RouteDefinition(
                name,
                waypoints,
                author == null ? "System" : author,
                "",
                System.currentTimeMillis(),
                length,
                source == null ? "" : source.stationName(),
                target == null ? "" : target.stationName()
        );
    }

    public static int estimateEtaSeconds(double distanceMeters) {
        return distanceMeters <= 0.0D ? 0 : Math.max(1, (int) Math.ceil(distanceMeters / POST_STATION_SPEED_MPS));
    }

    private static List<StationRef> collectServerStations(Object rawLevel) {
        if (!(rawLevel instanceof ServerLevel level)) {
            return List.of();
        }
        LandTransportNetworkService service = new LandTransportNetworkService();
        List<StationRef> refs = new ArrayList<>();
        for (BlockPos pos : PostStationRegistry.get(level)) {
            if (level.getBlockEntity(pos) instanceof PostStationBlockEntity station) {
                StationRef ref = service.stationRef(level, station);
                if (ref != null && !ref.townId().isBlank()) {
                    refs.add(ref);
                }
            }
        }
        return refs;
    }

    private static RouteAvailability resolveServerRoute(StationRef source, StationRef target, boolean allowTerrainFallback) {
        if (!(source.level() instanceof ServerLevel level)) {
            return RouteAvailability.unavailable(RouteFailureReason.MISSING_LEVEL);
        }
        RoadAutoRouteService.RouteResolution resolution = RoadAutoRouteService.resolveAutoRoutePreview(level, source.pos(), target.pos());
        if (!resolution.found()) {
            return RouteAvailability.unavailable(RouteFailureReason.NO_ROAD_ROUTE);
        }
        List<Vec3> waypoints = resolution.path().stream()
                .map(pos -> new Vec3(pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D))
                .toList();
        RouteSource routeSource = resolution.source() == RoadAutoRouteService.PathSource.ROAD_NETWORK
                ? RouteSource.ROAD_GRAPH
                : RouteSource.LAND_TERRAIN;
        return RouteAvailability.reachable(LandRoutePlan.fromWaypoints(source, target, List.of(source, target), waypoints, "System", routeSource));
    }

    private static double routeLength(List<Vec3> waypoints) {
        double total = 0.0D;
        for (int i = 1; i < waypoints.size(); i++) {
            total += waypoints.get(i - 1).distanceTo(waypoints.get(i));
        }
        return total;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public interface StationSource {
        List<StationRef> stations(Object level);
    }

    public interface RouteResolver {
        RouteAvailability resolve(StationRef source, StationRef target, boolean allowTerrainFallback);
    }

    public record TestLevelRef(String dimensionId) {
    }

    public record StationRef(String stationId, String townId, String townName, BlockPos pos, String stationName, @Nullable Object level) {
        public StationRef(String stationId, String townId, String townName, BlockPos pos, String stationName) {
            this(stationId, normalize(townId), townName == null ? "" : townName, pos.immutable(), stationName == null ? "" : stationName, null);
        }
    }

    public enum RouteSource {
        ROAD_GRAPH,
        LAND_TERRAIN,
        STORED_ROUTE
    }

    public enum RouteFailureReason {
        NONE,
        MISSING_LEVEL,
        MISSING_TOWN,
        MISSING_STATION,
        NO_POST_STATION,
        DIPLOMACY_BLOCKED,
        NO_ROAD_ROUTE,
        NO_AVAILABLE_CARRIAGE,
        VEHICLE_BUSY
    }

    public record RouteAvailability(boolean reachable, @Nullable LandRoutePlan plan, RouteFailureReason failureReason) {
        public static RouteAvailability reachable(LandRoutePlan plan) {
            return new RouteAvailability(true, plan, RouteFailureReason.NONE);
        }

        public static RouteAvailability unavailable(RouteFailureReason reason) {
            return new RouteAvailability(false, null, reason == null ? RouteFailureReason.NO_ROAD_ROUTE : reason);
        }
    }

    public record ReachableTown(String townId, String townName, BlockPos targetStationPos, String targetStationName,
                                int distanceMeters, int etaSeconds, RouteSource source,
                                List<String> passThroughTownNames, String routeName) {
        public ReachableTown {
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
        }
    }

    public record LandRoutePlan(StationRef sourceStation, StationRef targetStation, String sourceTownId, String targetTownId,
                                BlockPos sourceStationPos, BlockPos targetStationPos, RouteDefinition route,
                                int distanceMeters, int etaSeconds, RouteSource source,
                                List<String> passThroughTownNames) {
        public LandRoutePlan {
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
        }

        public static LandRoutePlan fromWaypoints(StationRef sourceStation, StationRef targetStation, List<StationRef> stationPath,
                                                  List<Vec3> waypoints, String author, RouteSource sourceKind) {
            List<String> passThrough = stationPath == null ? List.of() : stationPath.stream()
                    .filter(ref -> ref != null
                            && !ref.townId().equals(sourceStation.townId())
                            && !ref.townId().equals(targetStation.townId()))
                    .map(StationRef::townName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
            RouteDefinition route = routeDefinition(
                    (sourceStation.townName().isBlank() ? sourceStation.townId() : sourceStation.townName())
                            + " -> "
                            + (targetStation.townName().isBlank() ? targetStation.townId() : targetStation.townName()),
                    sourceStation,
                    targetStation,
                    waypoints,
                    author,
                    sourceKind
            );
            int distance = (int) Math.round(route.routeLengthMeters());
            return new LandRoutePlan(sourceStation, targetStation, sourceStation.townId(), targetStation.townId(),
                    sourceStation.pos(), targetStation.pos(), route, distance, estimateEtaSeconds(distance), sourceKind, passThrough);
        }
    }
}
```

- [ ] **Step 4: Fix the production route resolver**

Adjust `StationRef` and `resolveServerRoute` so server refs carry the level:

```java
return new StationRef(DockTownResolver.dockId(level, station.getBlockPos()), normalize(townId), townName,
        station.getBlockPos().immutable(), station.getDockName(), level);
```

Keep this permission check before generating a production route:

```java
if (!(level.getBlockEntity(source.pos()) instanceof PostStationBlockEntity sourceStation)
        || !(level.getBlockEntity(target.pos()) instanceof PostStationBlockEntity targetStation)
        || !RoadAutoRouteService.canCreateAutoRoute(level, sourceStation, targetStation)) {
    return RouteAvailability.unavailable(RouteFailureReason.DIPLOMACY_BLOCKED);
}
```

- [ ] **Step 5: Run service tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.LandTransportNetworkServiceTest"
```

Expected: all five tests pass.

- [ ] **Step 6: Commit service foundation**

Run:

```powershell
git add src/main/java/com/monpai/sailboatmod/route/LandTransportNetworkService.java src/test/java/com/monpai/sailboatmod/route/LandTransportNetworkServiceTest.java
git commit -m "Add land transport reachability service"
```

## Task 2: Post Station Screen Data And Packets

**Files:**
- Create: `src/main/java/com/monpai/sailboatmod/dock/PostStationScreenData.java`
- Create: `src/main/java/com/monpai/sailboatmod/client/PostStationClientHooks.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/OpenPostStationScreenPacket.java`
- Create: `src/main/java/com/monpai/sailboatmod/network/packet/PostStationGuiActionPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/ModNetwork.java`
- Test: `src/test/java/com/monpai/sailboatmod/network/packet/PostStationScreenPacketTest.java`

- [ ] **Step 1: Add screen data record and packet round-trip tests**

Write `PostStationScreenPacketTest`:

```java
package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.dock.DockScreenData;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostStationScreenPacketTest {
    @Test
    void openPostStationPacketRoundTripsLandFields() {
        PostStationScreenData data = sampleData();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        OpenPostStationScreenPacket.encode(new OpenPostStationScreenPacket(data), buffer);
        OpenPostStationScreenPacket decoded = OpenPostStationScreenPacket.decode(buffer);

        assertEquals(data.stationPos(), decoded.data().stationPos());
        assertEquals("town-c", decoded.data().reachableTowns().get(0).townId());
        assertEquals(42, decoded.data().vehicles().get(0).entityId());
        assertTrue(decoded.data().autoReturnOnDispatch());
        assertEquals(2, decoded.data().selectedRouteWaypoints().size());
    }

    private static PostStationScreenData sampleData() {
        DockScreenData advanced = new DockScreenData(
                new BlockPos(1, 64, 1), "Station", "Owner", "owner-uuid",
                true, false, false, ItemStack.EMPTY, List.of("Legacy"), List.of("Len 10m"),
                0, List.of(new Vec3(1, 65, 1), new Vec3(3, 65, 1)),
                -12, 12, -8, 8, List.of(42), List.of("Carriage"), List.of(Vec3.ZERO),
                0, List.of(), 0, List.of(), 0, List.of(), List.of());
        return new PostStationScreenData(
                new BlockPos(1, 64, 1), "Station", "town-a", "Alpha", true,
                List.of(new PostStationScreenData.ReachableTownEntry(
                        "town-c", "Cedar", new BlockPos(100, 64, 0), "Cedar Station",
                        100, 20, LandTransportNetworkService.RouteSource.ROAD_GRAPH.name(),
                        List.of("Bridge"), "Alpha -> Cedar")),
                0,
                List.of(new PostStationScreenData.VehicleEntry(42, "Carriage", Vec3.ZERO, "IDLE", true, true, "")),
                0,
                true,
                new PostStationScreenData.RouteSummary("Alpha -> Cedar", 100, 20, List.of("Bridge")),
                List.of(new Vec3(1, 65, 1), new Vec3(100, 65, 0)),
                advanced
        );
    }
}
```

- [ ] **Step 2: Run packet test and confirm failure**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.PostStationScreenPacketTest"
```

Expected: compile fails because the new data and packet classes do not exist.

- [ ] **Step 3: Create `PostStationScreenData`**

Use immutable list copies:

```java
package com.monpai.sailboatmod.dock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public record PostStationScreenData(
        BlockPos stationPos,
        String stationName,
        String townId,
        String townName,
        boolean canManage,
        List<ReachableTownEntry> reachableTowns,
        int selectedTownIndex,
        List<VehicleEntry> vehicles,
        int selectedVehicleIndex,
        boolean autoReturnOnDispatch,
        RouteSummary selectedRouteSummary,
        List<Vec3> selectedRouteWaypoints,
        DockScreenData advancedData
) {
    public PostStationScreenData {
        reachableTowns = reachableTowns == null ? List.of() : List.copyOf(reachableTowns);
        vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
        selectedRouteWaypoints = selectedRouteWaypoints == null ? List.of() : List.copyOf(selectedRouteWaypoints);
    }

    public record ReachableTownEntry(String townId, String townName, BlockPos stationPos, String stationName,
                                     int distanceMeters, int etaSeconds, String routeSource,
                                     List<String> passThroughTownNames, String routeName) {
        public ReachableTownEntry {
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
        }
    }

    public record VehicleEntry(int entityId, String name, Vec3 position, String state,
                               boolean ownedOrRentable, boolean recallable, String dockedTownName) {
    }

    public record RouteSummary(String routeName, int distanceMeters, int etaSeconds, List<String> passThroughTownNames) {
        public RouteSummary {
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
        }
    }
}
```

- [ ] **Step 4: Create client hook**

```java
package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.PostStationScreen;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class PostStationClientHooks {
    private static PostStationScreenData latest;

    public static void openOrUpdate(PostStationScreenData data) {
        latest = data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PostStationScreen screen && screen.isForStation(data.stationPos())) {
            screen.updateData(data);
        }
    }

    public static PostStationScreenData consumeFor(BlockPos pos) {
        if (latest != null && latest.stationPos().equals(pos)) {
            PostStationScreenData out = latest;
            latest = null;
            return out;
        }
        return null;
    }

    private PostStationClientHooks() {
    }
}
```

- [ ] **Step 5: Implement `OpenPostStationScreenPacket`**

Follow `OpenDockScreenPacket` field order and add local helper methods:

```java
public class OpenPostStationScreenPacket {
    private final PostStationScreenData data;

    public OpenPostStationScreenPacket(PostStationScreenData data) {
        this.data = data;
    }

    public PostStationScreenData data() {
        return data;
    }

    public static void encode(OpenPostStationScreenPacket packet, FriendlyByteBuf buffer) {
        PostStationScreenData data = packet.data;
        buffer.writeBlockPos(data.stationPos());
        PacketStringCodec.writeUtfSafe(buffer, data.stationName(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.townId(), 64);
        PacketStringCodec.writeUtfSafe(buffer, data.townName(), 64);
        buffer.writeBoolean(data.canManage());
        writeReachableTowns(buffer, data.reachableTowns());
        buffer.writeVarInt(data.selectedTownIndex());
        writeVehicles(buffer, data.vehicles());
        buffer.writeVarInt(data.selectedVehicleIndex());
        buffer.writeBoolean(data.autoReturnOnDispatch());
        writeRouteSummary(buffer, data.selectedRouteSummary());
        writeWaypoints(buffer, data.selectedRouteWaypoints());
        OpenDockScreenPacket.encodeData(data.advancedData(), buffer);
    }
}
```

Add package-private `OpenDockScreenPacket.encodeData(DockScreenData, FriendlyByteBuf)` and `OpenDockScreenPacket.decodeData(FriendlyByteBuf)` by extracting the existing body. Keep `OpenDockScreenPacket.encode/decode` as wrappers around those helpers.

- [ ] **Step 6: Implement `PostStationGuiActionPacket`**

Actions:

```java
public enum Action {
    SELECT_DESTINATION_INDEX,
    SELECT_VEHICLE_INDEX,
    TOGGLE_AUTO_RETURN,
    DISPATCH_SELECTED,
    RECALL_SELECTED,
    ADV_LOAD_BOOK_FROM_HAND,
    ADV_LOAD_BOOK_FROM_INVENTORY_SLOT,
    ADV_IMPORT_BOOK,
    ADV_CLEAR_BOOK,
    ADV_DELETE_ROUTE,
    ADV_PREV_ROUTE,
    ADV_NEXT_ROUTE,
    ADV_SELECT_ROUTE_INDEX,
    ADV_REVERSE_ROUTE,
    ADV_SELECT_STORAGE_INDEX,
    ADV_TAKE_SELECTED_STORAGE,
    ADV_SELECT_WAYBILL_INDEX,
    ADV_TAKE_SELECTED_WAYBILL,
    REFRESH
}
```

Handle actions only when the block entity is `PostStationBlockEntity`. After every action send:

```java
ModNetwork.CHANNEL.send(
        PacketDistributor.PLAYER.with(() -> player),
        new OpenPostStationScreenPacket(station.buildPostStationScreenData(player))
);
```

- [ ] **Step 7: Register packets**

Modify imports in `ModNetwork.java` and register after `DockGuiActionPacket`:

```java
CHANNEL.registerMessage(
        packetId++,
        OpenPostStationScreenPacket.class,
        OpenPostStationScreenPacket::encode,
        OpenPostStationScreenPacket::decode,
        OpenPostStationScreenPacket::handle,
        Optional.of(NetworkDirection.PLAY_TO_CLIENT)
);
CHANNEL.registerMessage(
        packetId++,
        PostStationGuiActionPacket.class,
        PostStationGuiActionPacket::encode,
        PostStationGuiActionPacket::decode,
        PostStationGuiActionPacket::handle,
        Optional.of(NetworkDirection.PLAY_TO_SERVER)
);
```

Bump:

```java
private static final String PROTOCOL_VERSION = "3";
```

- [ ] **Step 8: Run packet tests**

Run:

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.network.packet.PostStationScreenPacketTest"
```

Expected: pass.

- [ ] **Step 9: Commit packet layer**

```powershell
git add src/main/java/com/monpai/sailboatmod/dock/PostStationScreenData.java src/main/java/com/monpai/sailboatmod/client/PostStationClientHooks.java src/main/java/com/monpai/sailboatmod/network/packet/OpenPostStationScreenPacket.java src/main/java/com/monpai/sailboatmod/network/packet/PostStationGuiActionPacket.java src/main/java/com/monpai/sailboatmod/network/packet/OpenDockScreenPacket.java src/main/java/com/monpai/sailboatmod/network/ModNetwork.java src/test/java/com/monpai/sailboatmod/network/packet/PostStationScreenPacketTest.java
git commit -m "Add post station land screen packets"
```

## Task 3: Post Station Server Flow

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/menu/DockMenu.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/PostStationBlock.java`
- Test: `src/test/java/com/monpai/sailboatmod/block/entity/PostStationScreenDataTest.java`

- [ ] **Step 1: Add focused screen-data tests**

Create `PostStationScreenDataTest` with helper methods on `PostStationBlockEntity` exposed package-private for test:

```java
@Test
void selectedDestinationClampsWhenReachableTownListShrinks() {
    int selected = PostStationBlockEntity.clampSelectedIndexForTest(5, 2);
    assertEquals(1, selected);
}

@Test
void autoReturnDefaultsOnForNewDispatchUi() {
    assertTrue(PostStationBlockEntity.defaultAutoReturnOnDispatchForTest());
}
```

- [ ] **Step 2: Run test and confirm failure**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.block.entity.PostStationScreenDataTest"
```

Expected: compile fails because test helper methods do not exist.

- [ ] **Step 3: Add UI state fields and helpers**

In `PostStationBlockEntity`:

```java
private int selectedDestinationIndex = 0;
private int selectedVehicleIndex = 0;
private boolean autoReturnOnDispatch = true;

static int clampSelectedIndexForTest(int selected, int size) {
    if (size <= 0) {
        return 0;
    }
    return Mth.clamp(selected, 0, size - 1);
}

static boolean defaultAutoReturnOnDispatchForTest() {
    return true;
}
```

Persist:

```java
tag.putInt("SelectedDestinationIndex", selectedDestinationIndex);
tag.putInt("SelectedVehicleIndex", selectedVehicleIndex);
tag.putBoolean("AutoReturnOnDispatch", autoReturnOnDispatch);
```

Load:

```java
selectedDestinationIndex = Math.max(0, tag.getInt("SelectedDestinationIndex"));
selectedVehicleIndex = Math.max(0, tag.getInt("SelectedVehicleIndex"));
autoReturnOnDispatch = !tag.contains("AutoReturnOnDispatch") || tag.getBoolean("AutoReturnOnDispatch");
```

- [ ] **Step 4: Build `PostStationScreenData` from the service**

Add:

```java
public PostStationScreenData buildPostStationScreenData(Player player) {
    LandTransportNetworkService service = new LandTransportNetworkService();
    LandTransportNetworkService.StationRef source = service.stationRef(level, this);
    List<LandTransportNetworkService.ReachableTown> reachable = source == null
            ? List.of()
            : service.reachableTowns(level, source, true);
    selectedDestinationIndex = clampSelectedIndexForTest(selectedDestinationIndex, reachable.size());
    List<PostStationScreenData.ReachableTownEntry> towns = reachable.stream()
            .map(town -> new PostStationScreenData.ReachableTownEntry(
                    town.townId(), town.townName(), town.targetStationPos(), town.targetStationName(),
                    town.distanceMeters(), town.etaSeconds(), town.source().name(),
                    town.passThroughTownNames(), town.routeName()))
            .toList();
    List<TransportEntity> vehicles = getAssignableSailboats(player);
    selectedVehicleIndex = clampSelectedIndexForTest(selectedVehicleIndex, vehicles.size());
    PostStationScreenData.RouteSummary summary = selectedRouteSummary(service, source, towns);
    return new PostStationScreenData(
            worldPosition,
            getDockName(),
            source == null ? "" : source.townId(),
            source == null ? "" : source.townName(),
            canManageDock(player),
            towns,
            selectedDestinationIndex,
            vehicleEntries(vehicles, player),
            selectedVehicleIndex,
            autoReturnOnDispatch,
            summary,
            selectedRouteWaypoints(service, source, towns),
            buildScreenData(player)
    );
}
```

Make `DockBlockEntity.getNearbySailboats` protected, or add protected `nearbyTransportEntities(Player)` returning the same list, so `PostStationBlockEntity` can describe vehicles without duplicating search code.

- [ ] **Step 5: Add destination and vehicle selection actions**

```java
public void selectDestinationIndex(int index) {
    selectedDestinationIndex = Math.max(0, index);
    setChanged();
}

public void selectVehicleIndex(int index) {
    selectedVehicleIndex = Math.max(0, index);
    setChanged();
}

public void toggleAutoReturnOnDispatch() {
    autoReturnOnDispatch = !autoReturnOnDispatch;
    setChanged();
}
```

- [ ] **Step 6: Add dispatch using `LandTransportNetworkService`**

Add:

```java
public boolean dispatchSelectedDestination(Player player) {
    if (!(level instanceof ServerLevel serverLevel)) {
        return false;
    }
    LandTransportNetworkService service = new LandTransportNetworkService();
    LandTransportNetworkService.StationRef source = service.stationRef(level, this);
    List<LandTransportNetworkService.ReachableTown> towns = source == null ? List.of() : service.reachableTowns(level, source, true);
    if (towns.isEmpty()) {
        player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.no_reachable_town"), true);
        return false;
    }
    selectedDestinationIndex = clampSelectedIndexForTest(selectedDestinationIndex, towns.size());
    String targetTownId = towns.get(selectedDestinationIndex).townId();
    LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(serverLevel, source, targetTownId, true);
    if (!availability.reachable()) {
        player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.route_unavailable"), true);
        return false;
    }
    List<TransportEntity> vehicles = getAssignableSailboats(player);
    if (vehicles.isEmpty()) {
        player.displayClientMessage(Component.translatable(noAssignableTransportTranslationKey()), true);
        return false;
    }
    selectedVehicleIndex = clampSelectedIndexForTest(selectedVehicleIndex, vehicles.size());
    TransportEntity vehicle = vehicles.get(selectedVehicleIndex);
    vehicle.setAllowNonOrderAutoReturn(autoReturnOnDispatch);
    vehicle.setRouteCatalog(List.of(availability.plan().route()), 0, worldPosition);
    if (vehicle instanceof CarriageEntity carriage) {
        carriage.setLandTransportTask(availability.plan(), autoReturnOnDispatch, CarriageEntity.TransportTaskKind.DISPATCH);
    }
    return vehicle.startAutopilotFromRouteStart();
}
```

- [ ] **Step 7: Add loaded-entity recall entry point**

In `PostStationBlockEntity`:

```java
public boolean recallSelectedVehicle(Player player) {
    List<TransportEntity> vehicles = getAssignableSailboats(player).stream()
            .filter(entity -> entity instanceof CarriageEntity carriage && carriage.canBeRecalledTo(this, player))
            .toList();
    if (vehicles.isEmpty()) {
        player.displayClientMessage(Component.translatable("screen.sailboatmod.post_station.no_recall_vehicle"), true);
        return false;
    }
    selectedVehicleIndex = clampSelectedIndexForTest(selectedVehicleIndex, vehicles.size());
    return vehicles.get(selectedVehicleIndex) instanceof CarriageEntity carriage && carriage.startRecallTo(this, player);
}
```

- [ ] **Step 8: Send the correct packet from shared menu slots**

In `DockMenu.sendDockUpdate()`:

```java
if (dock instanceof PostStationBlockEntity station) {
    ModNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> serverPlayer),
            new OpenPostStationScreenPacket(station.buildPostStationScreenData(serverPlayer))
    );
    return;
}
ModNetwork.CHANNEL.send(
        PacketDistributor.PLAYER.with(() -> serverPlayer),
        new OpenDockScreenPacket(dock.buildScreenData(serverPlayer))
);
```

- [ ] **Step 9: Keep `PostStationBlock` open flow unchanged**

Keep `NetworkHooks.openScreen(serverPlayer, station, pos)` in `PostStationBlock`. The screen sends `PostStationGuiActionPacket.REFRESH` in `init()`, so no second open mechanism is needed.

- [ ] **Step 10: Run tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.block.entity.PostStationScreenDataTest"
.\gradlew.bat compileJava
```

Expected: test pass and compile pass.

- [ ] **Step 11: Commit post station server flow**

```powershell
git add src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java src/main/java/com/monpai/sailboatmod/menu/DockMenu.java src/test/java/com/monpai/sailboatmod/block/entity/PostStationScreenDataTest.java
git commit -m "Wire post station land dispatch data"
```

## Task 4: Post Station Land Screen

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/PostStationScreen.java`
- Modify: `src/main/resources/assets/sailboatmod/lang/en_us.json`
- Modify: `src/main/resources/assets/sailboatmod/lang/zh_cn.json`

- [ ] **Step 1: Replace inheritance**

Change class declaration:

```java
public class PostStationScreen extends AbstractContainerScreen<PostStationMenu> {
```

Constructor:

```java
private PostStationScreenData data;

public PostStationScreen(PostStationMenu menu, Inventory inventory, Component title) {
    super(menu, inventory, title);
    this.imageWidth = 194;
    this.imageHeight = 176;
    PostStationScreenData initial = PostStationClientHooks.consumeFor(menu.getDockPos());
    this.data = initial != null ? initial : empty(menu.getDockPos());
}
```

- [ ] **Step 2: Add tabs and action buttons**

Use four integer tabs:

```java
private static final int TAB_DESTINATIONS = 0;
private static final int TAB_VEHICLES = 1;
private static final int TAB_DISPATCH = 2;
private static final int TAB_ADVANCED = 3;
```

In `init()` add buttons:

```java
addRenderableWidget(Button.builder(text("tab.destinations"), button -> activeTab = TAB_DESTINATIONS).bounds(panelX + 6, top + 4, 44, 16).build());
addRenderableWidget(Button.builder(text("tab.vehicles"), button -> activeTab = TAB_VEHICLES).bounds(panelX + 52, top + 4, 40, 16).build());
addRenderableWidget(Button.builder(text("tab.dispatch"), button -> activeTab = TAB_DISPATCH).bounds(panelX + 94, top + 4, 42, 16).build());
addRenderableWidget(Button.builder(text("tab.advanced"), button -> activeTab = TAB_ADVANCED).bounds(panelX + 138, top + 4, 50, 16).build());
addRenderableWidget(Button.builder(text("refresh"), button -> send(PostStationGuiActionPacket.Action.REFRESH)).bounds(panelX + 146, top + 160, 40, 16).build());
dispatchButton = addRenderableWidget(Button.builder(text("dispatch"), button -> send(PostStationGuiActionPacket.Action.DISPATCH_SELECTED)).bounds(panelX + 8, top + 142, 70, 16).build());
recallButton = addRenderableWidget(Button.builder(text("recall"), button -> send(PostStationGuiActionPacket.Action.RECALL_SELECTED)).bounds(panelX + 82, top + 142, 60, 16).build());
autoReturnButton = addRenderableWidget(Button.builder(Component.empty(), button -> send(PostStationGuiActionPacket.Action.TOGGLE_AUTO_RETURN)).bounds(panelX + 8, top + 122, 132, 16).build());
send(PostStationGuiActionPacket.Action.REFRESH);
```

- [ ] **Step 3: Draw destination list**

Use row click to send `SELECT_DESTINATION_INDEX`:

```java
private void drawDestinationList(GuiGraphics g, int x, int y, int w) {
    List<PostStationScreenData.ReachableTownEntry> towns = data.reachableTowns();
    if (towns.isEmpty()) {
        g.drawString(font, text("empty.no_reachable_town"), x + 4, y + 4, 0xFFE7E3D8);
        return;
    }
    for (int i = 0; i < Math.min(7, towns.size()); i++) {
        PostStationScreenData.ReachableTownEntry town = towns.get(i);
        int rowY = y + i * 16;
        int color = i == data.selectedTownIndex() ? 0xFF2F6F4E : 0xFF49311F;
        g.fill(x, rowY, x + w, rowY + 15, color);
        g.drawString(font, trim(town.townName(), w - 54), x + 4, rowY + 3, 0xFFF4CF8A);
        g.drawString(font, town.distanceMeters() + "m", x + w - 46, rowY + 3, 0xFFAEDAD1);
    }
}
```

- [ ] **Step 4: Draw route summary with pass-through towns**

```java
private void drawRouteSummary(GuiGraphics g, int x, int y, int w) {
    PostStationScreenData.RouteSummary summary = data.selectedRouteSummary();
    if (summary == null || summary.routeName().isBlank()) {
        g.drawString(font, text("route.none"), x + 4, y + 4, 0xFFE7E3D8);
        return;
    }
    g.drawString(font, trim(summary.routeName(), w - 8), x + 4, y + 4, 0xFFF4CF8A);
    g.drawString(font, summary.distanceMeters() + "m / " + summary.etaSeconds() + "s", x + 4, y + 16, 0xFFAEDAD1);
    if (!summary.passThroughTownNames().isEmpty()) {
        g.drawString(font, trim(String.join(" > ", summary.passThroughTownNames()), w - 8), x + 4, y + 28, 0xFFE7E3D8);
    }
}
```

- [ ] **Step 5: Draw vehicles and dispatch tab**

Vehicle rows use `PostStationScreenData.VehicleEntry`. Dispatch tab shows selected destination, selected vehicle, auto-return state, and disables dispatch when either list is empty:

```java
dispatchButton.active = data.canManage() && !data.reachableTowns().isEmpty() && !data.vehicles().isEmpty();
autoReturnButton.setMessage(text(data.autoReturnOnDispatch() ? "auto_return.on" : "auto_return.off"));
```

- [ ] **Step 6: Add advanced tab actions**

Advanced tab uses `data.advancedData()` for legacy route book names/metas and sends `ADV_*` actions. Keep the same route book slot and storage deposit slot positions from `DockScreen` so existing `DockMenu` slots remain aligned.

- [ ] **Step 7: Add language keys**

`en_us.json`:

```json
"screen.sailboatmod.post_station.tab.destinations": "Towns",
"screen.sailboatmod.post_station.tab.vehicles": "Vehicles",
"screen.sailboatmod.post_station.tab.dispatch": "Dispatch",
"screen.sailboatmod.post_station.tab.advanced": "Advanced",
"screen.sailboatmod.post_station.empty.no_reachable_town": "No reachable town",
"screen.sailboatmod.post_station.no_reachable_town": "No reachable town is connected by road.",
"screen.sailboatmod.post_station.route_unavailable": "The selected town is no longer reachable.",
"screen.sailboatmod.post_station.no_recall_vehicle": "No loaded parked carriage can be recalled.",
"screen.sailboatmod.post_station.auto_return.on": "Auto return: ON",
"screen.sailboatmod.post_station.auto_return.off": "Auto return: OFF"
```

`zh_cn.json`:

```json
"screen.sailboatmod.post_station.tab.destinations": "目的地",
"screen.sailboatmod.post_station.tab.vehicles": "车辆",
"screen.sailboatmod.post_station.tab.dispatch": "发车",
"screen.sailboatmod.post_station.tab.advanced": "高级",
"screen.sailboatmod.post_station.empty.no_reachable_town": "没有可达城镇",
"screen.sailboatmod.post_station.no_reachable_town": "没有通过道路联通的可达城镇。",
"screen.sailboatmod.post_station.route_unavailable": "选择的城镇当前不可达。",
"screen.sailboatmod.post_station.no_recall_vehicle": "没有可召回的已加载停靠马车。",
"screen.sailboatmod.post_station.auto_return.on": "自动返回: 开",
"screen.sailboatmod.post_station.auto_return.off": "自动返回: 关"
```

- [ ] **Step 8: Compile UI**

```powershell
.\gradlew.bat compileJava
```

Expected: compile pass.

- [ ] **Step 9: Commit UI**

```powershell
git add src/main/java/com/monpai/sailboatmod/client/screen/PostStationScreen.java src/main/resources/assets/sailboatmod/lang/en_us.json src/main/resources/assets/sailboatmod/lang/zh_cn.json
git commit -m "Redesign post station land dispatch screen"
```

## Task 5: Carriage Land Task State, Auto Return, And Recall

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/entity/TransportEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/entity/CarriageTaskStateTest.java`

- [ ] **Step 1: Write state tests**

```java
package com.monpai.sailboatmod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageTaskStateTest {
    @Test
    void taskStatePersistsThroughNbt() {
        CompoundTag tag = CarriageEntity.saveLandTaskStateForTest(
                new BlockPos(1, 64, 1),
                new BlockPos(100, 64, 0),
                "town-c",
                false,
                CarriageEntity.TransportTaskKind.DISPATCH
        );

        CarriageEntity.LandTaskSnapshot snapshot = CarriageEntity.loadLandTaskStateForTest(tag);

        assertEquals(new BlockPos(1, 64, 1), snapshot.homeStationPos());
        assertEquals(new BlockPos(100, 64, 0), snapshot.destinationStationPos());
        assertEquals("town-c", snapshot.destinationTownId());
        assertFalse(snapshot.autoReturnOnArrival());
        assertEquals(CarriageEntity.TransportTaskKind.DISPATCH, snapshot.taskKind());
    }

    @Test
    void marketAndDispatchTasksDefaultToAutoReturn() {
        assertTrue(CarriageEntity.defaultAutoReturnForTaskForTest(CarriageEntity.TransportTaskKind.MARKET_ORDER));
        assertTrue(CarriageEntity.defaultAutoReturnForTaskForTest(CarriageEntity.TransportTaskKind.DISPATCH));
    }
}
```

- [ ] **Step 2: Run failing test**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageTaskStateTest"
```

Expected: compile fails because task-state helpers and enum do not exist.

- [ ] **Step 3: Add task fields and enum**

In `CarriageEntity`:

```java
public enum TransportTaskKind {
    NONE,
    DISPATCH,
    MARKET_ORDER,
    RETURN,
    RECALL
}

@Nullable private BlockPos homeStationPos;
@Nullable private BlockPos destinationStationPos;
private String destinationTownId = "";
@Nullable private BlockPos dockedStationPos;
private String dockedTownId = "";
private boolean autoReturnOnArrival = true;
private TransportTaskKind transportTaskKind = TransportTaskKind.NONE;

public record LandTaskSnapshot(@Nullable BlockPos homeStationPos, @Nullable BlockPos destinationStationPos,
                               String destinationTownId, boolean autoReturnOnArrival, TransportTaskKind taskKind) {
}
```

- [ ] **Step 4: Persist task fields**

Save:

```java
writeNullableBlockPos(tag, "HomeStationPos", homeStationPos);
writeNullableBlockPos(tag, "DestinationStationPos", destinationStationPos);
writeNullableBlockPos(tag, "DockedStationPos", dockedStationPos);
tag.putString("DestinationTownId", destinationTownId == null ? "" : destinationTownId);
tag.putString("DockedTownId", dockedTownId == null ? "" : dockedTownId);
tag.putBoolean("AutoReturnOnArrival", autoReturnOnArrival);
tag.putString("TransportTaskKind", transportTaskKind.name());
```

Load:

```java
homeStationPos = readNullableBlockPos(tag, "HomeStationPos");
destinationStationPos = readNullableBlockPos(tag, "DestinationStationPos");
dockedStationPos = readNullableBlockPos(tag, "DockedStationPos");
destinationTownId = tag.getString("DestinationTownId");
dockedTownId = tag.getString("DockedTownId");
autoReturnOnArrival = !tag.contains("AutoReturnOnArrival") || tag.getBoolean("AutoReturnOnArrival");
transportTaskKind = parseTaskKind(tag.getString("TransportTaskKind"));
```

- [ ] **Step 5: Implement test helpers**

```java
static CompoundTag saveLandTaskStateForTest(BlockPos home, BlockPos destination, String townId,
                                            boolean autoReturn, TransportTaskKind kind) {
    CompoundTag tag = new CompoundTag();
    writeNullableBlockPos(tag, "HomeStationPos", home);
    writeNullableBlockPos(tag, "DestinationStationPos", destination);
    tag.putString("DestinationTownId", townId == null ? "" : townId);
    tag.putBoolean("AutoReturnOnArrival", autoReturn);
    tag.putString("TransportTaskKind", kind == null ? TransportTaskKind.NONE.name() : kind.name());
    return tag;
}

static LandTaskSnapshot loadLandTaskStateForTest(CompoundTag tag) {
    return new LandTaskSnapshot(
            readNullableBlockPos(tag, "HomeStationPos"),
            readNullableBlockPos(tag, "DestinationStationPos"),
            tag.getString("DestinationTownId"),
            !tag.contains("AutoReturnOnArrival") || tag.getBoolean("AutoReturnOnArrival"),
            parseTaskKind(tag.getString("TransportTaskKind"))
    );
}

static boolean defaultAutoReturnForTaskForTest(TransportTaskKind kind) {
    return kind == TransportTaskKind.DISPATCH || kind == TransportTaskKind.MARKET_ORDER;
}
```

- [ ] **Step 6: Add land task API**

```java
public void setLandTransportTask(LandTransportNetworkService.LandRoutePlan plan, boolean autoReturn, TransportTaskKind kind) {
    if (plan == null) {
        return;
    }
    homeStationPos = plan.sourceStationPos();
    destinationStationPos = plan.targetStationPos();
    destinationTownId = plan.targetTownId();
    dockedStationPos = null;
    dockedTownId = "";
    autoReturnOnArrival = autoReturn;
    transportTaskKind = kind == null ? TransportTaskKind.DISPATCH : kind;
}

@Override
public void setAllowNonOrderAutoReturn(boolean allow) {
    autoReturnOnArrival = allow;
}
```

In `TransportEntity`, add default methods so existing sailboat code is not forced to implement carriage-only operations:

```java
default void setLandTransportTask(LandTransportNetworkService.LandRoutePlan plan, boolean autoReturn, CarriageEntity.TransportTaskKind kind) {
}
```

- [ ] **Step 7: Replace `finishAutopilot()` arrival logic**

Update `finishAutopilot()`:

```java
private void finishAutopilot() {
    BlockPos destinationPos = findTransportHubZoneContains(position());
    DockBlockEntity destination = destinationPos == null ? null : getTransportHub(destinationPos);
    if (destination != null) {
        dockedStationPos = destination.getBlockPos().immutable();
        dockedTownId = DockTownResolver.resolveTownForArrival(level(), destination.getBlockPos());
        List<ItemStack> cargo = unloadAllCargo();
        if (!cargo.isEmpty()) {
            destination.receiveShipment(this, getAutopilotRouteName(), pendingShipperName, "-", destination.getDockName(),
                    System.currentTimeMillis(), 0L, 0.0D, cargo, getPendingShipmentManifest());
        }
    }
    boolean startedReturn = destination instanceof PostStationBlockEntity station
            && transportTaskKind != TransportTaskKind.RETURN
            && transportTaskKind != TransportTaskKind.RECALL
            && autoReturnOnArrival
            && tryStartLandReturnTrip(station);
    if (!startedReturn) {
        transportTaskKind = TransportTaskKind.NONE;
        stopAutopilot();
    }
}
```

- [ ] **Step 8: Generate return route through the service**

```java
private boolean tryStartLandReturnTrip(PostStationBlockEntity currentStation) {
    if (!(level() instanceof ServerLevel serverLevel) || homeStationPos == null) {
        return false;
    }
    if (!(level().getBlockEntity(homeStationPos) instanceof PostStationBlockEntity homeStation)) {
        return false;
    }
    LandTransportNetworkService service = new LandTransportNetworkService();
    LandTransportNetworkService.RouteAvailability availability = service.planRouteBetweenStations(
            serverLevel,
            service.stationRef(level(), currentStation),
            service.stationRef(level(), homeStation),
            true
    );
    if (!availability.reachable()) {
        return false;
    }
    setRouteCatalog(List.of(availability.plan().route()), 0, currentStation.getBlockPos());
    setLandTransportTask(availability.plan(), false, TransportTaskKind.RETURN);
    return startAutopilot();
}
```

- [ ] **Step 9: Add recall checks and start**

```java
public boolean canBeRecalledTo(PostStationBlockEntity targetStation, @Nullable Player player) {
    if (targetStation == null || !isAlive() || isAutopilotActive() || hasManualControlPassenger()) {
        return false;
    }
    if (player != null && !isOwnedBy(player) && !isAvailableForRent()) {
        return false;
    }
    return dockedStationPos != null || !dockedTownId.isBlank();
}

public boolean startRecallTo(PostStationBlockEntity targetStation, @Nullable Player player) {
    if (!(level() instanceof ServerLevel serverLevel) || targetStation == null || dockedStationPos == null) {
        return false;
    }
    if (!(level().getBlockEntity(dockedStationPos) instanceof PostStationBlockEntity sourceStation)) {
        return false;
    }
    LandTransportNetworkService service = new LandTransportNetworkService();
    LandTransportNetworkService.RouteAvailability availability = service.planRouteBetweenStations(
            serverLevel,
            service.stationRef(level(), sourceStation),
            service.stationRef(level(), targetStation),
            true
    );
    if (!availability.reachable()) {
        return false;
    }
    setRouteCatalog(List.of(availability.plan().route()), 0, sourceStation.getBlockPos());
    setLandTransportTask(availability.plan(), false, TransportTaskKind.RECALL);
    return startAutopilot();
}
```

- [ ] **Step 10: Run carriage tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.entity.CarriageTaskStateTest" --tests "com.monpai.sailboatmod.entity.CarriageEntityMovementTest" --tests "com.monpai.sailboatmod.entity.CarriageLandDriveModelTest" --tests "com.monpai.sailboatmod.entity.CarriageDriveControllerTest"
```

Expected: all selected tests pass.

- [ ] **Step 11: Commit carriage task state**

```powershell
git add src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java src/main/java/com/monpai/sailboatmod/entity/TransportEntity.java src/test/java/com/monpai/sailboatmod/entity/CarriageTaskStateTest.java
git commit -m "Add carriage land transport task state"
```

## Task 6: Market Stable IDs And Reachability Filtering

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/CancelMarketListingPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/network/packet/DispatchMarketOrderPacket.java`
- Modify: `src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketReachabilityTest.java`

- [ ] **Step 1: Add stable ID tests**

```java
@Test
void listingEntryCarriesStableListingId() {
    MarketOverviewData.ListingEntry entry = new MarketOverviewData.ListingEntry(
            "listing-1", "Oak x4", "minecraft:oak_log", "Oak Log", 4, 0, 10,
            "Seller", "seller-uuid", "A Warehouse", "nation-a", "", "wood", 1);

    assertEquals("listing-1", entry.listingId());
}

@Test
void orderEntryCarriesStableOrderId() {
    MarketOverviewData.OrderEntry entry = new MarketOverviewData.OrderEntry(
            "order-1", "Oak x4", "A", "C", 4, "WAITING_SHIPMENT", List.of());

    assertEquals("order-1", entry.orderId());
}
```

- [ ] **Step 2: Run test and confirm failure**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.MarketReachabilityTest"
```

Expected: compile fails because constructor signatures do not include ids yet.

- [ ] **Step 3: Add IDs to overview records**

In `MarketOverviewData`:

```java
public record ListingEntry(String listingId, String label, String commodityKey, String itemName,
                           int availableCount, int reservedCount, int unitPrice, String sellerName,
                           String sellerUuid, String sourceDockName, String nationId, String sellerNote,
                           String category, int rarity) {
}

public record OrderEntry(String orderId, String label, String sourceDockName, String targetDockName,
                         int quantity, String status, List<DispatchOption> dispatchOptions) {
    public OrderEntry {
        dispatchOptions = dispatchOptions == null ? List.of() : List.copyOf(dispatchOptions);
    }
}
```

- [ ] **Step 4: Update market packet serialization**

In `writeListingEntries`, write `entry.listingId()` before `label`. In `readListingEntries`, read it first and pass it to the constructor. Do the same for `OrderEntry.orderId`.

- [ ] **Step 5: Update client packets to send IDs**

`PurchaseMarketListingPacket`:

```java
private final String listingId;

public PurchaseMarketListingPacket(BlockPos marketPos, String listingId, int quantity) {
    this.marketPos = marketPos;
    this.listingId = listingId == null ? "" : listingId;
    this.quantity = quantity;
}
```

Encode with `PacketStringCodec.writeUtfSafe(buffer, packet.listingId, 64)`.

`CancelMarketListingPacket` mirrors the same `listingId` field.

`DispatchMarketOrderPacket` uses `orderId`:

```java
private final String orderId;
```

- [ ] **Step 6: Update `MarketScreen` sends**

Buying:

```java
MarketOverviewData.ListingEntry listing = selectedListing();
if (listing != null) {
    ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(data.marketPos(), listing.listingId(), parsePositive(buyQtyValue, 1)));
}
```

Cancel listing:

```java
MarketOverviewData.ListingEntry listing = selectedListing();
if (listing != null) {
    ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), listing.listingId()));
}
```

Dispatch:

```java
MarketOverviewData.OrderEntry order = selectedOrder();
MarketOverviewData.DispatchOption option = selectedShipping();
if (order != null) {
    ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(
            data.marketPos(),
            order.orderId(),
            option == null ? TransportTerminalKind.AUTO : TransportTerminalKind.fromName(option.terminalKind())
    ));
}
```

- [ ] **Step 7: Add server ID methods**

In `MarketBlockEntity`:

```java
public boolean purchaseListingById(String playerUuid, String playerName, @Nullable Player onlinePlayer, String listingId, int quantity) {
    MarketSavedData market = MarketSavedData.get(level);
    MarketListing listing = market.getListing(listingId);
    if (listing == null) {
        return false;
    }
    if (!canViewerReachListing(playerUuid, listing)) {
        return false;
    }
    return purchaseListingResolved(playerUuid, playerName, onlinePlayer, listing, quantity);
}
```

Refactor the existing `purchaseListing(..., int listingIndex, ...)` into a wrapper that resolves the current unfiltered listing for compatibility, then calls `purchaseListingResolved`.

Dispatch by id:

```java
public boolean dispatchOrderById(String playerUuid, String playerName, @Nullable Player onlinePlayer,
                                 String orderId, TransportTerminalKind terminalKind) {
    MarketSavedData market = MarketSavedData.get(level);
    PurchaseOrder order = market.getPurchaseOrder(orderId);
    if (order == null || linkedDockPos == null || !linkedDockPos.equals(order.sourceDockPos())) {
        return false;
    }
    return dispatchResolvedOrder(playerUuid, playerName, onlinePlayer, market, order, terminalKind);
}
```

- [ ] **Step 8: Add listing reachability filtering**

In `buildOverviewForIdentity`, replace the listing loop with:

```java
for (MarketListing listing : market.getListings()) {
    if (!isListingVisibleToTown(townId, listing)) {
        continue;
    }
    listingEntries.add(new MarketOverviewData.ListingEntry(
            listing.listingId(),
            line,
            CommodityKeyResolver.resolve(listing.itemStack()),
            listing.itemStack().isEmpty() ? "-" : listing.itemStack().getHoverName().getString(),
            listing.availableCount(),
            listing.reservedCount(),
            currentListingUnitPrice(listing, 1),
            listing.sellerName(),
            listing.sellerUuid(),
            listing.sourceDockName().isBlank() ? listing.sourceDockPos().toShortString() : listing.sourceDockName(),
            listing.nationId(),
            listing.sellerNote(),
            category,
            listingRarity
    ));
}
```

Add:

```java
private boolean isListingVisibleToTown(String viewerTownId, MarketListing listing) {
    if (viewerTownId == null || viewerTownId.isBlank() || listing == null) {
        return false;
    }
    String sourceTownId = DockTownResolver.resolveTownForSource(level, listing.sourceDockPos(), listing.townId());
    if (viewerTownId.equals(sourceTownId)) {
        return true;
    }
    return hasPortRoute(sourceTownId, viewerTownId) || hasLandRoute(sourceTownId, viewerTownId);
}
```

- [ ] **Step 9: Add land route check for market**

```java
private boolean hasLandRoute(String sourceTownId, String targetTownId) {
    if (!(level instanceof ServerLevel serverLevel)) {
        return false;
    }
    LandTransportNetworkService service = new LandTransportNetworkService();
    List<DockBlockEntity> sourceStations = terminalsForTown(sourceTownId, TransportTerminalKind.POST_STATION);
    for (DockBlockEntity source : sourceStations) {
        if (source instanceof PostStationBlockEntity station) {
            LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(
                    serverLevel,
                    service.stationRef(level, station),
                    targetTownId,
                    true
            );
            if (availability.reachable()) {
                return true;
            }
        }
    }
    return false;
}
```

Port check uses existing `terminalsForTown` and `findRouteIndexByDestinationDock`.

- [ ] **Step 10: Run packet and market tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.MarketReachabilityTest" --tests "com.monpai.sailboatmod.network.packet.*Market*PacketTest"
.\gradlew.bat compileJava
```

Expected: selected tests pass and compile pass.

- [ ] **Step 11: Commit stable IDs and filtering**

```powershell
git add src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java src/main/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacket.java src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java src/main/java/com/monpai/sailboatmod/network/packet/CancelMarketListingPacket.java src/main/java/com/monpai/sailboatmod/network/packet/DispatchMarketOrderPacket.java src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/test/java/com/monpai/sailboatmod/market/MarketReachabilityTest.java
git commit -m "Filter market listings by transport reachability"
```

## Task 7: Market POST_STATION Dispatch Uses Land Route Plans

**Files:**
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java`
- Modify: `src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java`
- Test: `src/test/java/com/monpai/sailboatmod/market/MarketReachabilityTest.java`

- [ ] **Step 1: Add A-B-C dispatch test**

Add a test named `landReachableChainDispatchBuildsSingleRouteToFinalTown` that asserts:

```java
assertEquals("town-c", dispatchPlan.targetTownId());
assertEquals(List.of("Bridge"), dispatchPlan.passThroughTownNames());
assertEquals("Alpha -> Cedar", dispatchPlan.route().name());
```

- [ ] **Step 2: Run failing test**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.MarketReachabilityTest"
```

Expected: failure because POST_STATION dispatch still requires direct saved route indexes.

- [ ] **Step 3: Change terminal plan to carry generated routes**

Replace:

```java
private record DispatchTerminalPlan(DockBlockEntity sourceTerminal, DockBlockEntity targetTerminal,
                                    int routeIndex, double pairScore) {
}
```

with:

```java
private record DispatchTerminalPlan(DockBlockEntity sourceTerminal, DockBlockEntity targetTerminal,
                                    int routeIndex, @Nullable RouteDefinition generatedRoute,
                                    @Nullable LandTransportNetworkService.LandRoutePlan landPlan,
                                    double pairScore) {
    boolean usesGeneratedRoute() {
        return generatedRoute != null;
    }
}
```

- [ ] **Step 4: Resolve POST_STATION through `LandTransportNetworkService`**

In `resolveDispatchTerminalPlan`, when `terminalKind == TransportTerminalKind.POST_STATION`, ignore saved direct route indexes and use:

```java
LandTransportNetworkService service = new LandTransportNetworkService();
LandTransportNetworkService.RouteAvailability availability = service.planRouteToTown(
        serverLevel,
        service.stationRef(level, sourceStation),
        targetWarehouse.getTownId(),
        true
);
if (!availability.reachable()) {
    continue;
}
LandTransportNetworkService.LandRoutePlan plan = availability.plan();
if (!plan.targetStationPos().equals(targetTerminal.getBlockPos())) {
    continue;
}
double pairScore = Vec3.atCenterOf(sourceWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(sourceTerminal.getBlockPos()))
        + Vec3.atCenterOf(targetWarehouse.getBlockPos()).distanceToSqr(Vec3.atCenterOf(targetTerminal.getBlockPos()))
        + plan.distanceMeters();
best = chooseLowerScore(best, new DispatchTerminalPlan(sourceTerminal, targetTerminal, -1, plan.route(), plan, pairScore));
```

- [ ] **Step 5: Let shipment plan hold a route definition**

Change `ShipmentPlan`:

```java
private record ShipmentPlan(int routeIndex, @Nullable RouteDefinition generatedRoute,
                            @Nullable LandTransportNetworkService.LandRoutePlan landPlan,
                            BlockPos targetDockPos, String targetDockName, List<ItemStack> cargo,
                            List<ShipmentOrderSelection> selections) {
}
```

Pass `terminalPlan.generatedRoute()` and `terminalPlan.landPlan()` from plan construction.

- [ ] **Step 6: Assign generated post station route without saving it to the station route list**

In `dispatchShipmentPlan`:

```java
if (terminalKind == TransportTerminalKind.POST_STATION && plan.generatedRoute() != null && boat instanceof CarriageEntity carriage) {
    boat.setRouteCatalog(List.of(plan.generatedRoute()), 0, sourceDock.getBlockPos());
    carriage.setLandTransportTask(plan.landPlan(), true, CarriageEntity.TransportTaskKind.MARKET_ORDER);
    if (!boat.startAutopilotFromRouteStart()) {
        sourceDock.insertCargo(boat.unloadAllCargo());
        boat.clearPendingMarketDelivery();
        return false;
    }
} else if (!sourceDock.assignLoadedBoatToRouteIndex(boat, plan.routeIndex(), true, player)) {
    sourceDock.insertCargo(boat.unloadAllCargo());
    boat.clearPendingMarketDelivery();
    return false;
}
```

- [ ] **Step 7: Update dispatch preview**

For `POST_STATION`, `buildDispatchOption` should display the `LandRoutePlan.route().name()`, `distanceMeters`, ETA, and pass-through town detail:

```java
String detail = plan.passThroughTownNames().isEmpty()
        ? plan.sourceStation().townName() + " -> " + plan.targetStation().townName()
        : plan.sourceStation().townName() + " via " + String.join(" > ", plan.passThroughTownNames()) + " -> " + plan.targetStation().townName();
```

- [ ] **Step 8: Preserve waiting orders when dispatch route disappears**

Ensure `dispatchSelectedOrder` and `tryDispatchWaitingOrders` return `false` before cargo extraction if `resolveDispatchTerminalPlan` returns `null`. Do not call `splitOrderForShipment` before a reachable route plan exists.

- [ ] **Step 9: Run market dispatch tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.market.MarketReachabilityTest"
.\gradlew.bat compileJava
```

Expected: pass.

- [ ] **Step 10: Commit dispatch integration**

```powershell
git add src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java src/test/java/com/monpai/sailboatmod/market/MarketReachabilityTest.java
git commit -m "Dispatch market land orders through reachable towns"
```

## Task 8: Regression Sweep And Packaging

**Files:**
- No planned source edits. If compilation exposes missed imports or method signatures, fix only the files touched by Tasks 1-7.

- [ ] **Step 1: Run focused land transport tests**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.LandTransportNetworkServiceTest" --tests "com.monpai.sailboatmod.entity.CarriageTaskStateTest" --tests "com.monpai.sailboatmod.market.MarketReachabilityTest" --tests "com.monpai.sailboatmod.network.packet.PostStationScreenPacketTest"
```

Expected: all focused tests pass.

- [ ] **Step 2: Run existing carriage and road graph regressions**

```powershell
.\gradlew.bat test --tests "com.monpai.sailboatmod.route.RoadGraphRoutingServiceTest" --tests "com.monpai.sailboatmod.route.CarriageRoutePlannerTest" --tests "com.monpai.sailboatmod.entity.CarriageEntityMovementTest" --tests "com.monpai.sailboatmod.entity.CarriageLandDriveModelTest" --tests "com.monpai.sailboatmod.entity.CarriageDriveControllerTest" --tests "com.monpai.sailboatmod.entity.CarriageManualInputStateTest" --tests "com.monpai.sailboatmod.network.packet.CarriageControlInputPacketTest"
```

Expected: all selected regression tests pass.

- [ ] **Step 3: Run full Java test suite**

```powershell
.\gradlew.bat test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Compile and package jar**

```powershell
.\gradlew.bat build
```

Expected jars:

- `build/libs/sailboatmod-1.3.8.jar`
- `build/libs/sailboatmod-1.3.8-reobf.jar`
- `build/libs/sailboatmod-1.3.8-all.jar`
- `build/libs/sailboatmod-marketweb-1.3.8.jar`

- [ ] **Step 5: Manual in-game verification**

Run:

```powershell
.\gradlew.bat runClient
```

Manual checks:

- Create towns A, B, C and post stations in each town.
- Build A-C road and confirm A station shows C as a destination.
- Build A-B-C road and confirm A station shows both B and C.
- Select C from A-B-C and dispatch one carriage; confirm route name is A to C and B is only pass-through.
- Toggle auto-return off; confirm carriage remains parked at C after arrival.
- Recall the parked carriage from A; confirm it drives back through a generated route and does not teleport.
- Open market in A; confirm C listings are visible only when port route or post-station land route exists.
- Remove route reachability before dispatch; confirm order remains `WAITING_SHIPMENT` and cargo is not consumed.

- [ ] **Step 6: Commit final polish**

```powershell
git status -sb
git add src/main/java src/main/resources src/test/java docs/superpowers/plans/2026-06-09-post-station-land-transport.md
git commit -m "Rebuild post station land transport"
```

## Self-Review

- Spec coverage:
  - Reachable towns by town, not station: Task 1 and Task 3.
  - A-C and A-B-C behavior with B and C visible: Task 1 tests and Task 8 manual checks.
  - Selecting C generates one full A to C route: Task 1 and Task 7.
  - Multiple stations choose best target station: Task 1.
  - Auto-return default ON and optional OFF: Task 3, Task 4, Task 5.
  - Recall is route-based, no teleport: Task 5.
  - Market visibility and dispatch use transport reachability: Task 6 and Task 7.
  - Same-town listings remain visible: Task 6 `isListingVisibleToTown`.
  - Legacy route book and manual routes remain available: Task 3 and Task 4 advanced actions.
  - First version only handles loaded carriage entities: Task 3 recall source uses loaded `TransportEntity` list.
- Red-flag scan: checked banned marker phrases, future-only markers, vague validation wording, and missing file paths.
- Type consistency:
  - `LandTransportNetworkService.RouteAvailability`, `ReachableTown`, and `LandRoutePlan` names are used consistently across post station, carriage, and market tasks.
  - `PostStationScreenData.ReachableTownEntry`, `VehicleEntry`, and `RouteSummary` names match packet and UI tasks.
  - Market `listingId` and `orderId` are carried by data records, packets, and screen actions.
