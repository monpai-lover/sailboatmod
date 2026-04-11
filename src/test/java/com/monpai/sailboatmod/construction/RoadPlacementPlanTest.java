package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlacementPlanTest {
    @Test
    void roadPlacementPlanRetainsNavigableWaterBridgeRanges() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0)
        );
        BlockPos start = centerPath.get(0);
        BlockPos end = centerPath.get(1);
        RoadPlacementPlan plan = new RoadPlacementPlan(
                centerPath,
                start,
                start,
                end,
                end,
                List.of(new RoadGeometryPlanner.GhostRoadBlock(start.above(), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, start.above(), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadPlacementPlan.BridgeRange(0, 1)),
                List.of(new RoadPlacementPlan.BridgeRange(0, 1)),
                List.of(start.above()),
                start.above(),
                end.above(),
                start.above()
        );

        assertEquals(1, plan.bridgeRanges().size());
        assertEquals(1, plan.navigableWaterBridgeRanges().size());
    }
}
