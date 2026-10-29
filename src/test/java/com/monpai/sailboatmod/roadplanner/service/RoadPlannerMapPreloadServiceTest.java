package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadPlannerMapPreloadServiceTest {
    @Test
    void automaticRoutePreloadNeverForcesMissingChunksButExplicitForceRemainsDistinct() {
        assertFalse(RoadPlannerMapPreloadService.forcesMissingChunksForTest(
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
        assertTrue(RoadPlannerMapPreloadService.forcesMissingChunksForTest(
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER));
    }

    @Test
    void actualAutomaticMapEntryAndRouteRequestsLeaveCorridorWorkToTheViewportStream() throws Exception {
        RoadPlannerMapPreloadService service = new RoadPlannerMapPreloadService();
        Method resolve = RoadPlannerMapPreloadService.class.getDeclaredMethod("resolvePlan", ServerLevel.class, RoadPlannerMapPreloadRequestPacket.class);
        resolve.setAccessible(true);
        for (var purpose : List.of(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD)) {
            var nodes = List.of(BlockPos.ZERO, new BlockPos(4096, 64, 4096));
            var request = new RoadPlannerMapPreloadRequestPacket(UUID.randomUUID(), 1, purpose, "earth", "minecraft:overworld",
                    nodes.get(0), nodes.get(1), nodes, RoadPlannerMapPreloadRequestPacket.PROTOCOL_VERSION);
            var plan = (RoadMapRoutePreloadPlan) resolve.invoke(service, null, request);
            assertTrue(plan.chunks().isEmpty(), "opening/editing a road must not enumerate a complete corridor before the streaming preview starts");
        }
    }

    @Test
    void replacingActiveForceRenderJobReleasesForcedChunksFromPreviousJob() throws Exception {
        RoadPlannerMapPreloadService service = new RoadPlannerMapPreloadService();
        UUID sessionId = UUID.randomUUID();
        RoadPlannerMapPreloadRequestPacket.Purpose purpose = RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER;
        Object key = newJobKey(sessionId, purpose);

        Map<RoadPlannerTileKey, Set<Long>> oldForcedChunks = new LinkedHashMap<>();
        oldForcedChunks.put(
                new RoadPlannerTileKey("world_a", "minecraft:overworld", MapLod.LOD_1, 0, 0),
                new LinkedHashSet<>(List.of(ChunkPos.asLong(0, 0))));
        Object oldJob = newActiveJob(sessionId, 1L, purpose, oldForcedChunks);
        Object replacementJob = newActiveJob(sessionId, 2L, purpose, new LinkedHashMap<>());

        jobs(service).put(key, oldJob);

        Method replaceJob = RoadPlannerMapPreloadService.class.getDeclaredMethod(
                "replaceJob",
                key.getClass(),
                replacementJob.getClass());
        replaceJob.setAccessible(true);
        replaceJob.invoke(service, key, replacementJob);

        assertTrue(oldForcedChunks.isEmpty());
        assertEquals(1, jobs(service).size());
        assertSame(replacementJob, jobs(service).get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> jobs(RoadPlannerMapPreloadService service) throws Exception {
        Field jobs = RoadPlannerMapPreloadService.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        return (Map<Object, Object>) jobs.get(service);
    }

    private static Object newJobKey(UUID sessionId, RoadPlannerMapPreloadRequestPacket.Purpose purpose) throws Exception {
        Class<?> type = nestedClass("JobKey");
        Constructor<?> constructor = type.getDeclaredConstructor(UUID.class, RoadPlannerMapPreloadRequestPacket.Purpose.class);
        constructor.setAccessible(true);
        return constructor.newInstance(sessionId, purpose);
    }

    private static Object newActiveJob(UUID sessionId,
                                       long requestId,
                                       RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                       Map<RoadPlannerTileKey, Set<Long>> forcedChunks) throws Exception {
        RoadPlannerMapPreloadJob preloadJob = new RoadPlannerMapPreloadJob(
                sessionId,
                requestId,
                purpose,
                "world_a",
                "minecraft:overworld",
                new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE, List.of(), 0, 0));
        Class<?> type = nestedClass("ActiveJob");
        Constructor<?> constructor = type.getDeclaredConstructor(
                UUID.class,
                UUID.class,
                long.class,
                RoadPlannerMapPreloadRequestPacket.Purpose.class,
                String.class,
                String.class,
                RoadPlannerMapPreloadJob.class,
                Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                UUID.randomUUID(),
                sessionId,
                requestId,
                purpose,
                "world_a",
                "minecraft:overworld",
                preloadJob,
                forcedChunks);
    }

    private static Class<?> nestedClass(String simpleName) {
        for (Class<?> nested : RoadPlannerMapPreloadService.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }
        throw new AssertionError("Missing nested class " + simpleName);
    }
}
