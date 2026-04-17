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

    @Test
    void sharpPierBridgeTurnGeometryPreservesRepairedOuterShoulderBand() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 68, 1),
                new BlockPos(4, 68, 2),
                new BlockPos(4, 66, 3),
                new BlockPos(4, 64, 4)
        );
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = List.of(
                new RoadBridgePlanner.BridgeSpanPlan(
                        1,
                        5,
                        RoadBridgePlanner.BridgeMode.PIER_BRIDGE,
                        List.of(
                                new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 66, 0), new BlockPos(1, 63, 0), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                                new RoadBridgePlanner.BridgePierNode(3, new BlockPos(3, 68, 1), new BlockPos(3, 40, 1), 68, RoadBridgePlanner.BridgeNodeRole.PIER),
                                new RoadBridgePlanner.BridgePierNode(5, new BlockPos(4, 66, 3), new BlockPos(4, 63, 3), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                        ),
                        List.of(
                                new RoadBridgePlanner.BridgeDeckSegment(1, 3, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_UP, 66, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(3, 3, RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL, 68, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(3, 5, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_DOWN, 68, 66)
                        ),
                        68,
                        false,
                        true
                )
        );
        RoadCorridorPlan corridorPlan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {65, 66, 68, 68, 68, 66, 65});

        RoadGeometryPlanner.RoadGeometryPlan geometryPlan = RoadGeometryPlanner.plan(
                corridorPlan,
                pos -> Blocks.STONE_BRICKS.defaultBlockState()
        );
        List<BlockPos> geometryPositions = geometryPlan.ghostBlocks().stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .toList();

        assertTrue(
                geometryPositions.containsAll(corridorPlan.slices().get(2).surfacePositions()),
                () -> "slice2 missing repaired positions from geometry: surface="
                        + corridorPlan.slices().get(2).surfacePositions() + " geometry=" + geometryPositions
        );
        assertTrue(
                geometryPositions.containsAll(corridorPlan.slices().get(3).surfacePositions()),
                () -> geometryPlan.ghostBlocks().toString()
        );
    }
}
