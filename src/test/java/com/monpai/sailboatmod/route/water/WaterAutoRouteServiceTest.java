package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.WaypointMeta;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterAutoRouteServiceTest {
    @Test
    void candidateCheckFailsWhenPermissionFails() {
        WaterRouteResult<WaterAutoRouteService.BerthPair> result = WaterAutoRouteService.canListCandidate(
                dock("Source", new BlockPos(0, 64, 0), "town-a", "nation-a"),
                dock("Target", new BlockPos(32, 64, 0), "town-b", "nation-b"),
                new TestBerthWorld().waterRect(-12, -8, 44, 8),
                relation(Map.of("nation-a|nation-b", "neutral")),
                policy());

        assertEquals(WaterRouteFailureReason.NO_PERMISSION, result.reason());
    }

    @Test
    void candidateCheckFailsWhenSourceBerthIsMissing() {
        WaterRouteResult<WaterAutoRouteService.BerthPair> result = WaterAutoRouteService.canListCandidate(
                dock("Source", new BlockPos(0, 64, 0), "town-a", "nation-a"),
                dock("Target", new BlockPos(32, 64, 0), "town-b", "nation-a"),
                new TestBerthWorld().waterRect(24, -8, 44, 8),
                relation(Map.of()),
                policy());

        assertEquals(WaterRouteFailureReason.NO_SOURCE_BERTH, result.reason());
    }

    @Test
    void sameNationBerthReadyPairIsAccepted() {
        WaterRouteResult<WaterAutoRouteService.BerthPair> result = WaterAutoRouteService.canListCandidate(
                dock("Source", new BlockPos(0, 64, 0), "town-a", "nation-a"),
                dock("Target", new BlockPos(32, 64, 0), "town-b", "nation-a"),
                new TestBerthWorld().waterRect(-12, -8, 44, 8),
                relation(Map.of()),
                policy());

        assertTrue(result.successful());
    }

    @Test
    void distantDockRemainsListedWhileSolverEnforcesSearchRadius() {
        WaterRoutePolicy shortRange = new WaterRoutePolicy(8, 2, 1, 2, 32, 5000, 512, 64, 64, 200);
        TestBerthWorld world = new TestBerthWorld().waterRect(-12, -8, 108, 8);
        WaterRouteResult<WaterAutoRouteService.BerthPair> result = WaterAutoRouteService.canListCandidate(
                dock("Source", new BlockPos(0, 64, 0), "town-a", "nation-a"),
                dock("Target", new BlockPos(96, 64, 0), "town-b", "nation-a"),
                world,
                relation(Map.of()),
                shortRange);

        assertTrue(result.successful());
        BlockPos source = BlockPos.containing(result.value().source().pos());
        BlockPos target = BlockPos.containing(result.value().target().pos());
        assertTrue(source.distSqr(target) > shortRange.maxSearchRadius() * shortRange.maxSearchRadius());

        WaterRoutePathfinder limited = new WaterRoutePathfinder(world, source, target, shortRange);
        assertEquals(WaterRoutePathfinder.Status.FAILED, runToCompletion(limited));
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, limited.failureReason());
        assertTrue(limited.path().isEmpty());

        WaterRoutePathfinder fullRange = new WaterRoutePathfinder(world, source, target, policy());
        assertEquals(WaterRoutePathfinder.Status.SUCCESS, runToCompletion(fullRange));
        assertEquals(source, fullRange.path().get(0));
        assertEquals(target, fullRange.path().get(fullRange.path().size() - 1));
    }

    @Test
    void completedPathPreservesDenseWaypointsAndMetadataInNamedRoute() {
        List<WaypointMeta> metadata = List.of(
                WaypointMeta.of(WaypointMeta.SEGMENT_START, WaypointMeta.ORIGIN_RAW),
                WaypointMeta.of(WaypointMeta.SEGMENT_MID, WaypointMeta.ORIGIN_INTERP),
                WaypointMeta.of(WaypointMeta.SEGMENT_END, WaypointMeta.ORIGIN_RAW));
        RouteDefinition route = WaterAutoRouteService.routeDefinitionFromPath(
                dock("Source Dock", new BlockPos(0, 64, 0), "town-a", "nation-a"),
                dock("Target Dock", new BlockPos(32, 64, 0), "town-b", "nation-a"),
                "TownA",
                "TownB",
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0), new BlockPos(32, 64, 0)),
                metadata,
                "Tester",
                "uuid",
                1234L);

        assertEquals("Auto-TownA-TownB", route.name());
        assertEquals("Source Dock", route.startDockName());
        assertEquals("Target Dock", route.endDockName());
        assertEquals("Tester", route.authorName());
        assertEquals("uuid", route.authorUuid());
        assertEquals(3, route.waypoints().size());
        assertEquals(List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(16.5D, 64.0D, 0.5D),
                new Vec3(32.5D, 64.0D, 0.5D)), route.waypoints());
        assertEquals(metadata, route.waypointMetas());
        assertEquals(32.0D, route.routeLengthMeters(), 0.001D);
    }

    @Test
    void autoRouteUpsertReplacesExistingRouteForSameTargetDock() {
        RouteDefinition oldRoute = route("Water Auto: Target Dock", "Source Dock", "Target Dock", 32.0D);
        RouteDefinition manualRoute = route("Manual Harbor Loop", "Source Dock", "Other Dock", 12.0D);
        RouteDefinition newRoute = route("Water Auto: Target Dock", "Source Dock", "Target Dock", 48.0D);

        List<RouteDefinition> merged = WaterAutoRouteService.upsertAutoRouteForTest(
                List.of(oldRoute, manualRoute),
                newRoute);

        assertEquals(2, merged.size());
        assertEquals(manualRoute, merged.get(0));
        assertEquals(newRoute, merged.get(1));
    }

    private static WaterAutoRouteService.DockSnapshot dock(String name, BlockPos pos, String townId, String nationId) {
        return new WaterAutoRouteService.DockSnapshot(
                pos,
                name,
                townId,
                nationId,
                new DockBerthResolver.DockZone(pos, -12, 12, -8, 8));
    }

    private static RouteDefinition route(String name, String sourceName, String targetName, double length) {
        return new RouteDefinition(
                name,
                List.of(new net.minecraft.world.phys.Vec3(0.5D, 64.0D, 0.5D)),
                "Tester",
                "uuid",
                1L,
                length,
                sourceName,
                targetName);
    }

    private static WaterRoutePolicy policy() {
        return new WaterRoutePolicy(8, 2, 1, 2, 256, 5000, 512, 64, 64, 200);
    }

    private static WaterRoutePathfinder.Status runToCompletion(WaterRoutePathfinder pathfinder) {
        WaterRoutePathfinder.Status status = WaterRoutePathfinder.Status.RUNNING;
        for (int tick = 0; tick < 200 && status == WaterRoutePathfinder.Status.RUNNING; tick++) {
            status = pathfinder.step(64, 0);
        }
        return status;
    }

    private static WaterRoutePermissionService.RelationLookup relation(Map<String, String> values) {
        return (left, right) -> values.getOrDefault(left + "|" + right, values.getOrDefault(right + "|" + left, ""));
    }

    private static final class TestBerthWorld implements DockBerthResolver.BerthWorld, WaterRouteWorld {
        private final Set<Long> water = new HashSet<>();

        TestBerthWorld waterRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.add(key(x, z));
                }
            }
            return this;
        }

        @Override
        public boolean isBerthWater(int x, int z, WaterRoutePolicy policy) {
            return water.contains(key(x, z));
        }

        @Override
        public int waterSurfaceY(int x, int z) {
            return 64;
        }

        @Override
        public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            return isBerthWater(x, z, policy)
                    ? WaterColumn.passable(new BlockPos(x, waterSurfaceY(x, z), z), 0.0D)
                    : WaterColumn.blocked();
        }

        @Override
        public boolean canLoadMoreChunks(int requested) {
            return true;
        }

        @Override
        public int consumedChunkLoads() {
            return 0;
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }
}
