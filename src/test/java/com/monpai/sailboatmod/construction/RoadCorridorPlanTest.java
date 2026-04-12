package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadCorridorPlanTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void segmentKindDefinesRequiredValues() {
        assertEquals(RoadCorridorPlan.SegmentKind.TOWN_CONNECTION, RoadCorridorPlan.SegmentKind.valueOf("TOWN_CONNECTION"));
        assertEquals(RoadCorridorPlan.SegmentKind.LAND_APPROACH, RoadCorridorPlan.SegmentKind.valueOf("LAND_APPROACH"));
        assertEquals(RoadCorridorPlan.SegmentKind.APPROACH_RAMP, RoadCorridorPlan.SegmentKind.valueOf("APPROACH_RAMP"));
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, RoadCorridorPlan.SegmentKind.valueOf("BRIDGE_HEAD"));
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, RoadCorridorPlan.SegmentKind.valueOf("NAVIGABLE_MAIN_SPAN"));
        assertEquals(RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN, RoadCorridorPlan.SegmentKind.valueOf("NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN"));
        assertEquals(RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH, RoadCorridorPlan.SegmentKind.valueOf("ELEVATED_APPROACH"));
    }

    @Test
    void corridorSliceCapturesClearanceAndElevatedApproachSegmentKinds() {
        RoadCorridorPlan plan = new RoadCorridorPlan(
                List.of(new BlockPos(0, 64, 0)),
                List.of(new RoadCorridorPlan.CorridorSlice(
                        0,
                        new BlockPos(0, 70, 0),
                        RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH,
                        List.of(new BlockPos(0, 70, 0)),
                        List.of(new BlockPos(0, 69, 0)),
                        List.of(new BlockPos(0, 71, 0), new BlockPos(0, 72, 0)),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                null,
                true
        );

        assertEquals(RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH, plan.slices().get(0).segmentKind());
        assertEquals(List.of(new BlockPos(0, 71, 0), new BlockPos(0, 72, 0)), plan.slices().get(0).clearancePositions());
    }

    @Test
    void corridorSliceDefaultsClearanceToEmptyList() {
        RoadCorridorPlan.CorridorSlice slice = new RoadCorridorPlan.CorridorSlice(
                0,
                new BlockPos(0, 65, 0),
                RoadCorridorPlan.SegmentKind.APPROACH_RAMP,
                List.of(new BlockPos(0, 65, 0)),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(slice.clearancePositions().isEmpty());
    }

    @Test
    void constructorCopiesMutableInputsAndPositions() {
        BlockPos.MutableBlockPos mutablePathPoint = new BlockPos.MutableBlockPos(0, 64, 0);
        BlockPos.MutableBlockPos mutableDeckCenter = new BlockPos.MutableBlockPos(1, 64, 0);
        BlockPos.MutableBlockPos mutableSurfacePoint = new BlockPos.MutableBlockPos(2, 64, 0);
        BlockPos.MutableBlockPos mutableExcavationPoint = new BlockPos.MutableBlockPos(2, 63, 0);
        BlockPos.MutableBlockPos mutableRailingLightPoint = new BlockPos.MutableBlockPos(2, 65, 0);
        BlockPos.MutableBlockPos mutableSupportPoint = new BlockPos.MutableBlockPos(2, 62, 0);
        BlockPos.MutableBlockPos mutablePierLightPoint = new BlockPos.MutableBlockPos(2, 61, 0);
        BlockPos.MutableBlockPos mutableChannelMin = new BlockPos.MutableBlockPos(4, 62, -2);
        BlockPos.MutableBlockPos mutableChannelMax = new BlockPos.MutableBlockPos(8, 69, 2);
        List<BlockPos> mutablePath = new ArrayList<>(List.of(mutablePathPoint));
        List<BlockPos> mutableSurface = new ArrayList<>(List.of(mutableSurfacePoint));
        List<BlockPos> mutableExcavation = new ArrayList<>(List.of(mutableExcavationPoint));
        List<BlockPos> mutableRailingLights = new ArrayList<>(List.of(mutableRailingLightPoint));
        List<BlockPos> mutableSupports = new ArrayList<>(List.of(mutableSupportPoint));
        List<BlockPos> mutablePierLights = new ArrayList<>(List.of(mutablePierLightPoint));
        List<RoadCorridorPlan.CorridorSlice> mutableSlices = new ArrayList<>();
        mutableSlices.add(new RoadCorridorPlan.CorridorSlice(
                0,
                mutableDeckCenter,
                RoadCorridorPlan.SegmentKind.TOWN_CONNECTION,
                mutableSurface,
                mutableExcavation,
                mutableRailingLights,
                mutableSupports,
                mutablePierLights
        ));

        RoadCorridorPlan plan = new RoadCorridorPlan(
                mutablePath,
                mutableSlices,
                new RoadCorridorPlan.NavigationChannel(mutableChannelMin, mutableChannelMax),
                true
        );

        mutablePath.add(new BlockPos(99, 99, 99));
        mutableSlices.clear();
        mutableSurface.add(new BlockPos(100, 64, 0));
        mutableExcavation.add(new BlockPos(100, 63, 0));
        mutableRailingLights.add(new BlockPos(100, 65, 0));
        mutableSupports.add(new BlockPos(100, 62, 0));
        mutablePierLights.add(new BlockPos(100, 61, 0));
        mutablePathPoint.set(20, 20, 20);
        mutableDeckCenter.set(21, 21, 21);
        mutableSurfacePoint.set(22, 22, 22);
        mutableExcavationPoint.set(23, 23, 23);
        mutableRailingLightPoint.set(24, 24, 24);
        mutableSupportPoint.set(25, 25, 25);
        mutablePierLightPoint.set(26, 26, 26);
        mutableChannelMin.set(27, 27, 27);
        mutableChannelMax.set(28, 28, 28);

        assertEquals(1, plan.centerPath().size());
        assertEquals(new BlockPos(0, 64, 0), plan.centerPath().get(0));
        assertEquals(1, plan.slices().size());
        assertEquals(1, plan.slices().get(0).surfacePositions().size());
        assertEquals(new BlockPos(1, 64, 0), plan.slices().get(0).deckCenter());
        assertEquals(new BlockPos(2, 64, 0), plan.slices().get(0).surfacePositions().get(0));
        assertEquals(1, plan.slices().get(0).excavationPositions().size());
        assertEquals(new BlockPos(2, 63, 0), plan.slices().get(0).excavationPositions().get(0));
        assertEquals(1, plan.slices().get(0).railingLightPositions().size());
        assertEquals(new BlockPos(2, 65, 0), plan.slices().get(0).railingLightPositions().get(0));
        assertEquals(1, plan.slices().get(0).supportPositions().size());
        assertEquals(new BlockPos(2, 62, 0), plan.slices().get(0).supportPositions().get(0));
        assertEquals(1, plan.slices().get(0).pierLightPositions().size());
        assertEquals(new BlockPos(2, 61, 0), plan.slices().get(0).pierLightPositions().get(0));
        assertEquals(new BlockPos(4, 62, -2), plan.navigationChannel().min());
        assertEquals(new BlockPos(8, 69, 2), plan.navigationChannel().max());
        assertNotSame(plan.centerPath(), mutablePath);
        assertNotSame(plan.slices(), mutableSlices);
        assertTrue(plan.valid());
    }

    @Test
    void constructorRejectsNullInputs() {
        BlockPos center = new BlockPos(1, 64, 0);
        RoadCorridorPlan.CorridorSlice validSlice = new RoadCorridorPlan.CorridorSlice(
                0,
                center,
                RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                List.of(center),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        RoadCorridorPlan.NavigationChannel channel = new RoadCorridorPlan.NavigationChannel(center, center.above());

        assertThrows(NullPointerException.class, () -> new RoadCorridorPlan(null, List.of(validSlice), channel, true));
        assertThrows(NullPointerException.class, () -> new RoadCorridorPlan(List.of(center), null, channel, true));
        assertThrows(NullPointerException.class, () -> new RoadCorridorPlan(List.of(center), List.of((RoadCorridorPlan.CorridorSlice) null), channel, true));
        assertThrows(NullPointerException.class, () -> new RoadCorridorPlan(List.of((BlockPos) null), List.of(validSlice), channel, true));
        assertThrows(NullPointerException.class, () -> new RoadCorridorPlan.CorridorSlice(
                0,
                null,
                RoadCorridorPlan.SegmentKind.BRIDGE_HEAD,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        assertThrows(NullPointerException.class, () -> new RoadCorridorPlan.NavigationChannel(null, center));
    }

    @Test
    void constructorAllowsAbsentNavigationChannel() {
        BlockPos center = new BlockPos(1, 64, 0);
        RoadCorridorPlan.CorridorSlice slice = new RoadCorridorPlan.CorridorSlice(
                0,
                center,
                RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                List.of(center),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        RoadCorridorPlan plan = new RoadCorridorPlan(List.of(center), List.of(slice), null, true);

        assertNull(plan.navigationChannel());
    }

    @Test
    void roadPlacementPlanCarriesCorridorPlanAndDefaultsNullInLegacyConstructors() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(1, 64, 0);
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                List.of(start, end),
                List.of(new RoadCorridorPlan.CorridorSlice(
                        0,
                        start,
                        RoadCorridorPlan.SegmentKind.TOWN_CONNECTION,
                        List.of(start),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                new RoadCorridorPlan.NavigationChannel(start, end.above(5)),
                true
        );

        RoadPlacementPlan withCorridor = new RoadPlacementPlan(
                List.of(start, end),
                start,
                start,
                end,
                end,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                start,
                end,
                start,
                corridorPlan
        );

        RoadPlacementPlan legacy = new RoadPlacementPlan(
                List.of(start, end),
                start,
                start,
                end,
                end,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                start,
                end,
                start
        );

        assertEquals(corridorPlan, withCorridor.corridorPlan());
        assertNull(legacy.corridorPlan());
    }

    @Test
    void roadPlacementPlanToleratesNullGhostBlocksConsistently() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(1, 64, 0);
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = new ArrayList<>();
        ghostBlocks.add(null);

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(start, end),
                start,
                start,
                end,
                end,
                ghostBlocks,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                start,
                end,
                start
        );

        assertTrue(plan.ghostBlocks().isEmpty());
    }

    @Test
    void legacyConstructorDefaultsOwnedBlocksFromGhostBlocksUsingImmutableCopies() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(1, 64, 0);
        BlockPos.MutableBlockPos mutableGhostPos = new BlockPos.MutableBlockPos(2, 65, 2);
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(mutableGhostPos, Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(start, end),
                start,
                start,
                end,
                end,
                ghostBlocks,
                List.of(),
                List.of(),
                start,
                end,
                start
        );

        mutableGhostPos.set(99, 70, 99);

        assertEquals(1, plan.ownedBlocks().size());
        assertEquals(new BlockPos(2, 65, 2), plan.ownedBlocks().get(0));
    }

    @Test
    void roadPlacementPlanGhostBlocksAndBuildStepsRemainStableFromMutableInputs() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(1, 64, 0);
        BlockPos.MutableBlockPos mutableGhostPos = new BlockPos.MutableBlockPos(3, 65, 3);
        BlockPos.MutableBlockPos mutableStepPos = new BlockPos.MutableBlockPos(4, 66, 4);
        RoadGeometryPlanner.GhostRoadBlock ghostBlock = new RoadGeometryPlanner.GhostRoadBlock(
                mutableGhostPos,
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        RoadGeometryPlanner.RoadBuildStep buildStep = new RoadGeometryPlanner.RoadBuildStep(
                7,
                mutableStepPos,
                Blocks.STONE.defaultBlockState()
        );
        List<RoadGeometryPlanner.GhostRoadBlock> mutableGhostBlocks = new ArrayList<>(List.of(ghostBlock));
        List<RoadGeometryPlanner.RoadBuildStep> mutableBuildSteps = new ArrayList<>(List.of(buildStep));

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(start, end),
                start,
                start,
                end,
                end,
                mutableGhostBlocks,
                mutableBuildSteps,
                List.of(),
                List.of(),
                List.of(),
                start,
                end,
                start
        );

        mutableGhostPos.set(30, 80, 30);
        mutableStepPos.set(40, 90, 40);
        mutableGhostBlocks.clear();
        mutableBuildSteps.clear();

        assertEquals(1, plan.ghostBlocks().size());
        assertEquals(1, plan.buildSteps().size());
        assertEquals(new BlockPos(3, 65, 3), plan.ghostBlocks().get(0).pos());
        assertEquals(new BlockPos(4, 66, 4), plan.buildSteps().get(0).pos());
        assertNotSame(ghostBlock, plan.ghostBlocks().get(0));
        assertNotSame(buildStep, plan.buildSteps().get(0));
    }
}
