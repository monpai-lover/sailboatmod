package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftRotationSweepTest {
    @Test void sweepContainsEveryCornerDuringPitchBankAndTranslation() {
        var start=new RailAircraftMotionState.Frame(new Vec3(100,70,20),new Quaterniond().rotateY(.4));
        var end=new RailAircraftMotionState.Frame(new Vec3(101,70.5,20),
                RailAircraftMotionState.orientation(Direction.EAST,new Vec3(.98,.1,.16),Math.toRadians(30)));
        var box=new AABB(89,68,13,108,74,27);
        var sweep=RailAircraftMotionState.rotationSweep(start,box,end);
        for(int step=0;step<=100;step++) {
            double f=step/100D;var relative=new Quaterniond(start.orientation()).slerp(end.orientation(),f)
                    .mul(new Quaterniond(start.orientation()).conjugate());
            Vec3 anchor=start.anchor().lerp(end.anchor(),f);
            for(int corner=0;corner<8;corner++) {
                var v=new Vector3d((corner&1)==0?box.minX:box.maxX,(corner&2)==0?box.minY:box.maxY,(corner&4)==0?box.minZ:box.maxZ)
                        .sub(start.anchor().x,start.anchor().y,start.anchor().z);
                relative.transform(v).add(anchor.x,anchor.y,anchor.z);
                assertTrue(sweep.contains(new Vec3(v.x,v.y,v.z)),"Intermediate wings and tail must remain in the swept envelope");
            }
        }
        assertTrue(sweep.maxY>box.maxY+1,"Banked wing needs more than a translated old box");
    }
    @Test void translationOnlyDoesNotInventRotationalGroundPenetration() {
        var start=new RailAircraftMotionState.Frame(new Vec3(0,2,0),new Quaterniond());
        var end=new RailAircraftMotionState.Frame(new Vec3(1,2,0),new Quaterniond());
        var box=new AABB(-4,0,-3,4,5,3);var sweep=RailAircraftMotionState.rotationSweep(start,box,end);
        assertEquals(0,sweep.minY,1e-10);assertEquals(5,sweep.maxY,1e-10);
        assertEquals(-4,sweep.minX,1e-10);assertEquals(5,sweep.maxX,1e-10);
    }
    @Test void malformedOrUnboundedEnvelopesAreRejected() {
        var frame=new RailAircraftMotionState.Frame(Vec3.ZERO,new Quaterniond());
        assertThrows(IllegalArgumentException.class,()->RailAircraftMotionState.rotationSweep(frame,new AABB(0,0,0,1001,1,1),frame));
        assertThrows(IllegalArgumentException.class,()->RailAircraftMotionState.rotationSweep(frame,new AABB(Double.NaN,0,0,1,1,1),frame));
    }
}
