package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    void buildStepsSortSupportsBeforeDeckBeforeDecor() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 66, 0), Blocks.LANTERN.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICKS.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );

        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = invokeToBuildSteps(ghostBlocks);

        assertEquals(
                List.of(
                        RoadGeometryPlanner.RoadBuildPhase.SUPPORT,
                        RoadGeometryPlanner.RoadBuildPhase.DECK,
                        RoadGeometryPlanner.RoadBuildPhase.DECOR
                ),
                buildSteps.stream().map(RoadGeometryPlanner.RoadBuildStep::phase).toList()
        );
        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 65, 0),
                        new BlockPos(0, 66, 0)
                ),
                buildSteps.stream().map(RoadGeometryPlanner.RoadBuildStep::pos).toList()
        );
    }

    @Test
    void attemptedRoadBuildStepRemainsVisibleUntilWorldStateMatchesPlan() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                RoadGeometryPlanner.RoadBuildPhase.DECK
        );

        Set<Long> attempted = new LinkedHashSet<>();
        attempted.add(step.pos().asLong());

        List<RoadGeometryPlanner.RoadBuildStep> remaining = invokeRemainingRoadBuildSteps(
                List.of(step),
                Set.of(),
                attempted
        );

        assertEquals(List.of(step), remaining);
    }

    @Test
    void attemptedRoadStepRemainsHammerTargetableWhileWorldStateStillDiffersFromPlan() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                RoadGeometryPlanner.RoadBuildPhase.DECK
        );
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0)),
                null,
                null,
                null,
                null,
                List.of(new RoadGeometryPlanner.GhostRoadBlock(step.pos(), step.state())),
                List.of(step),
                List.of(),
                List.of(step.pos()),
                step.pos(),
                step.pos(),
                step.pos()
        );
        Object job = newRoadConstructionJob(level, "manual|test|hammer_target", plan, Set.of(step.pos().asLong()));

        List<BlockPos> remaining = invokeRemainingRoadGhostPositions(level, job);

        assertEquals(List.of(step.pos()), remaining);
    }

    @Test
    void attemptedRoadBuildStepDoesNotCountAsConsumedWithoutConfirmedCompletion() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                RoadGeometryPlanner.RoadBuildPhase.DECK
        );

        Set<Long> consumed = invokeConsumedRoadBuildStepKeys(
                new RoadPlacementPlan(
                        List.of(new BlockPos(0, 64, 0)),
                        null,
                        null,
                        null,
                        null,
                        List.of(new RoadGeometryPlanner.GhostRoadBlock(step.pos(), step.state())),
                        List.of(step),
                        List.of(),
                        List.of(step.pos()),
                        step.pos(),
                        step.pos(),
                        step.pos()
                ),
                Set.of(),
                Set.of(step.pos().asLong())
        );

        assertTrue(consumed.isEmpty());
    }

    @Test
    void roadJobStateRoundTripsAttemptedStepPositions() {
        ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
                "road-a",
                "minecraft:overworld",
                "owner",
                List.of(new BlockPos(0, 64, 0).asLong(), new BlockPos(1, 64, 0).asLong()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0.0D,
                false,
                false,
                0,
                false,
                List.of(new BlockPos(3, 65, 3).asLong())
        );

        ConstructionRuntimeSavedData.RoadJobState loaded = ConstructionRuntimeSavedData.RoadJobState.load(state.save());

        assertEquals(List.of(new BlockPos(3, 65, 3).asLong()), loaded.attemptedStepPositions());
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

        assertTrue(ghostPositions.contains(new BlockPos(0, 65, 0)));
        assertTrue(ghostPositions.contains(new BlockPos(0, 65, 1)));
        assertTrue(ghostPositions.contains(new BlockPos(0, 64, 0)));
        assertTrue(ghostBlocks.stream().anyMatch(block ->
                        block.pos().getX() == 0
                                && block.pos().getZ() == 2
                                && block.state().is(Blocks.OAK_FENCE)),
                () -> ghostBlocks.toString());
        assertTrue(ghostBlocks.stream().anyMatch(block ->
                        block.pos().getX() == 0
                                && block.pos().getZ() == 2
                                && block.state().is(Blocks.LANTERN)),
                () -> ghostBlocks.toString());
        assertEquals(ghostPositions, buildStepPositions);
        assertTrue(ownedBlocks.contains(new BlockPos(0, 63, 0)));
        assertTrue(ownedBlocks.contains(new BlockPos(0, 62, 0)));
    }

    @Test
    void corridorArtifactsIncludeExcavationAndClearanceOwnership() {
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                List.of(new BlockPos(0, 64, 0)),
                List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 70, 0),
                                RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH,
                                List.of(new BlockPos(0, 70, 0)),
                                List.of(new BlockPos(0, 69, 0)),
                                List.of(new BlockPos(0, 71, 0), new BlockPos(0, 72, 0)),
                                List.of(),
                                List.of(new BlockPos(0, 68, 0)),
                                List.of(new BlockPos(0, 67, 0))
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
                ghostBlocks -> List.of()
        );

        List<BlockPos> ownedBlocks = invokeArtifactsOwnedBlocks(artifacts);

        assertTrue(ownedBlocks.contains(new BlockPos(0, 69, 0)));
        assertTrue(ownedBlocks.contains(new BlockPos(0, 71, 0)));
        assertTrue(ownedBlocks.contains(new BlockPos(0, 72, 0)));
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
    void roadBuildStepCompletionDoesNotTreatSameFamilyVariantsAsPlaced() {
        RoadGeometryPlanner.RoadBuildStep slabStep = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 65, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        RoadGeometryPlanner.RoadBuildStep stairStep = new RoadGeometryPlanner.RoadBuildStep(
                1,
                new BlockPos(1, 65, 0),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState()
        );

        assertFalse(invokeRoadBuildStepPlaced(Blocks.STONE_BRICK_STAIRS.defaultBlockState(), slabStep));
        assertFalse(invokeRoadBuildStepPlaced(Blocks.STONE_BRICKS.defaultBlockState(), slabStep));
        assertFalse(invokeRoadBuildStepPlaced(Blocks.STONE_BRICK_SLAB.defaultBlockState(), stairStep));
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
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 69, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(5, 69, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(10, 69, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new BlockPos(0, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(10, 68, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(0, 2))
        );

        assertTrue(ghosts.stream().anyMatch(block -> block.state().is(Blocks.COBBLESTONE_WALL)));
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

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRemainingRoadGhostPositions(ServerLevel level, Object job) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod(
                            "remainingRoadGhostPositions",
                            ServerLevel.class,
                            Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadConstructionJob")
                    );
            method.setAccessible(true);
            return List.copyOf((Set<BlockPos>) method.invoke(null, level, job));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect hammer-targetable road ghost projection", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RoadGeometryPlanner.RoadBuildStep> invokeRemainingRoadBuildSteps(List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                                                                                         Set<Long> completedStepKeys,
                                                                                         Set<Long> attemptedStepKeys) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("remainingRoadBuildSteps", List.class, Set.class, Set.class);
            method.setAccessible(true);
            return (List<RoadGeometryPlanner.RoadBuildStep>) method.invoke(null, buildSteps, completedStepKeys, attemptedStepKeys);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect attempted road build-step filtering", ex);
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

    private static Object newRoadConstructionJob(ServerLevel level,
                                                 String roadId,
                                                 RoadPlacementPlan plan,
                                                 Set<Long> attemptedStepKeys) {
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
                    UUID.randomUUID(),
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
                    attemptedStepKeys
            );
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to create reflected road construction job", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> invokeConsumedRoadBuildStepKeys(RoadPlacementPlan plan,
                                                             Set<Long> completedStepKeys,
                                                             Set<Long> attemptedStepKeys) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("consumedRoadBuildStepKeys", RoadPlacementPlan.class, Set.class, Set.class);
            method.setAccessible(true);
            return (Set<Long>) method.invoke(null, plan, completedStepKeys, attemptedStepKeys);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect consumed road build-step filtering", ex);
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
    private static List<RoadGeometryPlanner.RoadBuildStep> invokeToBuildSteps(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("toBuildSteps", List.class);
            method.setAccessible(true);
            return (List<RoadGeometryPlanner.RoadBuildStep>) method.invoke(null, ghostBlocks);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect phase-sorted road build steps", ex);
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

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to allocate test instance", ex);
        }
    }

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
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
