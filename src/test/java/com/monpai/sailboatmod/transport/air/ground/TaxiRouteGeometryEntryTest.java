package com.monpai.sailboatmod.transport.air.ground;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.ground.TaxiRouteGeometry.*;

/** Regression for entering the original first capture point from inside its existing corridor. */
class TaxiRouteGeometryEntryTest {
    private static final Point FIRST = new Point(84.938553, 515.581909);
    private static final Point CURRENT = new Point(87.066, 516.402);
    private static final Point NEXT = new Point(34.5, 512.5);
    private static final double INITIAL_YAW = 84.7532;
    private static final double TERMINAL_YAW = 93.49655383882879;
    private static final Capabilities AIRCRAFT = new Capabilities(true,12.4,6,32,.75,1);

    private static Request request(Point position,double yaw,double firstSegmentLength) {
        double length=Math.hypot(NEXT.x()-FIRST.x(),NEXT.z()-FIRST.z());
        double ux=(NEXT.x()-FIRST.x())/length,uz=(NEXT.z()-FIRST.z())/length;
        Point next=new Point(FIRST.x()+ux*firstSegmentLength,FIRST.z()+uz*firstSegmentLength);
        Point end=new Point(next.x()+ux*50,next.z()+uz*50);
        return new Request(List.of(FIRST,next,end),32,8,-1,position,yaw,TERMINAL_YAW,AIRCRAFT);
    }

    @Test void nativeReferenceBehindFirstPointMayReachItsUnchangedCaptureCircle() {
        double yaw=Math.toRadians(INITIAL_YAW),hx=-Math.sin(yaw),hz=Math.cos(yaw);
        double dx=FIRST.x()-CURRENT.x(),dz=FIRST.z()-CURRENT.z();
        double rayDistance=dx*hx+dz*hz;
        double rayMiss=Math.abs(dx*hz-dz*hx);
        assertEquals(2.280039470292147,Math.hypot(dx,dz),1e-9);
        assertEquals(1.011201371807094,rayMiss,1e-9);
        assertTrue(rayDistance>0 && rayMiss<1.5,"The existing capture circle lies ahead on this heading ray");
        assertTrue(TaxiRouteGeometry.assess(request(CURRENT,INITIAL_YAW,Math.hypot(NEXT.x()-FIRST.x(),NEXT.z()-FIRST.z()))).admitted(),
                "A reference2.28m behind the first point can enter its original1.5m capture within the3.6m corridor");
    }

    @Test void aHeadingRayThatMissesTheOriginalCaptureDoesNotGainAnEntryPass() {
        assertFalse(TaxiRouteGeometry.assess(request(CURRENT,60,51)).admitted(),
                "Being inside the corridor alone must not enlarge the first capture circle");
        assertFalse(TaxiRouteGeometry.assess(request(CURRENT,INITIAL_YAW+180,51)).admitted(),
                "The first point must be in front of the current heading ray");
    }

    @Test void entryStillRequiresTheCurrentFirstNodeCorridor() {
        Point tooFar=new Point(FIRST.x()+4,FIRST.z());
        assertFalse(TaxiRouteGeometry.assess(request(tooFar,90,51)).admitted(),
                "Aiming through capture cannot bypass the follower's current3.6m radial corridor");
    }

    @Test void captureEntryMustLeaveSpaceForTheSameFiniteRadiusCorrection() {
        assertFalse(TaxiRouteGeometry.assess(request(CURRENT,INITIAL_YAW,5)).admitted(),
                "The original short first segment cannot pay for a32m-radius heading and offset correction");
    }
}
