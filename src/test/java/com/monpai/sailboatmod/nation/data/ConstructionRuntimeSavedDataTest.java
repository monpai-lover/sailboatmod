package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadLegacyJobRebuilder;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionRuntimeSavedDataTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void savesAndLoadsPlanCentricRoadJobs() {
        ConstructionRuntimeSavedData data = new ConstructionRuntimeSavedData();
        BlockPos start = new BlockPos(12, 64, 8);
        BlockPos end = new BlockPos(13, 64, 8);
        ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
                "road:test",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000111").toString(),
                List.of(start.asLong(), end.asLong()),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                start.above().asLong(),
                                blockStatePayload("minecraft:stone_bricks")
                        ),
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                end.above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                0,
                                start.above().asLong(),
                                blockStatePayload("minecraft:stone_bricks")
                        ),
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                1,
                                end.above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                List.of(start.above().asLong(), start.asLong()),
                1,
                false
        );

        data.putRoadJob(state);

        ConstructionRuntimeSavedData loaded = ConstructionRuntimeSavedData.load(data.save(new CompoundTag()));
        ConstructionRuntimeSavedData.RoadJobState restored = loaded.getRoadJobs().iterator().next();

        assertFalse(restored.isLegacyPathOnly());
        assertEquals("road:test", restored.roadId());
        assertEquals(2, restored.centerPath().size());
        assertEquals(2, restored.ghostBlocks().size());
        assertEquals(2, restored.buildSteps().size());
        assertEquals(List.of(start.above().asLong(), start.asLong()), restored.ownedBlocks());
        assertEquals(1, restored.placedStepCount());
        assertEquals("minecraft:stone_bricks", restored.ghostBlocks().get(0).statePayload().getString("Name"));
        assertEquals(List.of(0, 1), restored.buildSteps().stream().map(ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState::order).toList());
    }

    @Test
    void loadsLegacyPathOnlyRoadJobsWithoutDroppingThePath() {
        CompoundTag root = new CompoundTag();
        CompoundTag road = new CompoundTag();
        road.putString("RoadId", "legacy-road");
        road.putString("DimensionId", "minecraft:overworld");
        road.putString("OwnerUuid", UUID.fromString("00000000-0000-0000-0000-000000000222").toString());
        road.putLongArray("Path", new long[] {
                new BlockPos(20, 70, 20).asLong(),
                new BlockPos(21, 70, 20).asLong(),
                new BlockPos(22, 70, 20).asLong()
        });
        ListTag roads = new ListTag();
        roads.add(road);
        root.put("RoadJobs", roads);

        ConstructionRuntimeSavedData loaded = ConstructionRuntimeSavedData.load(root);

        ConstructionRuntimeSavedData.RoadJobState restored = loaded.getRoadJobs().iterator().next();
        assertTrue(restored.isLegacyPathOnly());
        assertEquals("legacy-road", restored.roadId());
        assertEquals(3, restored.centerPath().size());
        assertTrue(restored.ghostBlocks().isEmpty());
        assertTrue(restored.buildSteps().isEmpty());
        assertEquals(0, restored.placedStepCount());
    }

    @Test
    void rebuildsLegacyPathOnlyRoadJobsAndInfersCompletedSteps() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 1)
        );
        ConstructionRuntimeSavedData.RoadJobState legacyState = new ConstructionRuntimeSavedData.RoadJobState(
                "legacy-road",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000333").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(),
                List.of(),
                0,
                true
        );
        RoadPlacementPlan rebuiltPlan = new RoadPlacementPlan(
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(1),
                centerPath.get(1),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(centerPath.get(0).above(), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(centerPath.get(1).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, centerPath.get(0).above(), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, centerPath.get(1).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                centerPath.get(0),
                centerPath.get(1),
                centerPath.get(0)
        );

        RoadLegacyJobRebuilder.RebuildResult result = RoadLegacyJobRebuilder.rebuild(
                legacyState,
                path -> rebuiltPlan,
                plan -> "",
                step -> step.order() == 0
        );

        assertTrue(result.success());
        assertNotNull(result.plan());
        assertEquals(1, result.placedStepCount());
        assertEquals(2, result.plan().buildSteps().size());
        assertTrue(result.failureReason().isBlank());
    }

    @Test
    void rejectsLegacyRoadJobsWhenRebuiltPlanIsUnsafe() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 1)
        );
        ConstructionRuntimeSavedData.RoadJobState legacyState = new ConstructionRuntimeSavedData.RoadJobState(
                "legacy-road",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000444").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(),
                List.of(),
                0,
                true
        );

        RoadLegacyJobRebuilder.RebuildResult result = RoadLegacyJobRebuilder.rebuild(
                legacyState,
                path -> new RoadPlacementPlan(path, path.get(0), path.get(0), path.get(1), path.get(1), List.of(), List.of(), List.of(), null, null, null),
                plan -> "",
                step -> false
        );

        assertFalse(result.success());
        assertEquals(0, result.placedStepCount());
        assertTrue(result.plan() == null || result.plan().buildSteps().isEmpty());
        assertTrue(result.failureReason().contains("buildSteps"));
    }

    @Test
    void rejectsLegacyRoadJobsWhenCurrentWorldValidationFails() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 1)
        );
        ConstructionRuntimeSavedData.RoadJobState legacyState = new ConstructionRuntimeSavedData.RoadJobState(
                "legacy-road",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000555").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(),
                List.of(),
                0,
                true
        );
        RoadPlacementPlan rebuiltPlan = new RoadPlacementPlan(
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(1),
                centerPath.get(1),
                List.of(new RoadGeometryPlanner.GhostRoadBlock(centerPath.get(0).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, centerPath.get(0).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(),
                centerPath.get(0),
                centerPath.get(1),
                centerPath.get(0)
        );

        RoadLegacyJobRebuilder.RebuildResult result = RoadLegacyJobRebuilder.rebuild(
                legacyState,
                path -> rebuiltPlan,
                plan -> "blocked by world changes",
                step -> false
        );

        assertFalse(result.success());
        assertEquals(0, result.placedStepCount());
        assertTrue(result.failureReason().contains("blocked by world changes"));
    }

    @Test
    void rejectsLegacyRoadJobsWhenCompletedStepsContainAGap() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 1),
                new BlockPos(3, 64, 1)
        );
        ConstructionRuntimeSavedData.RoadJobState legacyState = new ConstructionRuntimeSavedData.RoadJobState(
                "legacy-road",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000666").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(),
                List.of(),
                0,
                true
        );
        RoadPlacementPlan rebuiltPlan = new RoadPlacementPlan(
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(2),
                centerPath.get(2),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(centerPath.get(0).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(centerPath.get(1).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(centerPath.get(2).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, centerPath.get(0).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, centerPath.get(1).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(2, centerPath.get(2).above(), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                centerPath.get(0),
                centerPath.get(2),
                centerPath.get(1)
        );

        RoadLegacyJobRebuilder.RebuildResult result = RoadLegacyJobRebuilder.rebuild(
                legacyState,
                path -> rebuiltPlan,
                plan -> "",
                step -> step.order() == 0 || step.order() == 2
        );

        assertFalse(result.success());
        assertEquals(0, result.placedStepCount());
        assertTrue(result.failureReason().contains("gap"));
    }

    @Test
    void rejectsPersistedRoadJobsWhenRoadRecordIsMissing() throws Exception {
        ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
                "road:missing",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000777").toString(),
                List.of(new BlockPos(1, 64, 1).asLong(), new BlockPos(2, 64, 1).asLong()),
                List.of(new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                        new BlockPos(1, 65, 1).asLong(),
                        blockStatePayload("minecraft:stone_brick_slab")
                )),
                List.of(new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                        0,
                        new BlockPos(1, 65, 1).asLong(),
                        blockStatePayload("minecraft:stone_brick_slab")
                )),
                0,
                false
        );

        String reason = invokePersistedRoadValidation(state, null);

        assertTrue(reason.contains("missing road network"));
    }

    @Test
    void rejectsMalformedNewFormatRoadJobsBeforeRestore() throws Exception {
        List<BlockPos> centerPath = List.of(
                new BlockPos(10, 70, 10),
                new BlockPos(11, 70, 10)
        );
        ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
                "road:malformed",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000888").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                centerPath.get(0).above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                2,
                                centerPath.get(0).above().asLong(),
                                new CompoundTag()
                        )
                ),
                4,
                false
        );
        RoadNetworkRecord road = new RoadNetworkRecord(
                "road:malformed",
                "nation_a",
                "town_a",
                "minecraft:overworld",
                "town:alpha",
                "town:beta",
                centerPath,
                123L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );

        String reason = invokePersistedRoadValidation(state, road);

        assertTrue(reason.contains("placedStepCount")
                || reason.contains("buildSteps")
                || reason.contains("state payload"));
    }

    @Test
    void acceptsWellFormedPersistedRoadJobsForKnownRoads() throws Exception {
        List<BlockPos> centerPath = List.of(
                new BlockPos(30, 70, 30),
                new BlockPos(31, 70, 30)
        );
        ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
                "road:valid",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000000999").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                centerPath.get(0).above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        ),
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                centerPath.get(1).above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                0,
                                centerPath.get(0).above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        ),
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                1,
                                centerPath.get(1).above().asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                1,
                false
        );
        RoadNetworkRecord road = new RoadNetworkRecord(
                "road:valid",
                "nation_a",
                "town_a",
                "minecraft:overworld",
                "town:alpha",
                "town:beta",
                centerPath,
                456L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );

        String reason = invokePersistedRoadValidation(state, road);

        assertTrue(reason.isBlank());
    }

    @Test
    void acceptsPersistedRoadJobsWhoseSavedGeometryDiffersFromFreshlyDerivedPlan() throws Exception {
        List<BlockPos> centerPath = List.of(
                new BlockPos(40, 70, 40),
                new BlockPos(41, 70, 40)
        );
        ConstructionRuntimeSavedData.RoadJobState state = new ConstructionRuntimeSavedData.RoadJobState(
                "road:off_geometry",
                "minecraft:overworld",
                UUID.fromString("00000000-0000-0000-0000-000000001010").toString(),
                centerPath.stream().map(BlockPos::asLong).toList(),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                new BlockPos(40, 71, 39).asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        ),
                        new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                                new BlockPos(41, 71, 39).asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                List.of(
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                0,
                                new BlockPos(40, 71, 39).asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        ),
                        new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                                1,
                                new BlockPos(41, 71, 39).asLong(),
                                blockStatePayload("minecraft:stone_brick_slab")
                        )
                ),
                0,
                false
        );
        RoadPlacementPlan expectedPlan = new RoadPlacementPlan(
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(1),
                centerPath.get(1),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(40, 71, 40), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(41, 71, 40), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(40, 71, 40), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(41, 71, 40), net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                centerPath.get(0),
                centerPath.get(1),
                centerPath.get(0)
        );

        String reason = invokePersistedRoadPlanValidation(state, expectedPlan);

        assertTrue(reason.isBlank());
    }

    private static CompoundTag blockStatePayload(String blockName) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockName);
        return tag;
    }

    private static String invokePersistedRoadValidation(ConstructionRuntimeSavedData.RoadJobState state,
                                                        RoadNetworkRecord road) throws Exception {
        java.lang.reflect.Method method = StructureConstructionManager.class.getDeclaredMethod(
                "validatePersistedRoadJobState",
                ConstructionRuntimeSavedData.RoadJobState.class,
                RoadNetworkRecord.class
        );
        method.setAccessible(true);
        return (String) method.invoke(null, state, road);
    }

    private static String invokePersistedRoadPlanValidation(ConstructionRuntimeSavedData.RoadJobState state,
                                                            RoadPlacementPlan expectedPlan) throws Exception {
        java.lang.reflect.Method method = StructureConstructionManager.class.getDeclaredMethod(
                "validatePersistedRoadJobMatchesPlan",
                ConstructionRuntimeSavedData.RoadJobState.class,
                RoadPlacementPlan.class
        );
        method.setAccessible(true);
        return (String) method.invoke(null, state, expectedPlan);
    }
}
