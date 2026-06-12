package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.core.BlockPos;
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
    void completedPathBuildsNamedRouteDefinition() {
        RouteDefinition route = WaterAutoRouteService.routeDefinitionFromPath(
                dock("Source Dock", new BlockPos(0, 64, 0), "town-a", "nation-a"),
                dock("Target Dock", new BlockPos(32, 64, 0), "town-b", "nation-a"),
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0), new BlockPos(32, 64, 0)),
                "Tester",
                "uuid",
                1234L);

        assertEquals("Water Auto: Target Dock", route.name());
        assertEquals("Source Dock", route.startDockName());
        assertEquals("Target Dock", route.endDockName());
        assertEquals("Tester", route.authorName());
        assertEquals("uuid", route.authorUuid());
        assertEquals(2, route.waypoints().size());
        assertEquals(32.0D, route.routeLengthMeters(), 0.001D);
    }

    private static WaterAutoRouteService.DockSnapshot dock(String name, BlockPos pos, String townId, String nationId) {
        return new WaterAutoRouteService.DockSnapshot(
                pos,
                name,
                townId,
                nationId,
                new DockBerthResolver.DockZone(pos, -12, 12, -8, 8));
    }

    private static WaterRoutePolicy policy() {
        return new WaterRoutePolicy(8, 2, 1, 2, 256, 5000, 512, 64, 64, 200);
    }

    private static WaterRoutePermissionService.RelationLookup relation(Map<String, String> values) {
        return (left, right) -> values.getOrDefault(left + "|" + right, values.getOrDefault(right + "|" + left, ""));
    }

    private static final class TestBerthWorld implements DockBerthResolver.BerthWorld {
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

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }
}
