package com.monpai.sailboatmod.road.pathfinding.cache;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSamplingCacheTest {
    @Test
    void allowedColumnsConstrainSearchWithoutDroppingExplicitBlockMask() {
        Set<Long> blocked = Set.of(RoadCoreExclusion.columnKey(4, 0));
        Set<Long> allowed = Set.of(
                RoadCoreExclusion.columnKey(0, 0),
                RoadCoreExclusion.columnKey(4, 0)
        );

        TerrainSamplingCache cache = new TerrainSamplingCache(
                null,
                PathfindingConfig.SamplingPrecision.NORMAL,
                blocked,
                allowed
        );

        assertFalse(cache.isBlocked(0, 0));
        assertTrue(cache.isBlocked(4, 0), "explicit obstacle mask must still win inside corridor");
        assertTrue(cache.isBlocked(8, 0), "outside corridor must be blocked during fine pathfinding");
    }
}
