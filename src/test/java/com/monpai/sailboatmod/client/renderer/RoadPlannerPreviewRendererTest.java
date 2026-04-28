package com.monpai.sailboatmod.client.renderer;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

class RoadPlannerPreviewRendererTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    @Test
    void previewRenderListSkipsAirAndDedupesByPositionAndState() {
        RoadPlannerClientHooks.PreviewGhostBlock first = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(1, 64, 1),
                Blocks.STONE_BRICKS.defaultBlockState()
        );
        RoadPlannerClientHooks.PreviewGhostBlock samePositionDifferentState = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(1, 64, 1),
                Blocks.SMOOTH_STONE.defaultBlockState()
        );
        RoadPlannerClientHooks.PreviewGhostBlock later = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(2, 64, 1),
                Blocks.STONE_BRICKS.defaultBlockState()
        );

        List<RoadPlannerClientHooks.PreviewGhostBlock> filtered = RoadPlannerPreviewRenderer.previewRenderListForTest(
                List.of(
                        first,
                        new RoadPlannerClientHooks.PreviewGhostBlock(new BlockPos(9, 64, 9), Blocks.AIR.defaultBlockState()),
                        first,
                        samePositionDifferentState,
                        later
                ),
                new BlockPos(1, 64, 1),
                100.0D,
                10
        );

        assertEquals(List.of(first, samePositionDifferentState, later), filtered);
    }

    @Test
    void previewRenderListCullsByFocusDistanceAndCapsPreservingOrder() {
        RoadPlannerClientHooks.PreviewGhostBlock first = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(0, 64, 0),
                Blocks.STONE_BRICKS.defaultBlockState()
        );
        RoadPlannerClientHooks.PreviewGhostBlock second = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(1, 64, 0),
                Blocks.STONE_BRICKS.defaultBlockState()
        );
        RoadPlannerClientHooks.PreviewGhostBlock far = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(4, 64, 0),
                Blocks.STONE_BRICKS.defaultBlockState()
        );
        RoadPlannerClientHooks.PreviewGhostBlock third = new RoadPlannerClientHooks.PreviewGhostBlock(
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICKS.defaultBlockState()
        );

        List<RoadPlannerClientHooks.PreviewGhostBlock> filtered = RoadPlannerPreviewRenderer.previewRenderListForTest(
                List.of(first, second, far, third),
                new BlockPos(0, 64, 0),
                2.0D,
                2
        );

        assertEquals(List.of(first, second), filtered);
    }
    @Test
    void blockBoxesUseExactCameraOffset() {
        RoadPlannerPreviewRenderer.PreviewBox box = RoadPlannerPreviewRenderer.previewBoxForTest(
                new BlockPos(10, 64, 20),
                new Vec3(9.75D, 63.50D, 19.25D)
        );

        assertEquals(0.25D, box.minX(), 1.0E-6D);
        assertEquals(0.50D, box.minY(), 1.0E-6D);
        assertEquals(0.75D, box.minZ(), 1.0E-6D);
        assertEquals(1.25D, box.maxX(), 1.0E-6D);
        assertEquals(1.50D, box.maxY(), 1.0E-6D);
        assertEquals(1.75D, box.maxZ(), 1.0E-6D);
    }

    @Test
    void highlightBoxesApplyInsetAroundExactCameraOffset() {
        RoadPlannerPreviewRenderer.PreviewBox box = RoadPlannerPreviewRenderer.highlightBoxForTest(
                new BlockPos(10, 64, 20),
                new Vec3(9.75D, 63.50D, 19.25D),
                0.02D
        );

        assertEquals(0.23D, box.minX(), 1.0E-6D);
        assertEquals(0.48D, box.minY(), 1.0E-6D);
        assertEquals(0.73D, box.minZ(), 1.0E-6D);
        assertEquals(1.27D, box.maxX(), 1.0E-6D);
        assertEquals(1.52D, box.maxY(), 1.0E-6D);
        assertEquals(1.77D, box.maxZ(), 1.0E-6D);
    }

    @Test
    void roadPlannerPreviewDisablesFilledBoxesForStability() {
        assertFalse(RoadPlannerPreviewRenderer.rendersFilledBoxesForTest());
    }

    @Test
    void pathSegmentBoxSpansBetweenAdjacentPreviewNodes() {
        RoadPlannerPreviewRenderer.PreviewBox box = RoadPlannerPreviewRenderer.pathSegmentBoxForTest(
                new BlockPos(10, 64, 20),
                new BlockPos(11, 64, 20),
                new Vec3(9.75D, 63.50D, 19.25D),
                0.08D
        );

        assertTrue(box.maxX() > box.minX());
        assertEquals(0.92D, box.minY(), 1.0E-6D);
        assertEquals(1.08D, box.maxY(), 1.0E-6D);
        assertEquals(1.17D, box.minZ(), 1.0E-6D);
        assertEquals(1.33D, box.maxZ(), 1.0E-6D);
    }

    @Test
    void planningHudLabelIncludesStageAndPercent() {
        RoadPlannerClientHooks.PlanningProgressState state = new RoadPlannerClientHooks.PlanningProgressState(
                21L,
                "Alpha",
                "Beta",
                "sampling_terrain",
                "采样地形",
                18,
                18,
                45,
                SyncManualRoadPlanningProgressPacket.Status.RUNNING,
                0L,
                250L,
                Long.MAX_VALUE
        );

        assertEquals("道路规划中: 采样地形 18%", RoadPlannerPreviewRenderer.planningHeadlineForTest(state).getString());
    }

    @Test
    void planningHudUsesTerminalColorForFailedState() {
        assertEquals(
                0xFFF08A8A,
                RoadPlannerPreviewRenderer.planningStatusColorForTest(SyncManualRoadPlanningProgressPacket.Status.FAILED)
        );
    }
}
