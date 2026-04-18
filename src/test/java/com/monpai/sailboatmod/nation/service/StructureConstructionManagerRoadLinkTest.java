package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadBridgePlanner;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void structureRoadLinkUsesAsyncPlannerSubmissionHook() {
        assertTrue(StructureConstructionManager.usesAsyncRoadPlanningForTest());
    }

    @Test
    void manualBridgeLinkProducesOwnedSupportAndLightingArtifacts() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.supportPositions().isEmpty()));
        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.railingLightPositions().isEmpty()));
        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.pierLightPositions().isEmpty()));
    }

    @Test
    void bridgeBuildStepsStayPhaseOrderedSupportDeckDecor() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        List<RoadGeometryPlanner.RoadBuildPhase> phases = plan.buildSteps().stream()
                .map(RoadGeometryPlanner.RoadBuildStep::phase)
                .toList();

        assertEquals(
                phases.stream().sorted(Comparator.naturalOrder()).toList(),
                phases
        );
    }

    @Test
    void longBridgeBuildStepsIncludeHeadsSupportsAndLights() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        List<BlockPos> buildStepPositions = plan.buildSteps().stream()
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .toList();
        List<BlockPos> expectedSupportPositions = plan.corridorPlan().slices().stream()
                .flatMap(slice -> slice.supportPositions().stream())
                .toList();
        List<BlockPos> expectedLightPositions = plan.corridorPlan().slices().stream()
                .flatMap(slice -> java.util.stream.Stream.concat(
                        slice.railingLightPositions().stream(),
                        slice.pierLightPositions().stream()
                ))
                .toList();

        assertFalse(expectedSupportPositions.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertFalse(expectedLightPositions.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertTrue(buildStepPositions.containsAll(expectedSupportPositions), buildStepPositions.toString());
        assertTrue(buildStepPositions.containsAll(expectedLightPositions), buildStepPositions.toString());
    }

    @Test
    void visibleBridgePierCapsAreScheduledWithinFirstTwoBuildBatches() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        int earlyStepCount = invokeRoadBuildBatchSizes(plan).stream()
                .limit(2)
                .mapToInt(Integer::intValue)
                .sum();
        Map<Long, BlockPos> topSupportByColumn = new LinkedHashMap<>();
        for (RoadCorridorPlan.CorridorSlice slice : plan.corridorPlan().slices()) {
            for (BlockPos supportPos : slice.supportPositions()) {
                long key = columnKey(supportPos.getX(), supportPos.getZ());
                BlockPos existing = topSupportByColumn.get(key);
                if (existing == null || supportPos.getY() > existing.getY()) {
                    topSupportByColumn.put(key, supportPos);
                }
            }
        }
        List<BlockPos> earlyBuildPositions = plan.buildSteps().stream()
                .limit(earlyStepCount)
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .toList();

        assertFalse(topSupportByColumn.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertTrue(earlyBuildPositions.containsAll(topSupportByColumn.values()),
                () -> "early=" + earlyBuildPositions + ", visibleSupports=" + topSupportByColumn.values());
    }

    @Test
    void longBridgePlacementArtifactsOwnBridgeSupportAndFoundationCoverage() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        List<BlockPos> owned = invokeRoadOwnedBlocks(null, plan);
        List<BlockPos> supports = plan.corridorPlan().slices().stream()
                .flatMap(slice -> slice.supportPositions().stream())
                .toList();

        assertFalse(supports.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertTrue(owned.containsAll(supports), owned.toString());
    }

    @Test
    void failedStructuralRoadStepsPreventLaterDecorPlacement() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.blockStates.put(new BlockPos(0, 65, 0).asLong(), Blocks.CHEST.defaultBlockState());

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0)),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICKS.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 66, 0), Blocks.COBBLESTONE_WALL.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICKS.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.SUPPORT),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK),
                        new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(0, 66, 0), Blocks.COBBLESTONE_WALL.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECOR)
                ),
                List.of(),
                List.of(),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0)
        );

        Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|a|b", plan), 3);

        assertEquals(Blocks.AIR.defaultBlockState(), level.getBlockState(new BlockPos(0, 64, 0)));
        assertEquals(Blocks.CHEST.defaultBlockState(), level.getBlockState(new BlockPos(0, 65, 0)));
        assertEquals(Blocks.AIR.defaultBlockState(), level.getBlockState(new BlockPos(0, 66, 0)));
        assertEquals(0, readRecordComponentAsInt(advanced, "placedStepCount"));
    }

    @Test
    void naturalWoodObstacleDoesNotBlockAutomaticRoadPlacement() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.blockStates.put(new BlockPos(0, 63, 0).asLong(), Blocks.DIRT.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 64, 0).asLong(), Blocks.GRASS_BLOCK.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 65, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.surfaceHeights.put(columnKey(0, 0), 64);

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0)),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK)),
                List.of(),
                List.of(),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0)
        );

        Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|log_clearance", plan), 1);

        assertEquals(Blocks.AIR.defaultBlockState(), level.getBlockState(new BlockPos(0, 65, 0)));
        assertEquals(Blocks.STONE_BRICK_SLAB, level.getBlockState(new BlockPos(0, 64, 0)).getBlock());
        assertEquals(1, readRecordComponentAsInt(advanced, "placedStepCount"));
    }

    @Test
    void alreadySatisfiedRoadDeckStepAdvancesWithoutWritingDuplicateBlock() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        level.blockStates.put(new BlockPos(0, 64, 0).asLong(), Blocks.STONE_BRICKS.defaultBlockState());

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0)),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK)),
                List.of(),
                List.of(),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0)
        );

        Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|satisfied", plan), 1);

        assertEquals(1, readRecordComponentAsInt(advanced, "placedStepCount"));
    }

    @Test
    void advancingRoadBuildStepsKeepsUnplacedGhostsVisibleUntilStateMatchesPlan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(2, 64, 0),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(2, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(1, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK),
                        new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(2, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), RoadGeometryPlanner.RoadBuildPhase.DECK)
                ),
                List.of(),
                List.of(),
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(0, 64, 0)
        );

        Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|ghost_consistency", plan), 1);
        @SuppressWarnings("unchecked")
        Set<Long> attempted = (Set<Long>) invokeRecordComponent(advanced, "attemptedStepKeys");

        assertFalse(attempted.isEmpty(), "advance should mark at least one attempted step");
        BlockPos attemptedPos = BlockPos.of(attempted.iterator().next());
        level.blockStates.put(attemptedPos.asLong(), Blocks.AIR.defaultBlockState());

        List<BlockPos> remainingGhosts = invokeRemainingRoadGhostPositions(level, advanced);

        assertTrue(remainingGhosts.contains(attemptedPos),
                () -> "attempted-but-unconfirmed step should remain ghosted: attempted=" + attemptedPos + " ghosts=" + remainingGhosts);
    }

    @Test
    void shortWaterBridgeDoesNotCreatePierSupportColumns() {
        RoadPlacementPlan plan = shortBridgePlanFixture();

        List<RoadCorridorPlan.CorridorSlice> supportSlices = plan.corridorPlan().slices().stream()
                .filter(slice -> !slice.supportPositions().isEmpty())
                .toList();

        assertTrue(supportSlices.isEmpty(), () -> supportSlices.toString());
    }

    @Test
    void shortWaterCrossingProducesArchModeWithoutSupportColumns() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 65, 0),
                        new BlockPos(2, 67, 0),
                        new BlockPos(3, 65, 0),
                        new BlockPos(4, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 3)),
                List.of()
        );

        assertTrue(plan.corridorPlan().slices().stream()
                .filter(slice -> slice.bridgeMode() == RoadBridgePlanner.BridgeMode.ARCH_SPAN)
                .allMatch(slice -> slice.supportPositions().isEmpty()));
    }

    @Test
    void longWaterBridgeUsesStoneDeckSlabsAndStonePierMaterials() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICKS)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.COBBLESTONE_WALL)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_SLAB)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)));
    }

    @Test
    void elevatedBridgePlanBuildsPierGhostsAllTheWayToFoundation() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 64, 0)
                )
        );

        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.pos().getY() == 41 && block.state().is(Blocks.STONE_BRICKS)),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void longWaterCrossingProducesPierModeAndFoundationToDeckSupportGhosts() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 66, 0),
                        new BlockPos(9, 64, 0)
                )
        );

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> slice.bridgeMode() == RoadBridgePlanner.BridgeMode.PIER_BRIDGE));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICKS) && block.pos().getY() == 41));
    }

    @Test
    void longWaterCrossingStillSynthesizesMidSpanPierPreviewWhenFoundationProbeFindsOnlyWater() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 40);
            for (int y = 40; y >= 0; y--) {
                level.blockStates.put(new BlockPos(x, y, 0).asLong(), Blocks.WATER.defaultBlockState());
            }
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 66, 0),
                        new BlockPos(9, 64, 0)
                )
        );

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> slice.bridgeMode() == RoadBridgePlanner.BridgeMode.PIER_BRIDGE));
        assertTrue(
                plan.corridorPlan().slices().stream()
                        .filter(slice -> slice.index() > 1 && slice.index() < 8)
                        .anyMatch(slice -> !slice.supportPositions().isEmpty()),
                () -> plan.corridorPlan().slices().toString()
        );
    }

    @Test
    void wideWaterCrossingProtectsOnlyNarrowMainChannelAndKeepsMidSpanPiersOutsideIt() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 12, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 11; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 68, 0),
                        new BlockPos(10, 68, 0),
                        new BlockPos(11, 66, 0),
                        new BlockPos(12, 64, 0)
                )
        );

        assertEquals(1, plan.navigableWaterBridgeRanges().size(), () -> String.valueOf(plan.navigableWaterBridgeRanges()));
        RoadPlacementPlan.BridgeRange mainChannel = plan.navigableWaterBridgeRanges().get(0);
        int protectedWidth = mainChannel.endIndex() - mainChannel.startIndex() + 1;
        assertTrue(protectedWidth <= 3,
                () -> "expected a narrow protected main channel, got " + plan.navigableWaterBridgeRanges());

        List<Integer> supportIndexes = plan.corridorPlan().slices().stream()
                .filter(slice -> !slice.supportPositions().isEmpty())
                .map(RoadCorridorPlan.CorridorSlice::index)
                .toList();

        assertTrue(supportIndexes.size() >= 2, () -> "expected transition piers on both sides of the protected channel, got " + supportIndexes);
        assertTrue(supportIndexes.stream().anyMatch(index -> index < mainChannel.startIndex()), () -> supportIndexes.toString());
        assertTrue(supportIndexes.stream().anyMatch(index -> index > mainChannel.endIndex()), () -> supportIndexes.toString());
        assertTrue(
                supportIndexes.stream().noneMatch(index -> index >= mainChannel.startIndex() && index <= mainChannel.endIndex()),
                () -> "protected main channel should stay pier-free: channel=" + mainChannel + " supports=" + supportIndexes
        );
    }

    @Test
    void bridgeRailingAndLightsStayWithinBridgeSurfaceFootprint() {
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

        java.util.Set<Long> bridgeSurfaceColumns = plan.corridorPlan().slices().stream()
                .filter(slice -> slice.segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH)
                .flatMap(slice -> slice.surfacePositions().stream())
                .map(pos -> BlockPos.asLong(pos.getX(), 0, pos.getZ()))
                .collect(java.util.stream.Collectors.toSet());

        java.util.Set<Long> lampColumns = plan.corridorPlan().slices().stream()
                .filter(slice -> slice.segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH)
                .flatMap(slice -> java.util.stream.Stream.concat(slice.railingLightPositions().stream(), slice.pierLightPositions().stream())
                        .flatMap(lightPos -> {
                            int armX = Integer.compare(lightPos.getX() - slice.deckCenter().getX(), 0);
                            int armZ = Integer.compare(lightPos.getZ() - slice.deckCenter().getZ(), 0);
                            return java.util.stream.Stream.of(
                                    BlockPos.asLong(lightPos.getX(), 0, lightPos.getZ()),
                                    BlockPos.asLong(lightPos.getX() + armX, 0, lightPos.getZ() + armZ)
                            );
                        }))
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(plan.ghostBlocks().stream()
                .filter(block -> block.state().is(Blocks.COBBLESTONE_WALL))
                .allMatch(block -> bridgeSurfaceColumns.contains(BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))),
                () -> plan.ghostBlocks().toString());
        assertTrue(plan.ghostBlocks().stream()
                .filter(block -> block.state().is(Blocks.LANTERN) || block.state().is(Blocks.SPRUCE_FENCE))
                .allMatch(block -> lampColumns.contains(BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void bridgeLanternsHangFromOutboardFenceArms() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        List<RoadGeometryPlanner.GhostRoadBlock> lanterns = plan.ghostBlocks().stream()
                .filter(block -> block.state().is(Blocks.LANTERN))
                .toList();

        assertFalse(lanterns.isEmpty(), "expected bridge lanterns");
        assertTrue(lanterns.stream().allMatch(block -> hasGhost(plan, block.pos().above(), Blocks.SPRUCE_FENCE)),
                () -> lanterns.toString() + " / " + plan.ghostBlocks());
    }

    @Test
    void shorelineLandRouteDoesNotCreateBridgeRangesJustBecauseWaterIsNearby() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 3; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
            setSurfaceColumn(level, x, 1, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 1).asLong(), Blocks.STONE.defaultBlockState());
        }

        List<RoadPlacementPlan.BridgeRange> ranges = invokeDetectBridgeRanges(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0)
                )
        );

        assertTrue(ranges.isEmpty(), String.valueOf(ranges));
    }

    @Test
    void elevatedWaterCrossingProducesBridgeSpanAndRampSlabsWithoutStairs() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 3; x++) {
            setSurfaceColumn(level, x, 0, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 67, 0),
                        new BlockPos(2, 67, 0),
                        new BlockPos(3, 67, 0),
                        new BlockPos(4, 64, 0)
                )
        );

        assertFalse(plan.bridgeRanges().isEmpty(), String.valueOf(plan.bridgeRanges()));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)),
                () -> plan.ghostBlocks().toString());
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void detectBridgeRangesMergesShortLandGapBetweenWaterCrossings() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 2, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 3, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(3, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());

        List<RoadPlacementPlan.BridgeRange> ranges = invokeDetectBridgeRanges(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0)
                )
        );

        assertEquals(List.of(new RoadPlacementPlan.BridgeRange(1, 3)), ranges);
    }

    @Test
    void mergedBridgeRangeKeepsShortLandGapAsSupportedBridgeSpan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 2, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 3, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(3, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 67, 0),
                        new BlockPos(2, 67, 0),
                        new BlockPos(3, 67, 0),
                        new BlockPos(4, 64, 0)
                )
        );

        assertTrue(plan.bridgeRanges().contains(new RoadPlacementPlan.BridgeRange(1, 3)), String.valueOf(plan.bridgeRanges()));
        assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.corridorPlan().slices().get(2).bridgeMode());
        assertTrue(plan.corridorPlan().slices().get(2).supportPositions().isEmpty());
    }

    @Test
    void bridgeLightsHangFromFenceArmsOutsideRailingWalls() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        assertTrue(plan.ghostBlocks().stream().anyMatch(block ->
                        block.state().is(Blocks.LANTERN)
                                && hasGhost(plan, block.pos().above(), Blocks.SPRUCE_FENCE)
                                && (hasGhost(plan, block.pos().above().north(), Blocks.COBBLESTONE_WALL)
                                || hasGhost(plan, block.pos().above().south(), Blocks.COBBLESTONE_WALL)
                                || hasGhost(plan, block.pos().above().east(), Blocks.COBBLESTONE_WALL)
                                || hasGhost(plan, block.pos().above().west(), Blocks.COBBLESTONE_WALL))),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void longLandRouteUsesOutboardHangingLanternStreetlights() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                java.util.stream.IntStream.rangeClosed(0, 48)
                        .mapToObj(x -> new BlockPos(x, 64, 0))
                        .toList(),
                List.of(),
                List.of()
        );

        assertTrue(hasGhost(plan, new BlockPos(24, 67, -4), Blocks.OAK_FENCE), () -> plan.ghostBlocks().toString());
        assertTrue(hasGhost(plan, new BlockPos(24, 66, -4), Blocks.LANTERN), () -> plan.ghostBlocks().toString());
        assertFalse(hasGhost(plan, new BlockPos(24, 67, -3), Blocks.LANTERN), () -> plan.ghostBlocks().toString());
    }

    @Test
    void pierBridgePlanProducesVillageStyleBridgeAndPierLamps() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 66, 0),
                        new BlockPos(9, 64, 0)
                )
        );

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.pierLightPositions().isEmpty()),
                () -> plan.corridorPlan().slices().toString());
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)),
                () -> plan.ghostBlocks().toString());
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.LANTERN) && Math.abs(block.pos().getZ()) >= 4),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void longWaterBridgeUsesDiscretePierAnchorsInsteadOfContinuousSupport() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        List<Integer> supportIndexes = plan.corridorPlan().slices().stream()
                .filter(slice -> !slice.supportPositions().isEmpty())
                .map(RoadCorridorPlan.CorridorSlice::index)
                .toList();

        assertFalse(supportIndexes.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertTrue(supportIndexes.size() < 6, supportIndexes.toString());
        assertTrue(supportIndexes.stream().allMatch(index -> index > 1 && index < 8), supportIndexes.toString());
    }

    @Test
    void longBridgePlanDoesNotFillEveryDeckColumnDownward() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 64, 0)
                )
        );

        assertFalse(hasGhost(plan, new BlockPos(4, 66, 0), Blocks.STONE_BRICKS), () -> plan.ghostBlocks().toString());
        assertFalse(hasGhost(plan, new BlockPos(5, 66, 0), Blocks.STONE_BRICKS), () -> plan.ghostBlocks().toString());
    }

    @Test
    void longPierBridgeConstructionRangeExtendsOntoAdjacentLandForBridgeheads() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        initializeServerLevelPlayers(level);

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 10, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 2; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        List<RoadPlacementPlan.BridgeRange> expandedRanges = invokeExpandBridgeConstructionRanges(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 66, 0),
                        new BlockPos(10, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 9))
        );

        assertEquals(List.of(new RoadPlacementPlan.BridgeRange(0, 10)), expandedRanges);
    }

    @Test
    void treeCanopyDoesNotShortenPierBridgeheadLandExtension() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        initializeServerLevelPlayers(level);

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 69, Blocks.OAK_LEAVES.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 64, 0).asLong(), Blocks.DIRT.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 65, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 66, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 67, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 68, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 69, Blocks.OAK_LEAVES.defaultBlockState());
        level.blockStates.put(new BlockPos(9, 64, 0).asLong(), Blocks.DIRT.defaultBlockState());
        level.blockStates.put(new BlockPos(9, 65, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(9, 66, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(9, 67, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(9, 68, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        setSurfaceColumn(level, 10, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 2; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 68, 0),
                new BlockPos(4, 68, 0),
                new BlockPos(5, 68, 0),
                new BlockPos(6, 68, 0),
                new BlockPos(7, 68, 0),
                new BlockPos(8, 68, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0)
        );
        int extendedStart = invokeExtendBridgeBoundary(level, centerPath, 2, -1, 68);
        int extendedEnd = invokeExtendBridgeBoundary(level, centerPath, 8, 1, 68);

        assertEquals(0, extendedStart);
        assertEquals(10, extendedEnd);
    }

    @Test
    void extendedPierBridgeStillAutoPlacesAtLeastOneConstructionStep() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 10, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 2; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 66, 0),
                        new BlockPos(10, 64, 0)
                )
        );

        Object advanced = invokeAdvanceRoadBuildSteps(level, newRoadConstructionJob(level, "manual|test|auto_bridge", plan), 8);

        assertTrue(readRecordComponentAsInt(advanced, "placedStepCount") > 0, () -> plan.buildSteps().toString());
        assertTrue(level.blockStates.keySet().stream()
                        .map(BlockPos::of)
                        .anyMatch(pos -> pos.getY() >= 64
                                && !level.getBlockState(pos).is(Blocks.DIRT)
                                && !level.getBlockState(pos).is(Blocks.STONE)
                                && !level.getBlockState(pos).is(Blocks.WATER)),
                () -> level.blockStates.toString());
    }

    @Test
    void alreadyBuiltRoadSegmentsAreExcludedFromNewConstructionPlan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 2; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }

        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)
        );

        RoadPlacementPlan initialPlan = invokeCreateRoadPlacementPlan(level, centerPath);
        assertFalse(initialPlan.buildSteps().isEmpty());

        initialPlan.ghostBlocks().forEach(block -> level.blockStates.put(block.pos().asLong(), block.state()));

        RoadPlacementPlan repeatedPlan = invokeCreateRoadPlacementPlan(level, centerPath);

        assertTrue(repeatedPlan.ghostBlocks().isEmpty(), () -> repeatedPlan.ghostBlocks().toString());
        assertTrue(repeatedPlan.buildSteps().isEmpty(), () -> repeatedPlan.buildSteps().toString());
    }

    @Test
    void coreExclusionFilterRemovesConstructionFootprintInsideCoreRadius() {
        List<BlockPos> filtered = invokeFilterCoreExcludedPositions(
                List.of(
                        new BlockPos(100, 64, 100),
                        new BlockPos(105, 64, 105),
                        new BlockPos(106, 64, 100)
                ),
                List.of(new BlockPos(100, 64, 100)),
                List.of()
        );

        assertFalse(filtered.contains(new BlockPos(100, 64, 100)));
        assertFalse(filtered.contains(new BlockPos(105, 64, 105)));
        assertTrue(filtered.contains(new BlockPos(106, 64, 100)));
    }

    @Test
    void trimmingExcludedPathEndpointsPreservesOuterBridgeApproach() {
        List<BlockPos> trimmed = invokeTrimExcludedPathEndpoints(
                List.of(
                        new BlockPos(100, 64, 100),
                        new BlockPos(104, 64, 100),
                        new BlockPos(106, 64, 100),
                        new BlockPos(112, 64, 100)
                ),
                java.util.Set.of(
                        BlockPos.asLong(100, 0, 100),
                        BlockPos.asLong(104, 0, 100)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(106, 64, 100),
                        new BlockPos(112, 64, 100)
                ),
                trimmed
        );
    }

    private static void setSurfaceColumn(TestServerLevel level, int x, int z, int surfaceY, BlockState state) {
        level.surfaceHeights.put(columnKey(x, z), surfaceY);
        level.blockStates.put(new BlockPos(x, surfaceY, z).asLong(), state);
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static boolean hasGhost(RoadPlacementPlan plan, BlockPos pos, net.minecraft.world.level.block.Block expectedBlock) {
        return plan.ghostBlocks().stream().anyMatch(block -> block.pos().equals(pos) && block.state().is(expectedBlock));
    }

    private static RoadPlacementPlan shortBridgePlanFixture() {
        return StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 6)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 4))
        );
    }

    private static RoadPlacementPlan longBridgePlanFixture() {
        return StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 8)),
                List.of(new RoadPlacementPlan.BridgeRange(4, 5))
        );
    }

    @SuppressWarnings("unchecked")
    private static List<RoadPlacementPlan.BridgeRange> invokeDetectBridgeRanges(ServerLevel level, List<BlockPos> centerPath) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod("detectBridgeRanges", ServerLevel.class, List.class);
            method.setAccessible(true);
            return (List<RoadPlacementPlan.BridgeRange>) method.invoke(null, level, centerPath);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static RoadPlacementPlan invokeCreateRoadPlacementPlan(ServerLevel level, List<BlockPos> centerPath) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "createRoadPlacementPlan",
                    ServerLevel.class,
                    List.class,
                    BlockPos.class,
                    BlockPos.class,
                    BlockPos.class,
                    BlockPos.class
            );
            method.setAccessible(true);
            BlockPos start = centerPath.get(0);
            BlockPos end = centerPath.get(centerPath.size() - 1);
            return (RoadPlacementPlan) method.invoke(null, level, centerPath, start, start, end, end);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RoadPlacementPlan.BridgeRange> invokeExpandBridgeConstructionRanges(ServerLevel level,
                                                                                            List<BlockPos> centerPath,
                                                                                            List<RoadPlacementPlan.BridgeRange> bridgeRanges) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "expandBridgeConstructionRangesForTest",
                    ServerLevel.class,
                    List.class,
                    List.class
            );
            method.setAccessible(true);
            return (List<RoadPlacementPlan.BridgeRange>) method.invoke(null, level, centerPath, bridgeRanges);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeFilterCoreExcludedPositions(List<BlockPos> positions,
                                                                    List<BlockPos> townCores,
                                                                    List<BlockPos> nationCores) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "filterCoreExcludedPositionsForTest",
                    List.class,
                    List.class,
                    List.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, positions, townCores, nationCores);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeTrimExcludedPathEndpoints(List<BlockPos> centerPath, java.util.Set<Long> excludedColumns) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "trimExcludedPathEndpointsForTest",
                    List.class,
                    java.util.Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, centerPath, excludedColumns);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invokeAdvanceRoadBuildSteps(ServerLevel level, Object job, int stepCount) {
        try {
            Method method = java.util.Arrays.stream(StructureConstructionManager.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("placeRoadBuildSteps"))
                    .filter(candidate -> candidate.getParameterCount() == 3)
                    .findFirst()
                    .orElseThrow();
            method.setAccessible(true);
            return method.invoke(null, level, job, stepCount);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRoadOwnedBlocks(ServerLevel level, RoadPlacementPlan plan) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "roadOwnedBlocks",
                    ServerLevel.class,
                    RoadPlacementPlan.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, level, plan);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static Object newRoadConstructionJob(ServerLevel level, String roadId, RoadPlacementPlan plan) {
        try {
            Class<?> jobClass = Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadConstructionJob");
            java.lang.reflect.Constructor<?> constructor = java.util.Arrays.stream(jobClass.getDeclaredConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 15)
                    .findFirst()
                    .orElseThrow();
            constructor.setAccessible(true);
            return constructor.newInstance(
                    level,
                    roadId,
                    java.util.UUID.randomUUID(),
                    "town_a",
                    "nation_a",
                    "Town A",
                    "Town B",
                    plan,
                    List.of(),
                    0,
                    0.0D,
                    false,
                    0,
                    false,
                    java.util.Set.of()
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int readRecordComponentAsInt(Object instance, String accessor) {
        try {
            return (int) instance.getClass().getDeclaredMethod(accessor).invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invokeRecordComponent(Object instance, String accessor) {
        try {
            return instance.getClass().getDeclaredMethod(accessor).invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRemainingRoadGhostPositions(ServerLevel level, Object job) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "remainingRoadGhostPositions",
                    ServerLevel.class,
                    Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadConstructionJob")
            );
            method.setAccessible(true);
            return List.copyOf((Set<BlockPos>) method.invoke(null, level, job));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int invokeExtendBridgeBoundary(ServerLevel level,
                                                  List<BlockPos> centerPath,
                                                  int edgeIndex,
                                                  int direction,
                                                  int targetDeckY) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "extendBridgeBoundary",
                    ServerLevel.class,
                    List.class,
                    int.class,
                    int.class,
                    int.class
            );
            method.setAccessible(true);
            return (int) method.invoke(null, level, centerPath, edgeIndex, direction, targetDeckY);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> invokeRoadBuildBatchSizes(RoadPlacementPlan plan) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "roadBuildBatchSizes",
                    RoadPlacementPlan.class
            );
            method.setAccessible(true);
            return (List<Integer>) method.invoke(null, plan);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static void initializeServerLevelPlayers(ServerLevel level) {
        if (level == null) {
            return;
        }
        Class<?> type = level.getClass();
        while (type != null) {
            try {
                Field players = type.getDeclaredField("players");
                players.setAccessible(true);
                if (players.get(level) == null) {
                    players.set(level, new java.util.ArrayList<>());
                }
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
        private Map<Long, Integer> surfaceHeights;
        private Holder<Biome> biome;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState newState, int flags) {
            blockStates.put(pos.asLong(), newState);
            return true;
        }

        @Override
        public BlockPos getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types heightmapType, BlockPos pos) {
            int surfaceY = surfaceHeights.getOrDefault(columnKey(pos.getX(), pos.getZ()), 63);
            return new BlockPos(pos.getX(), surfaceY + 1, pos.getZ());
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }

        @Override
        public <T extends net.minecraft.core.particles.ParticleOptions> int sendParticles(T particle,
                                                                                           double x,
                                                                                           double y,
                                                                                           double z,
                                                                                           int count,
                                                                                           double xDist,
                                                                                           double yDist,
                                                                                           double zDist,
                                                                                           double speed) {
            return 0;
        }

        @Override
        public void playSound(net.minecraft.world.entity.player.Player player,
                              BlockPos pos,
                              net.minecraft.sounds.SoundEvent sound,
                              net.minecraft.sounds.SoundSource source,
                              float volume,
                              float pitch) {
        }
    }
}
