package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftProjectedHullTest {
    private static final Vec3 ONE=new Vec3(1,1,1);
    private Optional<AABB> project(Vec3 scale,Vec3 pivot,Vec3 anchor,Vec3 tangent,double bank) {
        return RailAircraftProjectedHull.transform(0,0,0,19,3,3,scale,pivot,Direction.EAST,anchor,tangent,bank);
    }
    private void equal(AABB a,AABB b) {
        assertArrayEquals(new double[]{a.minX,a.minY,a.minZ,a.maxX,a.maxY,a.maxZ},
                new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ},1e-8);
    }
    @Test void integerMaximumIsInclusiveAndOneBlockHasPositiveVolume() {
        var box=RailAircraftProjectedHull.transform(10,20,30,10,20,30,ONE,new Vec3(10.5,20.5,30.5),
                Direction.WEST,new Vec3(100,5,200),new Vec3(-1,0,0),0).orElseThrow();
        equal(new AABB(99.5,4.5,199.5,100.5,5.5,200.5),box);
    }
    @Test void fortyFiveDegreeArrivalUsesItsActualAxisAlignedHull() {
        Vec3 local=new Vec3(10,2,2),anchor=new Vec3(100,50,200);
        AABB straight=project(ONE,local,anchor,new Vec3(1,0,0),0).orElseThrow();
        AABB turned=project(ONE,local,anchor,new Vec3(1,0,1),0).orElseThrow();
        double radius=12/Math.sqrt(2);
        equal(new AABB(100-radius,48,200-radius,100+radius,52,200+radius),turned);
        double straightSweep=2*Math.hypot(straight.maxX-anchor.x,straight.maxZ-anchor.z);
        double turnedSweep=2*Math.hypot(turned.maxX-anchor.x,turned.maxZ-anchor.z);
        assertTrue(turnedSweep>straightSweep+3);
    }
    @Test void nonuniformScaleAppliesBeforeRotationAndKeepsOffCenterAnchor() {
        AABB box=project(new Vec3(2,.5,3),new Vec3(2,1,3),new Vec3(100,50,200),new Vec3(0,0,1),0).orElseThrow();
        equal(new AABB(97,49.5,196,109,51.5,236),box);
    }
    @Test void resultMatchesNativePivotTransformForAllCornersIncludingPitchAndBank() {
        Vec3 localAnchor=new Vec3(7.5,1.5,2.5),nativePivot=new Vec3(-9,15,42),scale=new Vec3(2,.5,3);
        Vec3 anchor=new Vec3(-150,42,800),tangent=new Vec3(-1,.2,.3);double bank=.3;
        var q=RailAircraftMotionState.orientation(Direction.EAST,tangent,bank);
        var position=RailAircraftMotionState.posePosition(localAnchor,nativePivot,scale,q,anchor);
        AABB actual=project(scale,localAnchor,anchor,tangent,bank).orElseThrow();
        AABB expected=null;
        for(int x:new int[]{0,20})for(int y:new int[]{0,4})for(int z:new int[]{0,4}) {
            Vector3d world=q.transform(new Vector3d(x-nativePivot.x,y-nativePivot.y,z-nativePivot.z)
                    .mul(scale.x,scale.y,scale.z)).add(position);
            AABB point=new AABB(world.x,world.y,world.z,world.x,world.y,world.z);
            expected=expected==null?point:expected.minmax(point);
        }
        equal(expected,actual);
    }
    @Test void everyHorizontalLocalNoseMapsToSameDesiredHeading() {
        for(Direction nose:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}) {
            Vec3 anchor=new Vec3(6,7,8),tangent=new Vec3(0,0,-1);
            AABB box=RailAircraftProjectedHull.transform(-1,-1,-1,0,0,0,ONE,Vec3.ZERO,nose,anchor,tangent,0).orElseThrow();
            equal(new AABB(5,6,7,7,8,9),box);
        }
    }
    @Test void invalidBoundsScalesOrAttitudesFailClosed() {
        assertTrue(RailAircraftProjectedHull.transform(1,0,0,0,3,3,ONE,Vec3.ZERO,Direction.EAST,Vec3.ZERO,new Vec3(1,0,0),0).isEmpty());
        for(Vec3 scale:new Vec3[]{null,Vec3.ZERO,new Vec3(-1,1,1),new Vec3(Double.NaN,1,1)})
            assertTrue(project(scale,Vec3.ZERO,Vec3.ZERO,new Vec3(1,0,0),0).isEmpty());
        for(Vec3 anchor:new Vec3[]{null,new Vec3(Double.NaN,0,0),new Vec3(Double.POSITIVE_INFINITY,0,0)})
            assertTrue(project(ONE,Vec3.ZERO,anchor,new Vec3(1,0,0),0).isEmpty());
        assertTrue(project(ONE,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,0).isEmpty());
        assertTrue(project(ONE,Vec3.ZERO,Vec3.ZERO,new Vec3(1,0,0),Double.NaN).isEmpty());
        assertTrue(project(new Vec3(Double.MAX_VALUE,1,1),Vec3.ZERO,Vec3.ZERO,new Vec3(1,0,0),0).isEmpty());
    }
    @Test void integerMaximumCannotOverflowDuringExclusiveConversion() {
        double c=(double)Integer.MAX_VALUE+.5;
        var box=RailAircraftProjectedHull.transform(Integer.MAX_VALUE,0,0,Integer.MAX_VALUE,0,0,ONE,
                new Vec3(c,.5,.5),Direction.EAST,Vec3.ZERO,new Vec3(1,0,0),0).orElseThrow();
        equal(new AABB(-.5,-.5,-.5,.5,.5,.5),box);
    }
    @Test void absentControllerDoesNotLoadOptionalNativeClasses() {
        assertTrue(RailAircraftProjectedHull.at(null,Direction.WEST,Vec3.ZERO,new Vec3(-1,0,0),0).isEmpty());
    }
}
