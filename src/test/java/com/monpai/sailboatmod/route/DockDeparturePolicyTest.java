package com.monpai.sailboatmod.route;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DockDeparturePolicyTest {
    RouteDefinition route(){return new RouteDefinition("Auto-test",List.of(new Vec3(0,65,0),new Vec3(1000,65,0)),"","",0,1000,"A","B");}
    @Test void legalBerthFartherThan32BlocksCanDepart(){assertNotNull(DockDeparturePolicy.prepare(route(),new Vec3(45,64.91,9),true));}
    @Test void hullInsideButCentreOutsideIsStillLegal(){assertTrue(DockDeparturePolicy.inside(new AABB(0,62,0,8,72,8),new AABB(7.8,64.9,3,9.8,66.5,5)));}
    @Test void hullOutsideAndWrongAltitudeAreRejected(){assertFalse(DockDeparturePolicy.inside(new AABB(0,62,0,8,72,8),new AABB(10,64.9,3,12,66.5,5)));assertFalse(DockDeparturePolicy.inside(new AABB(0,62,0,8,72,8),new AABB(2,80,3,4,82,5)));}
    @Test void departureKeepsAllOriginalCornersAndSurfaceHeight(){var r=DockDeparturePolicy.prepare(route(),new Vec3(5,64.89,4),true);assertEquals(65,r.waypoints().getFirst().y);assertEquals(route().waypoints(),r.waypoints().subList(1,r.waypoints().size()));}
    @Test void repeatedAdmissionDoesNotDuplicateStart(){var r=DockDeparturePolicy.prepare(route(),new Vec3(5,64.89,4),true);assertEquals(r.waypoints(),DockDeparturePolicy.prepare(r,new Vec3(5,64.91,4),true).waypoints());}
    @Test void outOfBerthStillCannotDispatch(){assertNull(DockDeparturePolicy.prepare(route(),new Vec3(5,65,4),false));}
}
