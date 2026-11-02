package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only bridge to the far-route planner and the route-network reuse used by Tellus auto routes. */
public final class WaterAutoRouteServiceBench {
    private WaterAutoRouteServiceBench() { }

    private static void install(TerrainSurfaceSampler surface, double tellusWorldScale) {
        WorldWaterOverview bundled = WorldWaterOverview.get();
        GlobalWaterGridPlanner.SeaDistanceSource overview = bundled == null || tellusWorldScale <= 0 ? null
                : bundled.seaDistances(tellusWorldScale);
        WaterAutoRouteService.FarRouteTerrain far = new WaterAutoRouteService.FarRouteTerrain(surface, overview);
        WaterAutoRouteService.farTerrainOverride = () -> far;
    }

    public static WaterRouteResult<List<BlockPos>> plan(BlockPos source, BlockPos target, int seaY, TerrainPredictor predictor,
                                                        PredictionBudget budget, TerrainSurfaceSampler surface, long limitMillis,
                                                        double tellusWorldScale) {
        install(surface, tellusWorldScale);
        long deadline = System.currentTimeMillis() + limitMillis;
        try {
            return WaterRouteCancellation.run(() -> System.currentTimeMillis() > deadline,
                    () -> WaterAutoRouteService.runSingleSegmentNbt(null, source, target, seaY,
                            Math.max(1, WaterRoutePolicy.defaults().boatHalfWidth()), WaterRoutePolicy.defaults(),
                            new AtomicReference<>(List.of()), new WaterRouteProgressBar(null, null, "", ""),
                            predictor, budget, Map.of()));
        } catch (CancellationException e) {
            return WaterRouteResult.failure(WaterRouteFailureReason.TIMEOUT);
        } finally {
            WaterAutoRouteService.farTerrainOverride = null;
        }
    }

    /** Plans through {@link WaterRouteNetwork} reuse of {@code existing}; null = reuse not applicable. */
    public static WaterRouteResult<List<BlockPos>> planReusing(List<BlockPos> existing, BlockPos existingSourceDock,
                                                               BlockPos existingTargetDock, BlockPos source, BlockPos target,
                                                               int seaY, TerrainPredictor predictor, TerrainSurfaceSampler surface,
                                                               long limitMillis, double tellusWorldScale) {
        install(surface, tellusWorldScale);
        List<Vec3> points = new ArrayList<>();
        for (BlockPos p : existing) points.add(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5));
        List<WaterRouteNetwork.Route> network = List.of(new WaterRouteNetwork.Route("stored", existingSourceDock,
                existingTargetDock, points, true));
        long deadline = System.currentTimeMillis() + limitMillis;
        try {
            return WaterRouteCancellation.run(() -> System.currentTimeMillis() > deadline,
                    () -> WaterAutoRouteService.tryReuseNetwork(null, source, target, seaY,
                            Math.max(1, WaterRoutePolicy.defaults().boatHalfWidth()), WaterRoutePolicy.defaults(),
                            new AtomicReference<>(List.of()), new WaterRouteProgressBar(null, null, "", ""),
                            predictor, Map.of(), network));
        } catch (CancellationException e) {
            return WaterRouteResult.failure(WaterRouteFailureReason.TIMEOUT);
        } finally {
            WaterAutoRouteService.farTerrainOverride = null;
        }
    }
}
