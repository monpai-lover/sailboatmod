package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
