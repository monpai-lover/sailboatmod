package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.route.LandTransportNetworkService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketReachabilityTest {
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
                "order-1", "Oak x4", "A", "C", 4, "WAITING_SHIPMENT", 0, 0, List.of());

        assertEquals("order-1", entry.orderId());
    }

    @Test
    void marketPostStationDispatchDoesNotUseTerrainFallback() {
        assertFalse(MarketBlockEntity.allowTerrainFallbackForPostStationDispatchForTest());
    }

    @Test
    void landReachableChainDispatchBuildsSingleRouteToFinalTown() {
        TestWorld world = new TestWorld()
                .station("a1", "town-a", "Alpha", new BlockPos(0, 64, 0))
                .station("b1", "town-b", "Bridge", new BlockPos(50, 64, 0))
                .station("c1", "town-c", "Cedar", new BlockPos(100, 64, 0))
                .road("a1", "b1", 50)
                .road("b1", "c1", 50);

        LandTransportNetworkService.RouteAvailability availability =
                world.service().planRouteToTown(world.level(), world.station("a1"), "town-c", true);

        assertTrue(availability.reachable());
        LandTransportNetworkService.LandRoutePlan dispatchPlan = availability.plan();
        assertEquals("town-c", dispatchPlan.targetTownId());
        assertEquals(List.of("Bridge"), dispatchPlan.passThroughTownNames());
        assertEquals("Alpha -> Cedar", dispatchPlan.route().name());
        assertEquals("Alpha via Bridge -> Cedar", MarketBlockEntity.landDispatchDetail(dispatchPlan));
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

        LandTransportNetworkService.TestLevelRef level() {
            return level;
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
                                        source,
                                        target,
                                        ids.stream().map(stations::get).toList(),
                                        waypoints,
                                        "System",
                                        LandTransportNetworkService.RouteSource.ROAD_GRAPH));
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
                if (current.stationId().equals(end)) {
                    break;
                }
                for (Map.Entry<String, Integer> edge : roads.getOrDefault(current.stationId(), new java.util.LinkedHashMap<>()).entrySet()) {
                    int candidate = current.distance() + edge.getValue();
                    if (candidate < dist.getOrDefault(edge.getKey(), Integer.MAX_VALUE)) {
                        dist.put(edge.getKey(), candidate);
                        prev.put(edge.getKey(), current.stationId());
                        open.add(new Node(edge.getKey(), candidate));
                    }
                }
            }
            if (!dist.containsKey(end)) {
                return List.of();
            }
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            String cursor = end;
            out.add(cursor);
            while (!cursor.equals(start)) {
                cursor = prev.get(cursor);
                if (cursor == null) {
                    return List.of();
                }
                out.add(cursor);
            }
            java.util.Collections.reverse(out);
            return out;
        }

        private record Node(String stationId, int distance) {
        }
    }
}
