package com.monpai.sailboatmod.route.water;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KnownWaterRouteGraphTest {
    static Vec3 p(double x,double z){return new Vec3(x,65,z);}
    static BlockPos d(int x){return new BlockPos(x,66,0);}
    static WaterRouteNetwork.Route r(String name,int a,int b,boolean reverse,Vec3... points){return new WaterRouteNetwork.Route(name,d(a),d(b),List.of(points),reverse);}
    @Test void dockDispatchOnlyUsesSavedSegments(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,7000,true,p(0,0),p(7000,0))));var trip=g.between(d(0),d(7000)).orElseThrow();assertEquals(7000,trip.length(),1e-8);assertEquals(Set.of("ab"),trip.routeIds());}
    @Test void connectedRoutesReachCrimeaViaIstanbul(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,7000,true,p(0,0),p(7000,0)),r("bc",7000,11000,true,p(7000,0),p(11000,0))));assertEquals(11000,g.between(d(0),d(11000)).orElseThrow().length(),1e-8);assertEquals(Set.of(d(7000),d(11000)),g.fromDock(d(0)).keySet());}
    @Test void reverseOfKnownSegmentNeedsNoNewTerrain(){var g=KnownWaterRouteGraph.build(List.of(r("bc",7000,11000,true,p(7000,0),p(11000,0))));assertEquals(List.of(p(11000,0),p(7000,0)),g.between(d(11000),d(7000)).orElseThrow().points());}
    @Test void directedGraphDoesNotInventReturnEdge(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,false,p(0,0),p(100,0))));assertTrue(g.between(d(0),d(100)).isPresent());assertTrue(g.between(d(100),d(0)).isEmpty());}
    @Test void disconnectedDocksAreNotOffered(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0)),r("cd",200,300,true,p(200,0),p(300,0))));assertEquals(Set.of(d(100)),g.fromDock(d(0)).keySet());}
    @Test void nearbyDifferentBerthsNeverCreateInvisibleGap(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0)),r("bc",100,200,true,p(100,10),p(200,10))));assertTrue(g.between(d(0),d(200)).isEmpty());}
    @Test void endpointOnMiddleOfKnownRouteFormsRealJunction(){var g=KnownWaterRouteGraph.build(List.of(r("ac",0,200,true,p(0,0),p(200,0)),r("bd",100,300,true,p(100,0),p(100,100))));assertEquals(200,g.between(d(0),d(300)).orElseThrow().length(),1e-8);assertEquals(100,g.between(d(200),d(100)).orElseThrow().length(),1e-8);}
    @Test void junctionsAtDifferentHeightsStaySeparate(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0)),r("bc",100,200,true,new Vec3(100,66,0),new Vec3(200,66,0))));assertTrue(g.between(d(0),d(200)).isEmpty());}
    @Test void cheapestKnownPathWinsWithoutStraighteningOverIslands(){var g=KnownWaterRouteGraph.build(List.of(r("long",0,300,true,p(0,0),p(150,300),p(300,0)),r("short",0,300,true,p(0,0),p(150,40),p(300,0))));var trip=g.between(d(0),d(300)).orElseThrow();assertEquals(Set.of("short"),trip.routeIds());assertTrue(trip.points().contains(p(150,40)));assertTrue(trip.length()>300);}
    @Test void parallelNearbyLaneIsNotGraphConnection(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0)),r("cd",200,300,true,p(0,2),p(100,2))));assertTrue(g.between(d(0),d(300)).isEmpty());}
    @Test void shipChoosesNearestLaneWhichActuallyReachesDestination(){var g=KnownWaterRouteGraph.build(List.of(r("wrong",0,100,true,p(0,0),p(100,0)),r("right",200,300,true,p(0,50),p(100,50))));var entries=g.entries(p(20,2),d(300),8);assertFalse(entries.isEmpty());assertEquals(p(20,50),entries.getFirst().point());assertEquals(Set.of("right"),entries.getFirst().suffix().routeIds());}
    @Test void shipEntriesPreserveOneWayDirection(){var g=KnownWaterRouteGraph.build(List.of(r("forward",0,100,false,p(0,0),p(100,0))));assertTrue(g.entries(p(50,0),d(0),8).isEmpty());assertEquals(50,g.entries(p(50,0),d(100),8).getFirst().suffix().length(),1e-8);}
    @Test void farShipCannotRequestFullOceanSearch(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0))));assertTrue(g.entries(p(50,1000),d(100),8).isEmpty());assertTrue(g.reachableNear(p(50,1000)).isEmpty());}
    @Test void repeatedRequestsDoNotMutateGraph(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,7000,true,p(0,0),p(7000,0))));int n=g.nodeCount(),e=g.edgeCount();for(int i=0;i<100;i++){g.entries(p(i,10),d(7000),8);g.fromDock(d(0));}assertEquals(n,g.nodeCount());assertEquals(e,g.edgeCount());}
    @Test void deletedEdgeDisappearsFromRebuiltGraph(){var ab=r("ab",0,100,true,p(0,0),p(100,0));var bc=r("bc",100,200,true,p(100,0),p(200,0));assertTrue(KnownWaterRouteGraph.build(List.of(ab,bc)).between(d(0),d(200)).isPresent());assertTrue(KnownWaterRouteGraph.build(List.of(ab)).between(d(0),d(200)).isEmpty());}
    @Test void cyclesRemainFiniteAndShortest(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0)),r("bc",100,200,true,p(100,0),p(100,100)),r("ca",200,0,true,p(100,100),p(0,0))));assertEquals(Math.sqrt(20000),g.between(d(0),d(200)).orElseThrow().length(),1e-8);}
    @Test void nonFiniteGeometryRejected(){assertThrows(IllegalArgumentException.class,()->KnownWaterRouteGraph.build(List.of(r("bad",0,100,true,new Vec3(Double.NaN,65,0),p(100,0)))));}
    @Test void outputCannotModifyInfrastructure(){var g=KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0))));assertThrows(UnsupportedOperationException.class,()->g.between(d(0),d(100)).orElseThrow().points().clear());}
    @Test void cancellationStopsGraphWork(){assertThrows(java.util.concurrent.CancellationException.class,()->WaterRouteCancellation.run(()->true,()->KnownWaterRouteGraph.build(List.of(r("ab",0,100,true,p(0,0),p(100,0))))));}
    @Test void sevenKilometresManyKnownSegmentsStayExact(){var all=new ArrayList<WaterRouteNetwork.Route>();for(int i=0;i<70;i++)all.add(r("r"+i,i*100,(i+1)*100,true,p(i*100,0),p((i+1)*100,0)));var g=KnownWaterRouteGraph.build(all);assertEquals(7000,g.between(d(0),d(7000)).orElseThrow().length(),1e-8);assertEquals(70,g.fromDock(d(0)).size());}
}
