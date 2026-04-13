package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstructionManagerRoadLinkTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void previewRoadConnectionPrefersRoadTargetBonus() {
        StructureConstructionManager.PreviewRoadConnection road = new StructureConstructionManager.PreviewRoadConnection(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0)),
                StructureConstructionManager.PreviewRoadTargetKind.ROAD,
                new BlockPos(4, 64, 0)
        );
        StructureConstructionManager.PreviewRoadConnection structure = new StructureConstructionManager.PreviewRoadConnection(
                List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 1)),
                StructureConstructionManager.PreviewRoadTargetKind.STRUCTURE,
                new BlockPos(3, 64, 1)
        );

        StructureConstructionManager.PreviewRoadConnection chosen =
                StructureConstructionManager.choosePreviewConnectionForTest(List.of(structure, road), 0);

        assertEquals(StructureConstructionManager.PreviewRoadTargetKind.ROAD, chosen.targetKind());
    }

    @Test
    void manualBridgeLinkProducesOwnedSupportAndLightingArtifacts() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 3))
        );

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.supportPositions().isEmpty()));
        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.pierLightPositions().isEmpty()));
        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.railingLightPositions().isEmpty()));
    }

    @Test
    void waterBridgeSupportsOnlyAppearAtPierAnchorColumns() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 3))
        );

        List<RoadCorridorPlan.CorridorSlice> supportSlices = plan.corridorPlan().slices().stream()
                .filter(slice -> !slice.supportPositions().isEmpty())
                .toList();

        assertEquals(1, supportSlices.size());
        assertEquals(3, supportSlices.get(0).index());
    }

    @Test
    void waterBridgeUsesStoneDeckAndStonePierMaterials() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 3))
        );

        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICKS)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.COBBLESTONE_WALL)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_SLAB)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)));
    }
}
