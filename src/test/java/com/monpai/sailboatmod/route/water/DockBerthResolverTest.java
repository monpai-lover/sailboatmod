package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockBerthResolverTest {
    @Test
    void shoreDockFindsWaterInsideZone() {
        TestBerthWorld world = new TestBerthWorld().waterRect(4, -4, 10, 4);
        DockBerthResolver.DockZone zone = new DockBerthResolver.DockZone(new BlockPos(0, 64, 0), -6, 10, -4, 4);

        WaterRouteResult<DockBerthResolver.DockBerth> result =
                DockBerthResolver.resolve(world, zone, WaterRoutePolicy.defaults(), WaterRouteFailureReason.NO_SOURCE_BERTH);

        assertTrue(result.successful());
        assertTrue(result.value().pos().x >= 4.0D);
        assertTrue(zone.contains(result.value().pos()));
    }

    @Test
    void noWaterFailsWithProvidedReason() {
        WaterRouteResult<DockBerthResolver.DockBerth> result = DockBerthResolver.resolve(
                new TestBerthWorld(),
                new DockBerthResolver.DockZone(new BlockPos(0, 64, 0), -4, 4, -4, 4),
                WaterRoutePolicy.defaults(),
                WaterRouteFailureReason.NO_TARGET_BERTH);

        assertEquals(WaterRouteFailureReason.NO_TARGET_BERTH, result.reason());
    }

    @Test
    void berthAvoidsDockCore() {
        TestBerthWorld world = new TestBerthWorld().waterRect(-6, -6, 6, 6);
        WaterRouteResult<DockBerthResolver.DockBerth> result = DockBerthResolver.resolve(
                world,
                new DockBerthResolver.DockZone(new BlockPos(0, 64, 0), -6, 6, -6, 6),
                WaterRoutePolicy.defaults(),
                WaterRouteFailureReason.NO_SOURCE_BERTH);

        assertTrue(result.successful());
        assertFalse(result.value().pos().distanceToSqr(new Vec3(0.5D, 64.0D, 0.5D))
                < DockBerthResolver.DOCK_CORE_EXCLUSION_RADIUS_SQ);
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
