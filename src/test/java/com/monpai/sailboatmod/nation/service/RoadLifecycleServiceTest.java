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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    void widenedRoadbedTopDerivesFromFootprintColumnsNotCenterPathOnly() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 72, 0), Blocks.COBBLESTONE_WALL.defaultBlockState())
        );

        List<BlockPos> roadbedTop = invokeRoadbedTopFromFootprint(ghostBlocks);

        assertTrue(roadbedTop.contains(new BlockPos(0, 73, 0)));
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

    private static List<BlockPos> invokeRoadbedTopFromFootprint(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("deriveRoadbedTopFromGhostFootprint", List.class);
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
                    new RoadPlacementPlan(List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), null, null, null),
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
