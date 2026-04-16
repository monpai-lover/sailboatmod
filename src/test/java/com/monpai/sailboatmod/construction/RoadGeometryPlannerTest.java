package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGeometryPlannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void corridorPlanDoesNotCreateVerticalWallBetweenSlopeSlices() {
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0)
                ),
                List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 67, 0),
                                RoadCorridorPlan.SegmentKind.BRIDGE_HEAD,
                                List.of(new BlockPos(0, 67, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                1,
                                new BlockPos(1, 66, 0),
                                RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH,
                                List.of(new BlockPos(1, 66, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                2,
                                new BlockPos(2, 65, 0),
                                RoadCorridorPlan.SegmentKind.APPROACH_RAMP,
                                List.of(new BlockPos(2, 65, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                3,
                                new BlockPos(3, 63, 0),
                                RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                                List.of(new BlockPos(3, 63, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                null,
                true
        );

        RoadGeometryPlanner.RoadGeometryPlan geometry = RoadGeometryPlanner.plan(
                corridorPlan,
                pos -> Blocks.STONE_BRICKS.defaultBlockState()
        );

        assertFalse(
                geometry.ghostBlocks().stream().anyMatch(block ->
                        block.pos().getX() == 3
                                && block.pos().getZ() == 0
                                && block.pos().getY() == 64
                                && block.state().is(Blocks.STONE_BRICKS)
                ),
                () -> geometry.ghostBlocks().toString()
        );
        assertTrue(
                geometry.ghostBlocks().stream().anyMatch(block ->
                        block.pos().getX() == 2
                                && block.pos().getZ() == 0
                                && block.pos().getY() == 65
                ),
                () -> geometry.ghostBlocks().toString()
        );
    }

    @Test
    void turningBridgeApproachDoesNotSnapAnySlopePreviewColumnBackToGround() {
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 65, 0),
                        new BlockPos(2, 66, 1),
                        new BlockPos(2, 67, 2),
                        new BlockPos(3, 67, 3)
                ),
                List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 64, 0),
                                RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                                List.of(new BlockPos(0, 64, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                1,
                                new BlockPos(1, 64, 0),
                                RoadCorridorPlan.SegmentKind.APPROACH_RAMP,
                                List.of(new BlockPos(1, 64, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                2,
                                new BlockPos(2, 65, 0),
                                RoadCorridorPlan.SegmentKind.APPROACH_RAMP,
                                List.of(new BlockPos(2, 65, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                3,
                                new BlockPos(2, 66, 1),
                                RoadCorridorPlan.SegmentKind.BRIDGE_HEAD,
                                List.of(new BlockPos(2, 66, 1)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                4,
                                new BlockPos(2, 67, 2),
                                RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH,
                                List.of(new BlockPos(2, 67, 2)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                5,
                                new BlockPos(3, 67, 3),
                                RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN,
                                List.of(new BlockPos(3, 67, 3)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                null,
                true
        );

        List<BlockPos> slopeSlice = RoadGeometryPlanner.slicePositions(corridorPlan, 3);

        assertFalse(slopeSlice.isEmpty());
        assertEquals(
                66,
                slopeSlice.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                () -> slopeSlice.toString()
        );
    }
}
