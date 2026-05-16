package com.monpai.sailboatmod.roadplanner.obstacle;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.nation.model.PlacedStructureRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerObstacleMaskTest {
    @Test
    void coreMaskUsesDefaultRadiusAroundTownAndNationCoreColumns() {
        Set<Long> columns = RoadPlannerObstacleMask.coreColumns(
                List.of(new BlockPos(10, 64, 20)),
                List.of(new BlockPos(-5, 70, 8))
        );

        assertTrue(columns.contains(RoadCoreExclusion.columnKey(10 + RoadCoreExclusion.DEFAULT_RADIUS, 20)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(10 - RoadCoreExclusion.DEFAULT_RADIUS, 20)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(-5, 8 + RoadCoreExclusion.DEFAULT_RADIUS)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(-5, 8 - RoadCoreExclusion.DEFAULT_RADIUS)));
        assertFalse(columns.contains(RoadCoreExclusion.columnKey(10 + RoadCoreExclusion.DEFAULT_RADIUS + 1, 20)));
    }

    @Test
    void structureFootprintIncludesOneBlockMargin() {
        PlacedStructureRecord structure = new PlacedStructureRecord(
                "structure",
                "nation",
                "town",
                "cottage",
                "minecraft:overworld",
                new BlockPos(30, 64, 40).asLong(),
                3,
                5,
                2,
                0L,
                1,
                true,
                0
        );

        Set<Long> columns = RoadPlannerObstacleMask.structureColumns(List.of(structure), "minecraft:overworld");

        assertTrue(columns.contains(RoadCoreExclusion.columnKey(29, 39)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(33, 42)));
        assertTrue(columns.contains(RoadCoreExclusion.columnKey(30, 40)));
        assertFalse(columns.contains(RoadCoreExclusion.columnKey(28, 39)));
        assertFalse(columns.contains(RoadCoreExclusion.columnKey(33, 43)));
    }

    @Test
    void withoutEndpointsRemovesOnlyRouteEndpoints() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(10, 64, 10);
        BlockPos middle = new BlockPos(5, 64, 5);
        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(Set.of(
                RoadCoreExclusion.columnKey(source.getX(), source.getZ()),
                RoadCoreExclusion.columnKey(target.getX(), target.getZ()),
                RoadCoreExclusion.columnKey(middle.getX(), middle.getZ())
        ));

        RoadPlannerObstacleMask routeMask = mask.withoutEndpoints(source, target);

        assertFalse(routeMask.isBlocked(source));
        assertFalse(routeMask.isBlocked(target));
        assertTrue(routeMask.isBlocked(middle));
    }

    @Test
    void pathTouchesBlockedColumnScansBetweenPathNodes() {
        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(Set.of(
                RoadCoreExclusion.columnKey(4, 0)
        ));

        assertTrue(mask.pathTouchesBlockedColumn(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0)
        )));
    }

    @Test
    void pathTouchesBlockedColumnUsesSupercoverForDiagonalSegments() {
        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(Set.of(
                RoadCoreExclusion.columnKey(4, 2)
        ));

        assertTrue(mask.pathTouchesBlockedColumn(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(6, 64, 4)
        )));
    }
}
