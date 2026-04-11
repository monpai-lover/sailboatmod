package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderHammerSupportTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allocatesWalletBeforeTreasury() {
        BuilderHammerChargePlan plan = BuilderHammerChargePlan.allocate(48L, 20L, 100L);

        assertTrue(plan.success());
        assertEquals(20L, plan.walletSpent());
        assertEquals(28L, plan.treasurySpent());
    }

    @Test
    void failsWhenCombinedFundsAreInsufficient() {
        BuilderHammerChargePlan plan = BuilderHammerChargePlan.allocate(48L, 10L, 12L);

        assertFalse(plan.success());
        assertEquals(0L, plan.walletSpent());
        assertEquals(0L, plan.treasurySpent());
        assertEquals(26L, plan.shortfall());
    }

    @Test
    void capsQueuedHammerCredits() {
        BuilderHammerCreditState state = BuilderHammerCreditState.of(4, 5);

        BuilderHammerCreditState updated = state.enqueue();

        assertTrue(updated.accepted());
        assertEquals(5, updated.queuedCredits());
        assertFalse(updated.enqueue().accepted());
        assertEquals(5, updated.enqueue().queuedCredits());
    }

    @Test
    void clipsRoadGhostBlocksToNearbyWindow() {
        List<BlockPos> positions = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(16, 64, 0)
        );

        List<BlockPos> clipped = RuntimeRoadGhostWindow.clip(positions, new BlockPos(9, 64, 0), 5);

        assertEquals(List.of(
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(12, 64, 0)
        ), clipped);
    }

    @Test
    void roadRuntimeProgressUsesPlacedBuildStepsAndShrinksGhosts() {
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                new BlockPos(-1, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 1), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(0, 65, 1), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(1, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                new BlockPos(0, 65, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(0, 65, 0)
        );

        List<BlockPos> remaining = invokeRemainingRoadGhostPositions(plan, 1);

        assertEquals(List.of(new BlockPos(0, 65, 1), new BlockPos(1, 65, 0)), remaining);
        assertEquals(33, invokeRoadProgressPercent(plan, 1));
    }

    @Test
    void roadHammerConsumesWholeNextRoadBuildBatch() {
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        RoadGeometryPlanner.RoadGeometryPlan geometry = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        RoadPlacementPlan plan = new RoadPlacementPlan(
                centerPath,
                new BlockPos(-1, 64, 0),
                centerPath.get(0),
                centerPath.get(centerPath.size() - 1),
                new BlockPos(2, 64, 0),
                geometry.ghostBlocks(),
                geometry.buildSteps(),
                List.of(),
                centerPath.get(0).above(),
                centerPath.get(centerPath.size() - 1).above(),
                centerPath.get(0).above()
        );

        int firstBatchSize = (int) RoadGeometryPlanner.slicePositions(centerPath, 0).stream()
                .filter(pos -> geometry.buildSteps().stream().anyMatch(step -> step.pos().equals(pos)))
                .count();
        int secondBatchStart = Math.max(0, firstBatchSize);

        assertEquals(firstBatchSize, invokeNextRoadBuildBatchSize(plan, 0));
        assertEquals(geometry.buildSteps().size() - firstBatchSize, invokeNextRoadBuildBatchSize(plan, secondBatchStart));
    }

    @Test
    void roadHammerBatchingUsesCorridorSliceSupportsAndLights() {
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0));
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                centerPath,
                List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 65, 0),
                                RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN,
                                List.of(new BlockPos(0, 65, 0)),
                                List.of(),
                                List.of(new BlockPos(0, 66, 1)),
                                List.of(new BlockPos(0, 64, 0)),
                                List.of(new BlockPos(0, 63, 1))
                        )
                ),
                null,
                true
        );
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.SPRUCE_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.SPRUCE_FENCE.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 66, 1), Blocks.LANTERN.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 63, 1), Blocks.LANTERN.defaultBlockState())
        );
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = java.util.stream.IntStream.range(0, ghostBlocks.size())
                .mapToObj(index -> new RoadGeometryPlanner.RoadBuildStep(index, ghostBlocks.get(index).pos(), ghostBlocks.get(index).state()))
                .toList();
        RoadPlacementPlan plan = new RoadPlacementPlan(
                centerPath,
                new BlockPos(-1, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                ghostBlocks,
                buildSteps,
                List.of(new RoadPlacementPlan.BridgeRange(0, 0)),
                List.of(),
                ghostBlocks.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList(),
                new BlockPos(0, 65, 0),
                new BlockPos(0, 65, 0),
                new BlockPos(0, 65, 0),
                corridorPlan
        );

        assertEquals(4, invokeNextRoadBuildBatchSize(plan, 0));
    }

    @Test
    void placementArtifactsAreDerivedFromCorridorSlices() {
        List<BlockPos> sliceSurface = List.of(
                new BlockPos(0, 65, 0),
                new BlockPos(0, 65, 1)
        );
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                List.of(new BlockPos(0, 64, 0)),
                List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 65, 0),
                                RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN,
                                sliceSurface,
                                List.of(new BlockPos(0, 63, 0)),
                                List.of(new BlockPos(0, 66, 1)),
                                List.of(new BlockPos(0, 64, 0)),
                                List.of(new BlockPos(0, 63, 1))
                        )
                ),
                null,
                true
        );

        Object artifacts = invokeBuildRoadPlacementArtifacts(
                corridorPlan,
                newRoadPlacementStyleFactory(
                        Blocks.SPRUCE_SLAB.defaultBlockState(),
                        Blocks.SPRUCE_FENCE.defaultBlockState(),
                        true
                ),
                ghostBlocks -> List.of(new BlockPos(0, 62, 0))
        );

        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = invokeArtifactsGhostBlocks(artifacts);
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = invokeArtifactsBuildSteps(artifacts);
        List<BlockPos> ownedBlocks = invokeArtifactsOwnedBlocks(artifacts);
        Set<BlockPos> ghostPositions = ghostBlocks.stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .collect(java.util.stream.Collectors.toSet());
        Set<BlockPos> buildStepPositions = buildSteps.stream()
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(
                new BlockPos(0, 65, 0),
                new BlockPos(0, 65, 1),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 66, 1),
                new BlockPos(0, 67, 1),
                new BlockPos(0, 63, 1),
                new BlockPos(0, 64, 1)
        ), ghostPositions);
        assertEquals(ghostPositions, buildStepPositions);
        assertTrue(ownedBlocks.contains(new BlockPos(0, 63, 0)));
        assertTrue(ownedBlocks.contains(new BlockPos(0, 62, 0)));
    }

    @Test
    void placementArtifactsReturnEmptyWhenCorridorIsInvalid() {
        RoadCorridorPlan invalidCorridorPlan = new RoadCorridorPlan(
                List.of(new BlockPos(0, 64, 0)),
                List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 65, 0),
                                RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN,
                                List.of(new BlockPos(0, 65, 0)),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                null,
                false
        );

        Object artifacts = invokeBuildRoadPlacementArtifacts(
                invalidCorridorPlan,
                newRoadPlacementStyleFactory(
                        Blocks.SPRUCE_SLAB.defaultBlockState(),
                        Blocks.SPRUCE_FENCE.defaultBlockState(),
                        true
                ),
                ghostBlocks -> List.of(new BlockPos(0, 62, 0))
        );

        assertTrue(invokeArtifactsGhostBlocks(artifacts).isEmpty());
        assertTrue(invokeArtifactsBuildSteps(artifacts).isEmpty());
        assertTrue(invokeArtifactsOwnedBlocks(artifacts).isEmpty());
    }

    @Test
    void roadBuildStepCompletionRequiresExactPlannedState() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        assertTrue(invokeRoadBuildStepPlaced(step.state(), step));
        assertFalse(invokeRoadBuildStepPlaced(Blocks.SPRUCE_SLAB.defaultBlockState(), step));
    }

    @Test
    void allowsRepairReplacementWhenExistingRoadSurfaceDiffersFromPlannedStep() {
        assertTrue(invokeCanReplaceRoadSurface(
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState()
        ));
        assertFalse(invokeCanReplaceRoadSurface(
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        ));
        assertFalse(invokeCanReplaceRoadSurface(
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState()
        ));
    }

    @Test
    void naturalTopsoilCanBeExcavatedForRoadPlacement() {
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.DIRT.defaultBlockState()));
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.MUD.defaultBlockState()));
    }

    @Test
    void roadRuntimeUsesActualCompletedStepsInsteadOfPrefixCount() {
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        RoadGeometryPlanner.RoadGeometryPlan geometry = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        RoadPlacementPlan plan = new RoadPlacementPlan(
                centerPath,
                new BlockPos(-1, 64, 0),
                centerPath.get(0),
                centerPath.get(centerPath.size() - 1),
                new BlockPos(2, 64, 0),
                geometry.ghostBlocks(),
                geometry.buildSteps(),
                List.of(),
                centerPath.get(0).above(),
                centerPath.get(centerPath.size() - 1).above(),
                centerPath.get(0).above()
        );
        int firstBatchSize = (int) RoadGeometryPlanner.slicePositions(centerPath, 0).stream()
                .filter(pos -> geometry.buildSteps().stream().anyMatch(step -> step.pos().equals(pos)))
                .count();
        Set<Long> completedStepKeys = geometry.buildSteps().subList(firstBatchSize, geometry.buildSteps().size()).stream()
                .map(RoadGeometryPlanner.RoadBuildStep::pos)
                .map(BlockPos::asLong)
                .collect(java.util.stream.Collectors.toSet());

        List<BlockPos> remaining = invokeRemainingRoadGhostPositions(plan, completedStepKeys);

        assertEquals(geometry.buildSteps().subList(0, firstBatchSize).stream().map(RoadGeometryPlanner.RoadBuildStep::pos).toList(), remaining);
        assertEquals(Math.round((geometry.buildSteps().size() - firstBatchSize) * 100.0F / geometry.buildSteps().size()), invokeRoadProgressPercent(plan, completedStepKeys));
        assertEquals(firstBatchSize, invokeNextRoadBuildBatchSize(plan, completedStepKeys));
    }

    @Test
    void roadGhostTargetUsesActualFilteredRemainingGhosts() {
        BlockPos target = invokeRoadGhostTargetPos(List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(3, 65, 3), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(4, 65, 4), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        ));

        assertEquals(new BlockPos(3, 65, 3), target);
        assertNull(invokeRoadGhostTargetPos(List.of()));
    }

    @Test
    void navigableBridgeAddsRailingGhostBlocks() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghosts = invokeNavigableBridgeGhosts(
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 69, 0), Blocks.SPRUCE_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(5, 69, 0), Blocks.SPRUCE_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(10, 69, 0), Blocks.SPRUCE_SLAB.defaultBlockState())
                ),
                List.of(
                        new BlockPos(0, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(10, 68, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(0, 2))
        );

        assertTrue(ghosts.stream().anyMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)));
    }

    @Test
    void minorSurfaceClutterCountsAsReplaceableRoadBuildTarget() {
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.GRASS.defaultBlockState()));
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.DANDELION.defaultBlockState()));
    }

    @Test
    void naturalRoadObstaclesCountAsReplaceableRoadBuildTargets() {
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.OAK_LEAVES.defaultBlockState()));
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.VINE.defaultBlockState()));
        assertTrue(invokeRoadPlacementReplaceableForTest(Blocks.SNOW.defaultBlockState()));
    }

    @Test
    void storageBlocksDoNotCountAsReplaceableRoadBuildTargets() {
        assertFalse(invokeRoadPlacementReplaceableForTest(Blocks.CHEST.defaultBlockState()));
    }

    @Test
    void roadRollbackOwnershipIncludesTerrainEdits() {
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 65, 0)),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(1, 65, 0),
                List.of(),
                List.of(),
                List.of(),
                List.of(new BlockPos(0, 64, 0), new BlockPos(0, 63, 0)),
                new BlockPos(0, 65, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(0, 65, 0)
        );

        assertTrue(plan.ownedBlocks().contains(new BlockPos(0, 63, 0)));
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRemainingRoadGhostPositions(RoadPlacementPlan plan, int placedStepCount) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("remainingRoadGhostBlocks", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            List<RoadGeometryPlanner.GhostRoadBlock> remaining = (List<RoadGeometryPlanner.GhostRoadBlock>) method.invoke(null, plan, placedStepCount);
            return remaining.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect remaining road ghost projection", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRemainingRoadGhostPositions(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("remainingRoadGhostBlocks", RoadPlacementPlan.class, Set.class);
            method.setAccessible(true);
            List<RoadGeometryPlanner.GhostRoadBlock> remaining = (List<RoadGeometryPlanner.GhostRoadBlock>) method.invoke(null, plan, completedStepKeys);
            return remaining.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect live-world road ghost projection", ex);
        }
    }

    private static int invokeRoadProgressPercent(RoadPlacementPlan plan, int placedStepCount) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadProgressPercent", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, placedStepCount);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road progress calculation", ex);
        }
    }

    private static int invokeRoadProgressPercent(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadProgressPercent", RoadPlacementPlan.class, Set.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, completedStepKeys);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect live-world road progress calculation", ex);
        }
    }

    private static int invokeNextRoadBuildBatchSize(RoadPlacementPlan plan, int placedStepCount) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("nextRoadBuildBatchSize", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, placedStepCount);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road hammer batch sizing", ex);
        }
    }

    private static int invokeNextRoadBuildBatchSize(RoadPlacementPlan plan, Set<Long> completedStepKeys) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("nextRoadBuildBatchSize", RoadPlacementPlan.class, Set.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, completedStepKeys);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect live-world road hammer batch sizing", ex);
        }
    }

    private static Object invokeBuildRoadPlacementArtifacts(RoadCorridorPlan corridorPlan,
                                                            Function<BlockPos, Object> styleResolver,
                                                            Function<List<RoadGeometryPlanner.GhostRoadBlock>, List<BlockPos>> terrainOwnershipResolver) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("buildRoadPlacementArtifacts", RoadCorridorPlan.class, Function.class, Function.class);
            method.setAccessible(true);
            return method.invoke(null, corridorPlan, styleResolver, terrainOwnershipResolver);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect corridor-derived road placement artifacts", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RoadGeometryPlanner.GhostRoadBlock> invokeArtifactsGhostBlocks(Object artifacts) {
        try {
            Method method = artifacts.getClass().getDeclaredMethod("ghostBlocks");
            method.setAccessible(true);
            return (List<RoadGeometryPlanner.GhostRoadBlock>) method.invoke(artifacts);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect corridor-derived ghost blocks", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RoadGeometryPlanner.RoadBuildStep> invokeArtifactsBuildSteps(Object artifacts) {
        try {
            Method method = artifacts.getClass().getDeclaredMethod("buildSteps");
            method.setAccessible(true);
            return (List<RoadGeometryPlanner.RoadBuildStep>) method.invoke(artifacts);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect corridor-derived build steps", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeArtifactsOwnedBlocks(Object artifacts) {
        try {
            Method method = artifacts.getClass().getDeclaredMethod("ownedBlocks");
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(artifacts);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect corridor-derived owned blocks", ex);
        }
    }

    private static Function<BlockPos, Object> newRoadPlacementStyleFactory(BlockState surfaceState,
                                                                           BlockState supportState,
                                                                           boolean bridge) {
        return ignored -> {
            try {
                Class<?> styleClass = Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadPlacementStyle");
                java.lang.reflect.Constructor<?> ctor = styleClass.getDeclaredConstructor(BlockState.class, BlockState.class, boolean.class);
                ctor.setAccessible(true);
                return ctor.newInstance(surfaceState, supportState, bridge);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError("Unable to construct road placement style", ex);
            }
        };
    }

    private static boolean invokeRoadBuildStepPlaced(BlockState currentState, RoadGeometryPlanner.RoadBuildStep step) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("isRoadBuildStepPlaced", BlockState.class, RoadGeometryPlanner.RoadBuildStep.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, currentState, step);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect exact road build-step completion", ex);
        }
    }

    private static BlockPos invokeRoadGhostTargetPos(List<RoadGeometryPlanner.GhostRoadBlock> remainingGhosts) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("selectRoadGhostTargetPos", List.class);
            method.setAccessible(true);
            return (BlockPos) method.invoke(null, remainingGhosts);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road ghost target selection", ex);
        }
    }

    private static boolean invokeRoadPlacementReplaceableForTest(BlockState state) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("isRoadPlacementReplaceableForTest", BlockState.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, state);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road replacement rules", ex);
        }
    }

    private static boolean invokeCanReplaceRoadSurface(BlockState existingState, BlockState plannedState) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("canReplaceRoadSurface", BlockState.class, BlockState.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, existingState, plannedState);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road surface replacement rules", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RoadGeometryPlanner.GhostRoadBlock> invokeNavigableBridgeGhosts(List<RoadGeometryPlanner.GhostRoadBlock> baseGhosts,
                                                                                         List<BlockPos> centerPath,
                                                                                         List<RoadPlacementPlan.BridgeRange> navigableRanges) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("decorateNavigableBridgeGhosts", List.class, List.class, List.class, List.class, List.class);
            method.setAccessible(true);
            return (List<RoadGeometryPlanner.GhostRoadBlock>) method.invoke(null, baseGhosts, centerPath, navigableRanges, List.of(), List.of());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect navigable bridge ghost decoration", ex);
        }
    }
}
