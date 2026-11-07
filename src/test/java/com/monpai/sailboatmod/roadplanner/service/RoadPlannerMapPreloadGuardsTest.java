package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerMapPreloadGuardsTest {
    private RoadPlannerMapPreloadRequestPacket request(RoadPlannerMapPreloadRequestPacket.Purpose purpose, BlockPos a, BlockPos b) {
        return new RoadPlannerMapPreloadRequestPacket(UUID.randomUUID(), 1, purpose, "earth", "minecraft:overworld", a, b, List.of(), 1);
    }
    @Test void boundsAreCheckedBeforeSelectionEnumeration() {
        var viewport = RoadPlannerMapPreloadRequestPacket.Purpose.VIEWPORT;
        var force = RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER;
        assertTrue(request(viewport, BlockPos.ZERO, new BlockPos(2047, 64, 2047)).validClientRequest());
        assertFalse(request(viewport, BlockPos.ZERO, new BlockPos(2048, 64, 2048)).validClientRequest());
        assertFalse(request(force, new BlockPos(-30_000_000, 0, -30_000_000), new BlockPos(30_000_000, 0, 30_000_000)).validClientRequest());
        assertFalse(request(force, new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0)).validClientRequest());
        assertFalse(request(RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH, BlockPos.ZERO, BlockPos.ZERO).validClientRequest());
    }
    @Test void excessNodesAndProtocolMismatchAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RoadPlannerMapPreloadRequestPacket(UUID.randomUUID(), 1,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD, "earth", "minecraft:overworld", BlockPos.ZERO, BlockPos.ZERO,
                Collections.nCopies(RoadPlannerMapPreloadRequestPacket.MAX_ROUTE_NODES + 1, BlockPos.ZERO), 1));
        var packet = new RoadPlannerMapPreloadRequestPacket(UUID.randomUUID(), 1, RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "earth", "minecraft:overworld", BlockPos.ZERO, BlockPos.ZERO, List.of(), 0);
        assertFalse(packet.validClientRequest());
    }
    @Test void playerIdentityIsPartOfTheJobKey() throws Exception {
        Class<?> type = Class.forName(RoadPlannerMapPreloadService.class.getName() + "$JobKey");
        var constructor = type.getDeclaredConstructor(UUID.class, UUID.class, RoadPlannerMapPreloadRequestPacket.Purpose.class);
        constructor.setAccessible(true);
        UUID session = UUID.randomUUID();
        Object one = constructor.newInstance(UUID.randomUUID(), session, RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER);
        Object two = constructor.newInstance(UUID.randomUUID(), session, RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER);
        assertNotEquals(one, two);
    }
    @Test void rotatingSessionsCannotExceedPerPlayerOrGlobalJobCaps() throws Exception {
        RoadPlannerMapPreloadService service = new RoadPlannerMapPreloadService();
        Class<?> keyType = Class.forName(RoadPlannerMapPreloadService.class.getName() + "$JobKey");
        Class<?> jobType = Class.forName(RoadPlannerMapPreloadService.class.getName() + "$ActiveJob");
        var keyConstructor = keyType.getDeclaredConstructor(UUID.class, UUID.class, RoadPlannerMapPreloadRequestPacket.Purpose.class);
        var jobConstructor = jobType.getDeclaredConstructor(UUID.class, UUID.class, long.class,
                RoadPlannerMapPreloadRequestPacket.Purpose.class, String.class, String.class, RoadPlannerMapPreloadJob.class, java.util.Map.class);
        keyConstructor.setAccessible(true);
        jobConstructor.setAccessible(true);
        var replace = RoadPlannerMapPreloadService.class.getDeclaredMethod("replaceJob", keyType, jobType);
        replace.setAccessible(true);
        var field = RoadPlannerMapPreloadService.class.getDeclaredField("jobs");
        field.setAccessible(true);
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            UUID player = i < 20 ? owner : UUID.randomUUID();
            UUID session = UUID.randomUUID();
            var purpose = RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER;
            Object key = keyConstructor.newInstance(player, session, purpose);
            var plan = new RoadMapRoutePreloadPlan(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, List.of(), 0, 0);
            var job = new RoadPlannerMapPreloadJob(session, i, purpose, "earth", "minecraft:overworld", plan);
            Object active = jobConstructor.newInstance(player, session, (long) i, purpose, "earth", "minecraft:overworld", job, new java.util.LinkedHashMap<>());
            replace.invoke(service, key, active);
            int size = ((java.util.Map<?, ?>) field.get(service)).size();
            assertTrue(size <= RoadPlannerMapPreloadService.MAX_JOBS);
            if (i < 20) assertTrue(size <= RoadPlannerMapPreloadService.MAX_JOBS_PER_PLAYER);
        }
    }

    @Test void rasterStopsAtColumnBudgetAndTimeDeadlineThenResumes() {
        RoadMapRegion region = RoadMapRegion.centeredOn(new BlockPos(8, 0, 8), 16, MapLod.LOD_1);
        RoadPlannerMapRaster raster = new RoadPlannerMapRaster(region);
        AtomicInteger calls = new AtomicInteger();
        RoadMapColumnSampler sampler = (x, z) -> { calls.incrementAndGet(); return new RoadMapColumnSample(x, 64, z, 0xFF778899, false, 0, 64); };
        assertEquals(8, raster.advance(sampler, 8, 100, () -> 0));
        assertEquals(8, calls.get());
        assertFalse(raster.complete());
        assertEquals(0, raster.advance(sampler, 100, 100, () -> 100));
        assertThrows(IllegalStateException.class, () -> raster.snapshot(1));
        while (!raster.complete()) raster.advance(sampler, 32, 100, () -> 0);
        assertEquals(256, calls.get());
        assertEquals(256, raster.snapshot(1).argbPixels().length);
        assertTrue(raster.snapshot(1).columns().isEmpty());
    }
    @Test void mainThreadPreloadingDoesNotJoinAFutureOrUseBlockingChunkLookup() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerMapPreloadService.java"));
        assertFalse(source.contains(".join()"));
        assertFalse(source.contains("getChunkSource().getChunk("));
        assertTrue(source.contains("packet.validClientRequest()"));
        assertTrue(source.contains("player.hasPermissions(2)"));
    }
}
