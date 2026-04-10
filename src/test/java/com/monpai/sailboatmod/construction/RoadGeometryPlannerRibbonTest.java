package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGeometryPlannerRibbonTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void straightRoadUsesSevenWideRibbon() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)
        );

        RoadGeometryPlanner.RibbonSlice ribbon = RoadGeometryPlanner.buildRibbonSlice(centerPath, 1);

        assertEquals(3, ribbon.insideHalfWidth());
        assertEquals(3, ribbon.outsideHalfWidth());
        assertEquals(7, ribbon.totalWidth());
        assertEquals(7, ribbon.columns().size());
        assertTrue(ribbon.columns().contains(new BlockPos(1, 0, 0)));
    }

    @Test
    void sharpTurnWidensOutsideEdgeWithoutGrowingInsideEdge() {
        List<BlockPos> straightCenterPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)
        );
        List<BlockPos> turningCenterPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1)
        );

        RoadGeometryPlanner.RibbonSlice straight = RoadGeometryPlanner.buildRibbonSlice(straightCenterPath, 1);
        RoadGeometryPlanner.RibbonSlice turning = RoadGeometryPlanner.buildRibbonSlice(turningCenterPath, 1);

        assertEquals(straight.insideHalfWidth(), turning.insideHalfWidth());
        assertEquals(straight.outsideHalfWidth() + 1, turning.outsideHalfWidth());
        assertEquals(8, turning.totalWidth());
        assertTrue(turning.outsideHalfWidth() > turning.insideHalfWidth());
    }

    @Test
    void reversalKeepsRibbonMetadataConsistentWithFootprint() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(0, 64, 0)
        );

        RoadGeometryPlanner.RibbonSlice ribbon = RoadGeometryPlanner.buildRibbonSlice(centerPath, 1);

        assertEquals(ribbon.insideHalfWidth(), ribbon.outsideHalfWidth());
        assertEquals(ribbon.totalWidth(), ribbon.columns().size());
        assertEquals(7, ribbon.totalWidth());
    }

    @Test
    void diagonalStraightRibbonColumnsStayFourConnected() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 2)
        );

        RoadGeometryPlanner.RibbonSlice ribbon = RoadGeometryPlanner.buildRibbonSlice(centerPath, 1);

        assertTrue(isFourConnected(ribbon.columns()));
    }

    private static boolean isFourConnected(List<BlockPos> columns) {
        if (columns.isEmpty()) {
            return true;
        }
        Set<BlockPos> remaining = new LinkedHashSet<>(columns);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos first = columns.get(0);
        queue.add(first);
        remaining.remove(first);

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            BlockPos[] neighbors = new BlockPos[]{
                    new BlockPos(current.getX() + 1, current.getY(), current.getZ()),
                    new BlockPos(current.getX() - 1, current.getY(), current.getZ()),
                    new BlockPos(current.getX(), current.getY(), current.getZ() + 1),
                    new BlockPos(current.getX(), current.getY(), current.getZ() - 1)
            };
            for (BlockPos neighbor : neighbors) {
                if (remaining.remove(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return remaining.isEmpty();
    }
}
