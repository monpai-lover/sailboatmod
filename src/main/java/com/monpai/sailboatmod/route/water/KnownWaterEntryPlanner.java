package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.BooleanSupplier;

/** R-menu ONLY. Connect a physical ship to one of the nearest eligible saved lanes.
 * The suffix is untouched; no dock-to-dock search, graph registration or route saving occurs.
 */
final class KnownWaterEntryPlanner {
    private KnownWaterEntryPlanner() { }
    static WaterRouteResult<List<Vec3>> plan(ServerLevel level,Vec3 boat,List<KnownWaterRouteGraph.Entry> entries,BooleanSupplier cancelled) {
        if(level.getServer().isSameThread())throw new IllegalStateException("Ship connector on server tick");
        long deadline=System.nanoTime()+20_000_000_000L;
        var predictor=com.monpai.sailboatmod.route.terrain.TellusTerrain.forLevel(level).orElse(null);
        RealBlockWaterMap map=new RealBlockWaterMap(level,level.getSeaLevel()).enableOnDemand().retryUnknownPrediction()
                .withPredictor(predictor,com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds());
        try(RouteTerrainSource terrain=new RouteTerrainSource(level,predictor,
                ()->com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.forLevel(level).orElse(null))) {
            map.withAsyncTerrain(terrain);
            LiveWaterSnapshots.seedNearby(level,map,boat,Math.min(deadline,System.nanoTime()+3_000_000_000L));
            var world=StrictWaterDetour.world(map,1);
            var startColumn=StrictWaterDetour.awaitColumn(world,BlockPos.containing(boat),terrain::awaitProgress,deadline);
            if(!startColumn.passable()||startColumn.surfacePos()==null)return WaterRouteResult.failure(WaterRouteFailureReason.NO_SOURCE_BERTH);
            BlockPos start=startColumn.surfacePos();
            for(var entry:entries) {
                if(cancelled.getAsBoolean())return WaterRouteResult.failure(WaterRouteFailureReason.CANCELLED);
                StrictWaterDetour.checkDeadline(deadline);
                BlockPos goal=BlockPos.containing(entry.point());
                if(goal.getY()!=start.getY()||entry.connectorDistance()>KnownWaterRouteGraph.MAX_ENTRY_DISTANCE)continue;
                // Candidate windows are bounded independently: an inaccessible nearest lane cannot
                // monopolize the whole request and prevent trying the next nearby eligible lane.
                long candidateDeadline=Math.min(deadline,System.nanoTime()+4_000_000_000L);
                try {
                    List<BlockPos> connector=StrictWaterDetour.find(world,start,goal,terrain::awaitProgress,candidateDeadline,8000);
                    if(connector.isEmpty())continue;
                    List<Vec3> path=new ArrayList<>();path.add(new Vec3(boat.x,start.getY(),boat.z));
                    for(BlockPos p:connector)add(path,Vec3.atBottomCenterOf(p));
                    add(path,entry.point());for(Vec3 p:entry.suffix().points())add(path,p);
                    if(path.size()<2||path.size()>65_536)continue;
                    return WaterRouteResult.success(List.copyOf(path));
                } catch(StrictWaterDetour.Failure fail) {
                    if(fail.reason()!=WaterRouteFailureReason.TIMEOUT&&fail.reason()!=WaterRouteFailureReason.NODE_BUDGET_EXCEEDED)throw fail;
                }
            }
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        } catch(StrictWaterDetour.Failure failure){return WaterRouteResult.failure(failure.reason());}
    }
    private static void add(List<Vec3> path,Vec3 p){if(path.isEmpty()||path.getLast().distanceToSqr(p)>1e-12)path.add(p);}
}
