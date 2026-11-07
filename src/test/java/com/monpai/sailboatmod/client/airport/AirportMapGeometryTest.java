package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AirportMapGeometryTest {
    private final ClaimMapViewport viewport = new ClaimMapViewport(10, 20, 400, 300);
    @Test void projectionPreservesHalfBlockRunwayCentersAndExactTaxiPoints() {
        var view = RoadPlannerMapView.centered(100, 200, 2);
        var point = new Vec3(100.5, 81, 200.25);
        var screen = AirportMapGeometry.project(point, view, viewport);
        assertEquals(211, screen.x(), 1e-9);
        assertEquals(170.5, screen.y(), 1e-9);
        assertEquals(point, AirportMapGeometry.unproject(screen.x(), screen.y(), 81, view, viewport).orElseThrow());
        assertTrue(AirportMapGeometry.unproject(410, 170, 81, view, viewport).isEmpty());
        assertTrue(AirportMapGeometry.unproject(Double.NaN, 170, 81, view, viewport).isEmpty());
    }
    @Test void pointHitToleranceStaysInPixelsAtEveryZoom() {
        for (double scale : new double[]{.25, 1, 8}) {
            var view = RoadPlannerMapView.centered(0, 0, scale);
            var points = List.of(new Vec3(0, 81, 0));
            assertEquals(0, AirportMapGeometry.nearestPoint(points, 216, 170, 7, view, viewport));
            assertEquals(-1, AirportMapGeometry.nearestPoint(points, 218, 170, 7, view, viewport));
        }
    }
    @Test void segmentHitReturnsCorrectOrderedSegmentAndRejectsOutsideMap() {
        var view = RoadPlannerMapView.centered(0, 0, 2);
        var points = List.of(new Vec3(-20,81,0), new Vec3(0,81,0), new Vec3(0,81,20));
        assertEquals(1, AirportMapGeometry.nearestSegment(points,213,190,5,view,viewport));
        assertEquals(0, AirportMapGeometry.nearestSegment(points,190,173,5,view,viewport));
        assertEquals(-1, AirportMapGeometry.nearestSegment(points,-1,170,500,view,viewport));
    }
    @Test void rotatedParkingFootprintAndHeadingUseMinecraftYaw() {
        var rectangle = AirportMapGeometry.rectangle(new Vec3(100,81,200),90,20,8);
        assertEquals(90, rectangle.stream().mapToDouble(p -> p.x).min().orElseThrow(), 1e-9);
        assertEquals(110, rectangle.stream().mapToDouble(p -> p.x).max().orElseThrow(), 1e-9);
        assertEquals(196, rectangle.stream().mapToDouble(p -> p.z).min().orElseThrow(), 1e-9);
        assertEquals(204, rectangle.stream().mapToDouble(p -> p.z).max().orElseThrow(), 1e-9);
        assertEquals(90, AirportMapGeometry.headingDegrees(Vec3.ZERO,new Vec3(-10,0,0)),1e-9);
        assertEquals(0, AirportMapGeometry.headingDegrees(Vec3.ZERO,new Vec3(0,0,10)),1e-9);
    }
    @Test void runwayFootprintIncludesWidthCapAtBothEnds() {
        var rectangle=AirportMapGeometry.runwayFootprint(new Vec3(10.5,81,20.5),new Vec3(50.5,81,20.5),10);
        assertEquals(5.5,rectangle.stream().mapToDouble(p -> p.x).min().orElseThrow(),1e-9);
        assertEquals(55.5,rectangle.stream().mapToDouble(p -> p.x).max().orElseThrow(),1e-9);
        assertEquals(15.5,rectangle.stream().mapToDouble(p -> p.z).min().orElseThrow(),1e-9);
    }
    @Test void clippingBoundsWorkForHugeOffscreenGeometryAndFiniteGuard() {
        var clipped=AirportMapGeometry.clipLine(new AirportMapGeometry.ScreenPoint(-1e9,170),new AirportMapGeometry.ScreenPoint(1e9,170),viewport).orElseThrow();
        assertEquals(10,clipped.start().x(),1e-6);
        assertEquals(409,clipped.end().x(),1e-6);
        assertTrue(AirportMapGeometry.clipLine(new AirportMapGeometry.ScreenPoint(Double.NaN,170),new AirportMapGeometry.ScreenPoint(20,170),viewport).isEmpty());
        assertTrue(AirportMapGeometry.clipLine(new AirportMapGeometry.ScreenPoint(0,0),new AirportMapGeometry.ScreenPoint(0,500),viewport).isEmpty());
    }
    @Test void headingHandleStaysGrabbableWhenParkingFootprintIsZoomedOut() {
        var view=RoadPlannerMapView.centered(0,0,.01);
        var handle=AirportMapGeometry.headingHandle(Vec3.ZERO,90,20,view,viewport);
        assertEquals(186,handle.x(),1e-9);assertEquals(170,handle.y(),1e-9);
    }
    @Test void rotatedPolygonHitRejectsBoundingBoxCornersAndHonorsInside() {
        var view=RoadPlannerMapView.centered(0,0,2);
        var polygon=AirportMapGeometry.rectangle(Vec3.ZERO,45,20,4);
        assertTrue(AirportMapGeometry.containsPolygon(polygon,210,170,view,viewport));
        assertFalse(AirportMapGeometry.containsPolygon(polygon,225,185,view,viewport));
        assertFalse(AirportMapGeometry.containsPolygon(polygon,Double.NaN,170,view,viewport));
    }

    @Test void savedHitUsesRotatedFootprintsAndExactRunwayCenters() {
        var view=RoadPlannerMapView.centered(0,0,2);
        var stand=new com.monpai.sailboatmod.transport.air.airport.AirportParkingStand("gate",new Vec3(-30,81,0),90,20,8,8,true);
        var runway=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("strip",com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,
                new net.minecraft.core.BlockPos(0,81,0),new net.minecraft.core.BlockPos(40,81,0),10,12);
        var data=new com.monpai.sailboatmod.transport.air.airport.AirportScreenData(net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"),net.minecraft.core.BlockPos.ZERO,
                "Airport","Town","Owner",true,0,List.of(runway),new com.monpai.sailboatmod.transport.air.airport.AirportGroundLayout(0,List.of(stand),List.of()),List.of(),"");
        var hit=AirportMapGeometry.hitSaved(data,251,179,view,viewport).orElseThrow();
        assertEquals(AirportMapGeometry.Kind.RUNWAY,hit.kind());assertEquals("strip",hit.id());
        assertEquals(AirportMapGeometry.Handle.BODY,hit.handle());
        var center=AirportMapGeometry.hitSaved(data,150,170,view,viewport).orElseThrow();
        assertEquals(AirportMapGeometry.Kind.STAND,center.kind());assertEquals(AirportMapGeometry.Handle.POINT,center.handle());
        assertTrue(AirportMapGeometry.hitSaved(data,150,185,view,viewport).isEmpty());
    }

}
