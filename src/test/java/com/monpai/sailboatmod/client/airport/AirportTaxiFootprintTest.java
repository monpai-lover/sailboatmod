package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AirportTaxiFootprintTest {
    private final ClaimMapViewport viewport=new ClaimMapViewport(10,20,400,300);
    private final RoadPlannerMapView view=RoadPlannerMapView.centered(0,0,4);
    @Test void sweptTaxiFootprintIncludesEndCapsAndOutsideOfRightAngleBend() {
        var footprint=AirportMapGeometry.taxiFootprint(List.of(new Vec3(0,81,0),new Vec3(20,81,0),new Vec3(20,81,20)),10,view,viewport);
        assertCovered(footprint,new Vec3(-4.9,81,0));
        assertCovered(footprint,new Vec3(20,81,24.9));
        assertCovered(footprint,new Vec3(23.5,81,-3.5));
        assertCovered(footprint,new Vec3(10,81,4.9));
        var outside=AirportMapGeometry.project(new Vec3(24,81,-4),view,viewport);
        assertTrue(footprint.stream().noneMatch(polygon->AirportMapGeometry.containsPolygon(polygon,outside.x(),outside.y(),view,viewport)));
    }
    @Test void TessellationConservativelyContainsActualCircleAtEveryHeading() {
        var footprint=AirportMapGeometry.taxiFootprint(List.of(new Vec3(0,81,0)),10,view,viewport);
        assertEquals(1,footprint.size());
        for(int angle=0;angle<360;angle++) {
            double radians=Math.toRadians(angle);
            assertCovered(footprint,new Vec3(Math.cos(radians)*5,81,Math.sin(radians)*5));
        }
        assertTrue(footprint.getFirst().size()>=12);
        assertTrue(footprint.getFirst().size()<=96);
        assertTrue(footprint.getFirst().stream().allMatch(point->Math.hypot(point.x,point.z)<=5.1));
    }
    @Test void offscreenCirclesAreCulledAndPathCrossingViewportRemainsVisible() {
        assertTrue(AirportMapGeometry.taxiFootprint(List.of(new Vec3(100_000,81,100_000)),10,view,viewport).isEmpty());
        var crossing=AirportMapGeometry.taxiFootprint(List.of(new Vec3(-1e6,81,0),new Vec3(1e6,81,0)),10,view,viewport);
        assertEquals(1,crossing.size());assertCovered(crossing,Vec3.ZERO);
        assertTrue(AirportMapGeometry.taxiFootprint(List.of(Vec3.ZERO),Double.NaN,view,viewport).isEmpty());
        assertTrue(AirportMapGeometry.taxiFootprint(List.of(Vec3.ZERO),-1,view,viewport).isEmpty());
    }
    private void assertCovered(List<List<Vec3>> footprint,Vec3 point) {
        var screen=AirportMapGeometry.project(point,view,viewport);
        assertTrue(footprint.stream().anyMatch(polygon->AirportMapGeometry.containsPolygon(polygon,screen.x(),screen.y(),view,viewport)),point.toString());
    }
}
