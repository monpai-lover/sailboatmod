package com.monpai.sailboatmod.route.water;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AsyncTerrainWiringTest {
    @Test void hierarchicalSamplingPollsRequestOwnedFuturesAndClosesThem()throws Exception {
        String service=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/route/water/WaterAutoRouteService.java"));
        String stage=service.substring(service.indexOf("private static WaterRouteResult<List<BlockPos>> runHierarchicalNbt("),service.indexOf("static boolean useHierarchicalTerrain"));
        assertTrue(stage.contains("try (RouteTerrainSource"));assertTrue(stage.contains("map.withAsyncTerrain(terrain)"));
        assertTrue(stage.contains("terrain.surface(x, z, spacing)"));assertTrue(stage.contains("terrain.read(cx, cz)"));
        assertFalse(stage.contains("predictor.gate().request"),"coarse LOD must not wait for a full 512x512 tile");
        assertTrue(stage.contains("terrain.diagnostic()"));
    }
    @Test void deferredCacheNeverWaitsOnAFuture()throws Exception {
        String cache=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/route/water/DeferredTerrainCache.java"));
        assertTrue(cache.contains("getNow(null)"));assertFalse(cache.contains(".join("));assertFalse(cache.contains(".get("+"Math"));
        String source=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/route/water/RouteTerrainSource.java"));
        assertTrue(source.contains("PortAsyncRuntime.worker"));
    }
}
