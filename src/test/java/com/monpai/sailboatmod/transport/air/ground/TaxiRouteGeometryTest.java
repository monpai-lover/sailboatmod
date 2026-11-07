package com.monpai.sailboatmod.transport.air.ground;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.ground.TaxiRouteGeometry.*;

/** Pure authored-path preflight; no Minecraft, world or runtime dependency. */
class TaxiRouteGeometryTest {
    private static Capabilities aircraft(double radius) { return new Capabilities(true,10,6,radius,.75,1); }
    private static Request route(List<Point> p,double radius,double initialYaw,double terminalYaw) {
        return new Request(p,30,8,-1,p.getFirst(),initialYaw,terminalYaw,aircraft(radius));
    }
    private static List<Point> straight(double firstLength) {
        return List.of(new Point(0,0),new Point(firstLength,0),new Point(80,0),new Point(120,0));
    }
    private static List<Point> gentleQuarter() {
        List<Point> p=new ArrayList<>();p.add(new Point(-30,0));p.add(new Point(0,0));
        for(int degrees=10;degrees<=90;degrees+=10) {
            double a=Math.toRadians(degrees);p.add(new Point(64*Math.sin(a),64*(1-Math.cos(a))));
        }
        p.add(new Point(64,94));return p;
    }

    @Test void rejectsAFullWidthFitWhoseSharpTurnMissesTheCurrentPointCapture() {
        var p=List.of(new Point(0,0),new Point(64,0),new Point(64,64),new Point(64,128));
        Assessment a=TaxiRouteGeometry.assess(route(p,32,-90,0));
        assertFalse(a.admitted(),"Width alone cannot establish a32m-radius90degree corner");
        assertEquals("taxi_turn_capture_not_proven",a.reason());
        assertEquals(1,a.pointIndex());
        assertEquals(32*(Math.sqrt(2)-1),a.required(),1e-7);
        assertEquals(.75,a.available(),1e-7);
    }

    @Test void adjacentGentleCornersMustNotSpendTheSameShortSegmentTwice() {
        double a=Math.toRadians(20);Point c=new Point(40+5*Math.cos(a),5*Math.sin(a));
        var p=List.of(new Point(0,0),new Point(40,0),c,new Point(c.x()+40,c.z()));
        Assessment r=TaxiRouteGeometry.assess(route(p,32,-90,-90));
        assertFalse(r.admitted(),"Each20degree bend has small capture miss but their tangents overlap");
        assertEquals("taxi_turn_segment_not_proven",r.reason());
        assertEquals(2*32*Math.tan(Math.toRadians(10)),r.required(),1e-7);
        assertEquals(5,r.available(),1e-7);
    }

    @Test void sampledAuthoredArcCanPassWithoutChangingAnyOriginalPoint() {
        List<Point> mutable=new ArrayList<>(gentleQuarter());Request request=route(mutable,32,-90,0);
        List<Point> before=List.copyOf(request.points());mutable.clear();
        assertTrue(TaxiRouteGeometry.assess(request).admitted());
        assertEquals(before,request.points(),"Assessment must not generate or substitute another Plan path");
    }

    @Test void theSameAirportGeometryCanAdmitASmallTurningAircraftAndRejectALargeRadiusOne() {
        var p=List.of(new Point(0,0),new Point(64,0),new Point(64,64),new Point(64,128));
        assertTrue(TaxiRouteGeometry.assess(route(p,1,-90,0)).admitted());
        assertFalse(TaxiRouteGeometry.assess(route(p,32,-90,0)).admitted());
    }

    @Test void initialHeadingMayRecoverGraduallyInsideTheFirstSegment() {
        double a=Math.toRadians(93.5);Point u=new Point(-Math.sin(a),Math.cos(a));
        var p=List.of(new Point(0,0),new Point(u.x()*40,u.z()*40),new Point(u.x()*80,u.z()*80),new Point(u.x()*120,u.z()*120));
        assertTrue(TaxiRouteGeometry.assess(route(p,32,84.637,93.5)).admitted(),
                "An8.863degree initial mismatch is not an immediate5degree rejection");
    }

    @Test void initialHeadingCorrectionStillNeedsLongitudinalAndLateralSpace() {
        assertFalse(TaxiRouteGeometry.assess(route(straight(5),32,-81.137,-90)).admitted(),
                "A short first segment does not prove correction before its capture point");
        assertFalse(TaxiRouteGeometry.assess(route(straight(40),32,0,-90)).admitted(),
                "A90degree initial heading cannot recover inside this5m residual corridor");
    }

    @Test void terminalStandHeadingCannotBeIgnoredAfterTheAircraftStops() {
        Assessment r=TaxiRouteGeometry.assess(route(straight(40),32,-90,-79));
        assertFalse(r.admitted());assertEquals("taxi_terminal_heading_not_proven",r.reason());
    }

    @Test void aHardHoldCannotBorrowTheMovingCornerFilletAcrossItsStop() {
        double a=Math.toRadians(20);Point c=new Point(40+40*Math.cos(a),40*Math.sin(a));
        var p=List.of(new Point(0,0),new Point(40,0),c,new Point(c.x()+40*Math.cos(a),c.z()+40*Math.sin(a)));
        var r=new Request(p,30,8,1,p.getFirst(),-90,-70,aircraft(32));
        Assessment result=TaxiRouteGeometry.assess(r);
        assertFalse(result.admitted());assertEquals("taxi_hold_heading_not_proven",result.reason());
    }

    @Test void brakingUsesTheMeasuredDecelerationAndTheActualStopTargetSegment() {
        var slowBrake=new Capabilities(true,10,6,32,.1,5);
        var p=straight(40);Assessment r=TaxiRouteGeometry.assess(new Request(p,30,8,-1,p.getFirst(),-90,-90,slowBrake));
        assertFalse(r.admitted());assertEquals("taxi_stopping_distance_not_proven",r.reason());
        assertEquals(127,r.required(),1e-7);
    }

    @Test void actualReferenceRadiusAndUnknownCalibrationRemainCapabilityGates() {
        var p=straight(40);var offsetHull=new Capabilities(true,15,6,32,.75,1);
        assertFalse(TaxiRouteGeometry.assess(new Request(p,30,8,-1,p.getFirst(),-90,-90,offsetHull)).admitted(),
                "A hull radius about its native reference cannot be replaced by a centered AABB estimate");
        var unknown=new Capabilities(false,10,6,32,.75,1);
        assertFalse(TaxiRouteGeometry.assess(new Request(p,30,8,-1,p.getFirst(),-90,-90,unknown)).admitted());
    }

    @Test void mirroringRotatingAndTranslatingAnAirportDoNotChangeTheDecision() {
        var p=gentleQuarter();assertTrue(TaxiRouteGeometry.assess(route(p,32,-90,0)).admitted());
        var moved=p.stream().map(q->new Point(20481032-q.z(),20483080+q.x())).toList();
        assertTrue(TaxiRouteGeometry.assess(route(moved,32,0,90)).admitted());
        var mirrored=p.stream().map(q->new Point(q.x(),-q.z())).toList();
        assertTrue(TaxiRouteGeometry.assess(route(mirrored,32,-90,180)).admitted());
    }

    @Test void malformedAndReversingPathsDoNotAcquireAFalseGeometricPass() {
        assertFalse(TaxiRouteGeometry.assess(route(List.of(new Point(0,0),new Point(40,0),new Point(0,0)),32,-90,90)).admitted());
        assertFalse(TaxiRouteGeometry.assess(route(List.of(new Point(0,0),new Point(Double.NaN,0)),32,-90,-90)).admitted());
    }
}
