package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.water.debug.RouteDebugDtos.ViewTransform;
import com.monpai.sailboatmod.route.water.debug.RouteDebugService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RouteDebugProductionPipelineTest {
    @AfterEach void resetTerrainOverride() { WaterAutoRouteService.farTerrainOverride = null; }

    @Test void readOnlyDebugUsesTheProductionSolverForTheSameResolvedBerths() throws Exception {
        Method preview = resolvedPreview();
        assertDoesNotThrow(() -> WaterAutoRouteService.class.getMethod("planReadOnlyNbt", ServerLevel.class,
                DockBerthResolver.DockZone.class, DockBerthResolver.DockZone.class));
        var water = columns(true);
        TerrainPredictor predictor = predictor(water);
        WaterAutoRouteService.farTerrainOverride = () -> new WaterAutoRouteService.FarRouteTerrain(
                (x, z, spacing) -> java.util.Optional.of(new com.monpai.sailboatmod.route.terrain.SurfaceSample(
                        63, null, true, 1)), null);
        BlockPos start = new BlockPos(5, 63, 5), goal = new BlockPos(29, 63, 5);
        Map<Long, NbtChunkWaterReader.ChunkColumns> snapshots = Map.of(0L, water,
                net.minecraft.world.level.ChunkPos.asLong(1, 0), water);
        WaterRouteResult<List<BlockPos>> production = WaterAutoRouteService.runSingleSegmentNbt(null, start, goal, 63,
                1, WaterRoutePolicy.defaults(), new AtomicReference<>(List.of()),
                new WaterRouteProgressBar(null, null, "", ""), predictor, PredictionBudget.sixtySeconds(), snapshots);
        Object readOnly = preview.invoke(null, null, start, goal, 63, predictor, snapshots);
        WaterRouteResult<?> actual = (WaterRouteResult<?>) readOnly.getClass().getMethod("result").invoke(readOnly);
        assertTrue(production.successful(), () -> "production reason=" + production.reason());
        assertEquals(production, actual);
        assertEquals(start, readOnly.getClass().getMethod("sourceBerth").invoke(readOnly));
        assertEquals(goal, readOnly.getClass().getMethod("targetBerth").invoke(readOnly));
    }

    @Test void readOnlyDebugRetainsAuthoritativePortLandOverPredictedOcean() throws Exception {
        Method preview = resolvedPreview();
        WaterAutoRouteService.farTerrainOverride = () -> new WaterAutoRouteService.FarRouteTerrain(
                (x, z, spacing) -> java.util.Optional.of(new com.monpai.sailboatmod.route.terrain.SurfaceSample(
                        63, null, true, 1)), null);
        Object readOnly = preview.invoke(null, null, new BlockPos(5, 63, 5), new BlockPos(29, 63, 5), 63,
                predictor(columns(true)), Map.of(0L, columns(false),
                        net.minecraft.world.level.ChunkPos.asLong(1, 0), columns(false)));
        WaterRouteResult<?> result = (WaterRouteResult<?>) readOnly.getClass().getMethod("result").invoke(readOnly);
        assertFalse(result.successful(), "preview must not discard real port snapshots");
    }

    @Test void productionPreviewFitsTheWholeRouteAndDetourWithin1024Pixels() throws Exception {
        Method viewMethod = assertDoesNotThrow(() -> RouteDebugService.class.getDeclaredMethod("productionPreviewView",
                BlockPos.class, BlockPos.class, List.class));
        viewMethod.setAccessible(true);
        BlockPos start = new BlockPos(134_000, 63, -29_000), goal = new BlockPos(5_000, 63, -69_000);
        List<BlockPos> path = List.of(start, new BlockPos(170_000, 63, 40_000), goal);
        ViewTransform view = (ViewTransform) viewMethod.invoke(null, start, goal, path);
        assertTrue(view.width() <= 1024 && view.height() <= 1024);
        assertTrue(view.scale() > 1, "far routes must scale to fit, not crop their midpoint");
        for (BlockPos point : path) {
            assertTrue(view.px(point.getX()) >= 0 && view.px(point.getX()) < view.width());
            assertTrue(view.pz(point.getZ()) >= 0 && view.pz(point.getZ()) < view.height());
        }
    }

    @Test void readOnlyDebugRetainsCallerCancellationAndReturnsACancelledResult() throws Exception {
        Method preview = resolvedPreview();
        AtomicBoolean cancelled = new AtomicBoolean();
        Object readOnly = assertDoesNotThrow(() -> WaterRouteCancellation.run(cancelled::get, () -> {
            cancelled.set(true);
            try {
                return preview.invoke(null, null, new BlockPos(5, 63, 5), new BlockPos(29, 63, 5), 63,
                        predictor(columns(true)), Map.of());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("read-only cancellation must be reported as a result", failure);
            }
        }));
        WaterRouteResult<?> result = (WaterRouteResult<?>) readOnly.getClass().getMethod("result").invoke(readOnly);
        assertEquals(WaterRouteFailureReason.CANCELLED, result.reason());
    }

    private static Method resolvedPreview() {
        return assertDoesNotThrow(() -> WaterAutoRouteService.class.getMethod("planReadOnlyNbt", ServerLevel.class,
                BlockPos.class, BlockPos.class, int.class, TerrainPredictor.class, Map.class),
                "route debug needs a read-only bridge to the current production solver");
    }

    private static NbtChunkWaterReader.ChunkColumns columns(boolean water) {
        boolean[] wet = new boolean[256]; Arrays.fill(wet, water);
        int[] surface = new int[256]; Arrays.fill(surface, 62);
        return new NbtChunkWaterReader.ChunkColumns(wet, surface);
    }

    private static TerrainPredictor predictor(NbtChunkWaterReader.ChunkColumns columns) {
        return new TerrainPredictor() {
            @Override public Prediction predictChunk(int x, int z, long wait) { return Prediction.ready(columns); }
            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
    }
}
