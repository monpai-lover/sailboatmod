package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadLifecycleServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rollbackTargetsAllOwnedRoadBlocksInReverseOrder() {
        List<BlockPos> removed = RoadLifecycleService.ownedBlocksRemovalOrderForTest(
                List.of(new BlockPos(1, 65, 1), new BlockPos(1, 64, 1), new BlockPos(1, 63, 1))
        );

        assertEquals(new BlockPos(1, 63, 1), removed.get(0));
        assertTrue(removed.contains(new BlockPos(1, 64, 1)));
        assertEquals(new BlockPos(1, 65, 1), removed.get(removed.size() - 1));
    }

    @Test
    void cancelRejectsWrongLevelWithoutDroppingActiveRoadJob() {
        ServerLevel activeLevel = allocate(ServerLevel.class);
        ServerLevel otherLevel = allocate(ServerLevel.class);
        Object job = newRoadConstructionJob(activeLevel, "manual|town:a|town:b");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put("manual|town:a|town:b", job);
        try {
            assertFalse(invokeCancelActiveRoadConstruction(otherLevel, "manual|town:a|town:b"));
            assertTrue(activeRoads.containsKey("manual|town:a|town:b"));
        } finally {
            restoreMapEntry(activeRoads, "manual|town:a|town:b", previous);
        }
    }

    @Test
    void clearActiveRoadRuntimeStateRemovesWorkersAndQueuedHammerCredits() {
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeWorkers = readStaticMap("ACTIVE_ROAD_WORKERS");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeCredits = readStaticMap("ACTIVE_ROAD_HAMMER_CREDITS");

        Object previousRoad = activeRoads.put("manual|town:a|town:b", newRoadConstructionJob(allocate(ServerLevel.class), "manual|town:a|town:b"));
        Object previousWorkers = activeWorkers.put("manual|town:a|town:b", new ConcurrentHashMap<>());
        Object previousCredits = activeCredits.put("manual|town:a|town:b", 3);
        try {
            invokeClearActiveRoadRuntimeState("manual|town:a|town:b");
            assertFalse(activeRoads.containsKey("manual|town:a|town:b"));
            assertFalse(activeWorkers.containsKey("manual|town:a|town:b"));
            assertFalse(activeCredits.containsKey("manual|town:a|town:b"));
        } finally {
            restoreMapEntry(activeRoads, "manual|town:a|town:b", previousRoad);
            restoreMapEntry(activeWorkers, "manual|town:a|town:b", previousWorkers);
            restoreMapEntry(activeCredits, "manual|town:a|town:b", previousCredits);
        }
    }

    @Test
    void widenedRoadbedTopUsesRoadSurfaceFootprintNotLampPosts() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 72, 0), Blocks.COBBLESTONE_WALL.defaultBlockState())
        );

        List<BlockPos> roadbedTop = invokeRoadbedTopFromFootprint(ghostBlocks);

        assertTrue(roadbedTop.contains(new BlockPos(0, 71, 0)));
        assertTrue(roadbedTop.contains(new BlockPos(1, 71, 0)));
        assertTrue(roadbedTop.contains(new BlockPos(-1, 71, 0)));
        assertEquals(3, roadbedTop.size());
    }

    @Test
    void widenedOwnedBlocksIncludeFootprintAndTerrainEditsForCleanup() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        List<BlockPos> terrainEdits = List.of(
                new BlockPos(-1, 69, 0),
                new BlockPos(1, 69, 0)
        );

        List<BlockPos> owned = invokeOwnedRoadBlocksFromFootprint(ghostBlocks, terrainEdits);
        List<BlockPos> removalOrder = RoadLifecycleService.ownedBlocksRemovalOrderForTest(owned);

        assertEquals(Set.of(
                new BlockPos(-1, 70, 0),
                new BlockPos(0, 70, 0),
                new BlockPos(1, 70, 0),
                new BlockPos(-1, 69, 0),
                new BlockPos(1, 69, 0)
        ), Set.copyOf(owned));
        assertEquals(owned.get(owned.size() - 1), removalOrder.get(0));
        assertEquals(owned.get(0), removalOrder.get(removalOrder.size() - 1));
    }

    @Test
    void partialPersistedOwnedBlocksFallBackToWidenedFootprintCoverage() {
        RoadPlacementPlan plan = widenedPlanWithPartialOwnedBlocks();

        List<BlockPos> resolved = invokeResolvedRoadOwnedBlocks(null, plan);

        assertEquals(Set.of(
                new BlockPos(-1, 70, 0),
                new BlockPos(0, 70, 0),
                new BlockPos(1, 70, 0)
        ), Set.copyOf(resolved));
    }

    @Test
    void snapshotRoadOwnershipUsesResolvedCoverageForActivePlans() {
        ServerLevel level = allocate(ServerLevel.class);
        RoadPlacementPlan plan = widenedPlanWithPartialOwnedBlocks();
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        String roadId = "manual|town:snapshot_a|town:snapshot_b";
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan));
        try {
            Map<String, List<BlockPos>> snapshot = StructureConstructionManager.snapshotRoadOwnedBlocks(level);
            assertTrue(snapshot.containsKey(roadId));
            assertEquals(Set.of(
                    new BlockPos(-1, 70, 0),
                    new BlockPos(0, 70, 0),
                    new BlockPos(1, 70, 0)
            ), Set.copyOf(snapshot.get(roadId)));
        } finally {
            restoreMapEntry(activeRoads, roadId, previous);
        }
    }

    @Test
    void liveFoundationFallbackCapturesSupportBlocksUnderWidenedRoadColumns() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();
        states.put(new BlockPos(-1, 69, 0), Blocks.COBBLESTONE.defaultBlockState());
        states.put(new BlockPos(-1, 68, 0), Blocks.COBBLESTONE.defaultBlockState());
        states.put(new BlockPos(0, 69, 0), Blocks.SANDSTONE.defaultBlockState());
        states.put(new BlockPos(1, 69, 0), Blocks.MUD_BRICKS.defaultBlockState());
        states.put(new BlockPos(1, 68, 0), Blocks.SPRUCE_FENCE.defaultBlockState());
        Function<BlockPos, net.minecraft.world.level.block.state.BlockState> lookup =
                pos -> states.getOrDefault(pos, Blocks.DIRT.defaultBlockState());

        List<BlockPos> captured = invokeCaptureFoundationFromLookup(ghostBlocks, lookup);

        assertEquals(Set.of(
                new BlockPos(-1, 69, 0),
                new BlockPos(-1, 68, 0),
                new BlockPos(0, 69, 0),
                new BlockPos(1, 69, 0),
                new BlockPos(1, 68, 0)
        ), Set.copyOf(captured));
    }

    private static RoadPlacementPlan widenedPlanWithPartialOwnedBlocks() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = List.of(
                new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        return new RoadPlacementPlan(
                List.of(new BlockPos(0, 70, 0), new BlockPos(1, 70, 0)),
                null,
                null,
                null,
                null,
                ghostBlocks,
                buildSteps,
                List.of(),
                List.of(new BlockPos(0, 70, 0)),
                null,
                null,
                null
        );
    }

    private static List<BlockPos> invokeRoadbedTopFromFootprint(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("deriveRoadbedTopFromRoadSurfaceFootprint", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, ghostBlocks);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect roadbed-top footprint derivation", ex);
        }
    }

    private static List<BlockPos> invokeOwnedRoadBlocksFromFootprint(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                                      List<BlockPos> terrainEdits) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("collectOwnedRoadBlocks", List.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, ghostBlocks, terrainEdits);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect widened road owned-block collection", ex);
        }
    }

    private static List<BlockPos> invokeResolvedRoadOwnedBlocks(ServerLevel level, RoadPlacementPlan plan) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadOwnedBlocks", ServerLevel.class, RoadPlacementPlan.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, level, plan);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect resolved road ownership fallback", ex);
        }
    }

    private static List<BlockPos> invokeCaptureFoundationFromLookup(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                                    Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blockLookup) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("captureRoadFoundationFromStateLookup", List.class, Function.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, ghostBlocks, blockLookup);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect live support/foundation fallback capture", ex);
        }
    }

    private static boolean invokeCancelActiveRoadConstruction(ServerLevel level, String roadId) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("cancelActiveRoadConstruction", ServerLevel.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, level, roadId);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect cancel road lifecycle", ex);
        }
    }

    private static void invokeClearActiveRoadRuntimeState(String roadId) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("clearActiveRoadRuntimeState", String.class);
            method.setAccessible(true);
            method.invoke(null, roadId);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect active road runtime cleanup", ex);
        }
    }

    private static Object newRoadConstructionJob(ServerLevel level, String roadId) {
        return newRoadConstructionJob(level, roadId, new RoadPlacementPlan(List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), null, null, null));
    }

    private static Object newRoadConstructionJob(ServerLevel level, String roadId, RoadPlacementPlan plan) {
        try {
            Class<?> jobClass = Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadConstructionJob");
            Constructor<?> constructor = jobClass.getDeclaredConstructors()[0];
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
                    0,
                    0.0D
            );
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to construct test road job", ex);
        }
    }

    private static Field staticField(String name) {
        try {
            Field field = Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager").getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to access manager field " + name, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readStaticMap(String name) {
        try {
            return (Map<String, Object>) staticField(name).get(null);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("Unable to read manager map " + name, ex);
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
            throw new AssertionError("Unable to allocate test instance for " + type.getName(), ex);
        }
    }

    private static void restoreMapEntry(Map<String, Object> map, String key, Object previous) {
        if (previous == null) {
            map.remove(key);
            return;
        }
        map.put(key, previous);
    }
}
