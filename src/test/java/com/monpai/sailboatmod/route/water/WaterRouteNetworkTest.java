package com.monpai.sailboatmod.route.water;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WaterRouteNetworkTest {
    static Vec3 p(double x,double z){return new Vec3(x,65,z);}
    static WaterRouteNetwork.Route route(String id,double from,double to,boolean reverse){
        return new WaterRouteNetwork.Route(id,new BlockPos((int)from,65,0),new BlockPos((int)to,65,0),List.of(p(from,0),p((from+to)/2,0),p(to,0)),reverse);
    }
    static void endpoints(WaterRouteNetwork.Plan plan,Vec3 a,Vec3 b){
        assertEquals(a,plan.legs().getFirst().points().getFirst());assertEquals(b,plan.legs().getLast().points().getLast());
        for(int i=1;i<plan.legs().size();i++)assertEquals(plan.legs().get(i-1).points().getLast(),plan.legs().get(i).points().getFirst());
    }
    @Test void wholeRouteIsInfrastructureNotASecondSearch(){var q=WaterRouteNetwork.plan(List.of(route("Crete-Istanbul",0,7000,true)),p(0,0),p(7000,0)).orElseThrow();endpoints(q,p(0,0),p(7000,0));assertEquals(7000,q.reusedLength(),1e-6);assertEquals(0,q.newLength(),1e-6);}
    @Test void reverseSubsetUsesExactInteriorProjection(){var q=WaterRouteNetwork.plan(List.of(route("Crete-Crimea",0,11000,true)),p(10000,0),p(6500,0)).orElseThrow();endpoints(q,p(10000,0),p(6500,0));assertEquals(3500,q.reusedLength(),1e-6);assertEquals(0,q.newLength(),1e-6);}
    @Test void extendingToCrimeaOnlySearchesMissingSuffix(){var q=WaterRouteNetwork.plan(List.of(route("Crete-Istanbul",0,7000,true)),p(0,0),p(11000,0)).orElseThrow();endpoints(q,p(0,0),p(11000,0));assertEquals(7000,q.reusedLength(),1e-6);assertEquals(4000,q.newLength(),1e-6);assertFalse(q.legs().getLast().reused());}
    @Test void remoteStartCanJoinExistingNetworkThenReuse(){var q=WaterRouteNetwork.plan(List.of(route("Crete-Istanbul",0,7000,true)),p(11000,0),p(0,0)).orElseThrow();endpoints(q,p(11000,0),p(0,0));assertFalse(q.legs().getFirst().reused());assertEquals(7000,q.reusedLength(),1e-6);}
    @Test void twoRoutesComposeWithoutRequiringOneExistingEndToEndRoute(){var q=WaterRouteNetwork.plan(List.of(route("A-B",0,4000,true),route("B-C",4000,9000,true)),p(0,0),p(9000,0)).orElseThrow();assertEquals(9000,q.reusedLength(),1e-6);assertEquals(Set.of("A-B","B-C"),q.routeIds());assertEquals(0,q.newLength(),1e-6);}
    @Test void sameDockDifferentBerthsRemainsAnUnverifiedGap(){
        var b=new BlockPos(4000,65,0);var a=new WaterRouteNetwork.Route("A-B",BlockPos.ZERO,b,List.of(p(0,0),p(4000,0)),true);
        var c=new WaterRouteNetwork.Route("B-C",b,new BlockPos(9000,65,20),List.of(p(4000,20),p(9000,20)),true);
        var q=WaterRouteNetwork.plan(List.of(a,c),p(0,0),p(9000,20)).orElseThrow();assertTrue(q.legs().stream().anyMatch(l->!l.reused()));assertTrue(q.newLength()>=20);endpoints(q,p(0,0),p(9000,20));
    }
    @Test void nearbyDepartureIsNotAssumedWater(){var q=WaterRouteNetwork.plan(List.of(route("A-B",0,7000,true)),p(1000,30),p(6000,0)).orElseThrow();assertFalse(q.legs().getFirst().reused());assertTrue(q.newLength()>=30);}
    @Test void oneWayWaterwayCannotBeReversed(){assertTrue(WaterRouteNetwork.plan(List.of(route("oneway",0,7000,false)),p(7000,0),p(0,0)).isEmpty());}
    @Test void verticallySeparateWaterDoesNotJoin(){assertTrue(WaterRouteNetwork.plan(List.of(route("A-B",0,7000,true)),new Vec3(0,90,0),new Vec3(7000,90,0)).isEmpty());}
    @Test void disconnectedAndUselessDetourFallsBackToNormalPlanner(){assertTrue(WaterRouteNetwork.plan(List.of(route("wrong",0,-7000,true)),p(0,0),p(7000,0)).isEmpty());}
    @Test void geometryIsImmutableAndCannotBeAlteredByJourney(){var points=new ArrayList<>(List.of(p(0,0),p(7000,0)));var a=new WaterRouteNetwork.Route("a",BlockPos.ZERO,new BlockPos(7000,65,0),points,true);points.clear();assertEquals(2,a.points().size());assertThrows(UnsupportedOperationException.class,()->a.points().clear());}
    @Test void nonFiniteDataNeverPublishesPlan(){assertTrue(WaterRouteNetwork.plan(List.of(route("a",0,7000,true)),new Vec3(Double.NaN,65,0),p(7000,0)).isEmpty());}
    @Test void noUnsafeStraighteningOfIslandDetour(){var path=List.of(p(0,0),p(3000,800),p(5000,800),p(7000,0));var a=new WaterRouteNetwork.Route("around-island",BlockPos.ZERO,new BlockPos(7000,65,0),path,true);var q=WaterRouteNetwork.plan(List.of(a),path.getFirst(),path.getLast()).orElseThrow();assertEquals(path,q.legs().getFirst().points());assertTrue(q.reusedLength()>7000);}
}
