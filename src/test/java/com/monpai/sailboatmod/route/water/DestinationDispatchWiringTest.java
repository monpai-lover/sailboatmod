package com.monpai.sailboatmod.route.water;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class DestinationDispatchWiringTest {
    static String read(String p)throws Exception{return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/"+p));}
    @Test void userAndMarketUseSameKnownGraphNotTerrainPlanner()throws Exception{assertTrue(read("route/water/KnownVoyageService.java").contains("KnownWaterRouteGraph.build"));assertTrue(read("route/water/WaterDestinationService.java").contains("KnownWaterRouteGraph.build"));assertFalse(read("route/water/WaterDestinationService.java").contains("submitDestinationRoute"));assertTrue(read("block/entity/MarketBlockEntity.java").contains("ensureMarketRoute"));}
    @Test
    void noExactVertexOnlyTrackerAndNoBlindStallSkip() throws Exception {
        var source = read("entity/SailboatEntity.java");
        assertTrue(source.contains("AutonavPolylineFix.advance"));
        assertTrue(source.contains("replaceRouteGeometry"));
        var unstick = source.substring(source.indexOf("private void endUnstickAttempt"),
                source.indexOf("private boolean tryRuntimeAStarReroute"));
        assertFalse(unstick.contains("advanceAutopilotTargetOrStop()"));
    }
    @Test void infrastructureDoesNotPersistShipmentOwnersOrInventory()throws Exception{var s=read("route/water/WaterRouteNetworkSavedData.java");assertFalse(s.contains("shippingOrderId"));assertFalse(s.contains("ItemStack"));assertTrue(s.contains("getDataStorage()"));}
    @Test void gapsRunSolverAndCachedEdgesStillChecked()throws Exception{var s=read("route/water/WaterAutoRouteService.java");var a=s.substring(s.indexOf("static WaterRouteResult<List<BlockPos>> tryReuseNetwork"),s.indexOf("WaterRouteResult<List<BlockPos>> runSingleSegmentNbt("));assertTrue(a.contains("segmentHullClear"));assertTrue(a.contains("runSingleSegmentNbt"));
        // Reused/new joins are re-finished; cached edges keep strict hull validation.
        assertTrue(a.contains("finishGuidedRoute"));assertTrue(a.contains("jointWindows"));}
    @Test void dockingGeometryPreservesRouteSurfaceHeight()throws Exception{var s=read("entity/SailboatEntity.java");assertTrue(s.contains("WaterPolylineFollower.dockingTarget(autopilotRoute.get(index),target)"));}
    @Test void ownerAuthorizationIsRecheckedAfterAsyncPlanning()throws Exception{var s=read("route/water/KnownVoyageService.java");assertTrue(s.contains("canPlanDestination"));assertTrue(s.contains("catalog(l.level"));assertTrue(s.contains("captureDestinationCargo"));assertTrue(s.contains("l.cancelled"));}
}
