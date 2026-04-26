package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMapCorridorPlannerTest {
    @Test
    void manualNodesTakePriorityAndRoughPathContinuesToDestination() {
        RoadMapCorridorPlanner planner = new RoadMapCorridorPlanner(128, MapLod.LOD_4);
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(384, 70, 0);
        List<BlockPos> manualNodes = List.of(start, new BlockPos(128, 65, 0));

        RoadMapCorridorPlan plan = planner.plan(start, destination, manualNodes, 128, 256);

        assertFalse(plan.regions().isEmpty());
        assertTrue(plan.regions().stream().anyMatch(region -> region.priority() == RoadMapRegionPriority.CURRENT));
        assertTrue(plan.regions().stream().anyMatch(region -> region.priority() == RoadMapRegionPriority.MANUAL_ROUTE));
        assertTrue(plan.regions().stream().anyMatch(region -> region.priority() == RoadMapRegionPriority.ROUGH_PATH));
        assertTrue(plan.regions().stream().anyMatch(region -> region.region().containsWorldXZ(destination.getX(), destination.getZ())));
    }

    @Test
    void cacheScopeSeparatesWorldsAndClearsActiveMemoryOnSwitch() {
        RoadMapSnapshotCache cache = new RoadMapSnapshotCache();
        RoadMapCacheKey firstWorldKey = new RoadMapCacheKey("world_a", "minecraft:overworld", 0, 0, 128, MapLod.LOD_4);
        RoadMapCacheKey secondWorldKey = new RoadMapCacheKey("world_b", "minecraft:overworld", 0, 0, 128, MapLod.LOD_4);
        RoadMapSnapshot snapshot = new RoadMapSnapshot(1L, RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4), List.of(), new int[0]);

        cache.activateWorld("world_a");
        cache.put(firstWorldKey, snapshot);
        assertTrue(cache.get(firstWorldKey).isPresent());

        cache.activateWorld("world_b");
        assertTrue(cache.get(firstWorldKey).isEmpty());
        cache.put(secondWorldKey, snapshot);
        assertEquals(1, cache.activeEntryCount());
    }
}
