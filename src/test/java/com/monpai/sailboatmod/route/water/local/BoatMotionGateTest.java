package com.monpai.sailboatmod.route.water.local;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BoatMotionGateTest {
    static final BoatCollisionField.Hull HULL=new BoatCollisionField.Hull(1,1,64.78,66.6,64);
    static final BoatCollisionField.Cell WATER=new BoatCollisionField.Cell(true,true,List.of());
    static Vec3 p(double x,double z){return new Vec3(x,65,z);}
    @Test void actualHeadingIsHeldEvenWhenThePursuitDirectionIsClear() {
        var field=new BoatCollisionField(HULL,(x,z)->WATER,List.of(new AABB(1.6,64.9,-1,3.6,66.5,1)));
        assertEquals(BoatCollisionField.Check.CLEAR,field.segment(p(0,0),p(0,4)));
        Vec3 requested=new Vec3(.3,-.002,0);
        assertEquals(new Vec3(0,-.002,0),BoatMotionGate.constrain(p(0,0),requested,65,
                (a,b)->field.segment(a,b)==BoatCollisionField.Check.CLEAR));
        // Yaw is owned by the controller and may continue turning while translation is held.
        Vec3 aligned=new Vec3(0,-.002,.3);
        assertEquals(aligned,BoatMotionGate.constrain(p(0,0),aligned,65,
                (a,b)->field.segment(a,b)==BoatCollisionField.Check.CLEAR));
    }
    @Test void unknownTerrainStopsHorizontalTravelWithoutStoppingBuoyancy() {
        assertEquals(new Vec3(0,.04,0),BoatMotionGate.constrain(new Vec3(3,64.91,7),new Vec3(.2,.04,.1),65,(a,b)->false));
    }
    @Test void floatingPositionDoesNotLowerCollisionSurface() {
        Vec3 requested=new Vec3(.2,-.01,.15);
        assertEquals(requested,BoatMotionGate.constrain(new Vec3(3,64.89,7),requested,65,(a,b)->{
            assertEquals(65,a.y);assertEquals(65,b.y);assertEquals(3.2,b.x,1e-12);assertEquals(7.15,b.z,1e-12);return true;
        }));
    }
    @Test void validEscapeFromInitiallyOverlappingShapeIsNotPrevented() {
        var shape=new AABB(0,64.8,0,1,65.2,1);
        var field=new BoatCollisionField(HULL,(x,z)->new BoatCollisionField.Cell(true,true,x==0&&z==0?List.of(shape):List.of()),List.of());
        Vec3 away=new Vec3(-.1,0,0);
        assertEquals(away,BoatMotionGate.constrain(p(-.8,.5),away,65,(a,b)->field.segment(a,b)==BoatCollisionField.Check.CLEAR));
        assertEquals(Vec3.ZERO,BoatMotionGate.constrain(p(-.8,.5),new Vec3(.1,0,0),65,(a,b)->field.segment(a,b)==BoatCollisionField.Check.CLEAR));
    }
    @Test void zeroHorizontalVelocityNeedsNoCollisionRead() {
        Vec3 vertical=new Vec3(0,.1,0);
        assertSame(vertical,BoatMotionGate.constrain(p(0,0),vertical,65,(a,b)->{fail("unnecessary collision read");return false;}));
    }
    @Test void nonFiniteVelocitiesFailClosed() {
        assertEquals(Vec3.ZERO,BoatMotionGate.constrain(p(0,0),new Vec3(Double.NaN,0,0),65,(a,b)->true));
        assertEquals(Vec3.ZERO,BoatMotionGate.constrain(p(0,0),new Vec3(.1,0,0),Double.NaN,(a,b)->true));
    }
}
