package com.monpai.sailboatmod.route;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadAutoRouteServiceTest {
    @Test
    void mergesStoredAndGeneratedRoutesWithoutDuplicatingShape() {
        RouteDefinition stored = new RouteDefinition(
                "Manual Route",
                List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(10.5D, 64.0D, 10.5D)),
                "Player",
                "uuid",
                1L,
                20.0D,
                "Start",
                "End"
        );
        RouteDefinition generatedDuplicate = new RouteDefinition(
                "Road Link: End",
                List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(10.5D, 64.0D, 10.5D)),
                "System",
                "",
                2L,
                20.0D,
                "Start",
                "End"
        );
        RouteDefinition generatedNew = new RouteDefinition(
                "Road Link: Other",
                List.of(new Vec3(0.5D, 64.0D, 0.5D), new Vec3(4.5D, 64.0D, 4.5D), new Vec3(8.5D, 64.0D, 8.5D)),
                "System",
                "",
                3L,
                16.0D,
                "Start",
                "Other"
        );

        List<RouteDefinition> merged = RoadAutoRouteService.mergeRoutesForTest(
                List.of(stored),
                List.of(generatedDuplicate, generatedNew)
        );

        assertEquals(2, merged.size());
        assertEquals("Manual Route", merged.get(0).name());
        assertEquals("Road Link: Other", merged.get(1).name());
    }

    @Test
    void prefersHybridNetworkResolutionOverLongerDirectFallback() {
        RoadAutoRouteService.RouteResolution direct = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.LAND_TERRAIN,
                List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(10, 64, 0))
        );
        RoadAutoRouteService.RouteResolution hybrid = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.ROAD_NETWORK,
                List.of(
                        new net.minecraft.core.BlockPos(0, 64, 0),
                        new net.minecraft.core.BlockPos(4, 64, 0),
                        new net.minecraft.core.BlockPos(10, 64, 0)
                )
        );

        RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

        assertTrue(chosen.found());
        assertEquals(RoadAutoRouteService.PathSource.ROAD_NETWORK, chosen.source());
        assertEquals(3, chosen.path().size());
    }

    @Test
    void keepsDirectGroundResolutionWhenHybridResolutionFails() {
        RoadAutoRouteService.RouteResolution direct = new RoadAutoRouteService.RouteResolution(
                RoadAutoRouteService.PathSource.LAND_TERRAIN,
                List.of(new net.minecraft.core.BlockPos(0, 64, 0), new net.minecraft.core.BlockPos(6, 69, 0))
        );
        RoadAutoRouteService.RouteResolution hybrid = RoadAutoRouteService.RouteResolution.none();

        RoadAutoRouteService.RouteResolution chosen = RoadAutoRouteService.preferResolutionForTest(direct, hybrid);

        assertTrue(chosen.found());
        assertEquals(RoadAutoRouteService.PathSource.LAND_TERRAIN, chosen.source());
        assertEquals(2, chosen.path().size());
    }
}
