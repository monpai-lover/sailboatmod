package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
    @Test void almostPureYawDoesNotInflateTheTaipeiShallowWaterSweepVertically() {
        // Exact stalled first route-turn samples from the Sable controller at tick 149398.
        var from = new RailAircraftMotionState.Frame(
                new Vec3(135084.88085598146, 65.50043517754473, -29003.423386539118),
                new Quaterniond(0.00046533221029676497, -0.872763991355896,
                        -0.00026026307023130357, 0.48814207315444946));
        var to = new RailAircraftMotionState.Frame(
                new Vec3(135084.88443011927, 65.50043682795375, -29003.423182788985),
                RailAircraftMotionState.orientation(Direction.EAST,
                        new Vec3(-0.5815965293723715, 0, 0.8134773979785869), 0));
        var actual = new AABB(135077.83820255846, 63.99563754390692, -29009.285867905244,
                135087.1397455968, 67.00416642456548, -28999.32601217553);

        var sweep = RailAircraftMotionState.rotationSweep(from, actual, to);
        assertTrue(sweep.minY + 1.0 / 64.0 > 64.0,
                "The yaw arc cannot turn milliblock bed contact into an underwater obstacle");
    }

    @Test void appliedMotionUsesTheSameNormalizedEndpointArcAsTheSweep() {
        var state = new RailAircraftMotionState();
        var controller = new Object();
        var world = new Object();
        var body = new Object();
        var anchor = new Vec3(100, 70, 20);
        var before = new Quaterniond().rotateY(.4);
        double nativeScale = 1.000004;
        var from = new RailAircraftMotionState.Frame(anchor, new Quaterniond(
                before.x * nativeScale, before.y * nativeScale, before.z * nativeScale, before.w * nativeScale));
        var token = state.acquire(controller, world, body, UUID.randomUUID(), UUID.randomUUID(),
                Direction.EAST, from, false).token();
        var to = new Vec3(101, 70.5, 20);
        var tangent = new Vec3(.98, .1, .16);
        double bank = Math.toRadians(30);
        var target = RailAircraftMotionState.orientation(Direction.EAST, tangent, bank);
        assertTrue(state.move(controller, world, body, token, to, tangent, bank, 100).success());
        var box = new AABB(89, 68, 13, 108, 74, 27);
        var sweep = RailAircraftMotionState.rotationSweep(from, box,
                new RailAircraftMotionState.Frame(to, target));

        for (int step = 1; step <= 20; step++) {
            var frame = state.advance(state.session(token), .0025);
            var expected = new Quaterniond(before).slerp(target, step / 20.0).normalize();
            var orientation = frame.orientation();
            assertEquals(expected.x, orientation.x, 1e-12);
            assertEquals(expected.y, orientation.y, 1e-12);
            assertEquals(expected.z, orientation.z, 1e-12);
            var relative = orientation.mul(new Quaterniond(before).conjugate());
            for (int corner = 0; corner < 8; corner++) {
                var point = new Vector3d((corner & 1) == 0 ? box.minX : box.maxX,
                        (corner & 2) == 0 ? box.minY : box.maxY, (corner & 4) == 0 ? box.minZ : box.maxZ)
                        .sub(anchor.x, anchor.y, anchor.z);
                relative.transform(point).add(frame.anchor().x, frame.anchor().y, frame.anchor().z);
                assertTrue(sweep.contains(new Vec3(point.x, point.y, point.z)));
            }
        }
    }
    @Test void malformedOrUnboundedEnvelopesAreRejected() {
        var frame=new RailAircraftMotionState.Frame(Vec3.ZERO,new Quaterniond());
        assertThrows(IllegalArgumentException.class,()->RailAircraftMotionState.rotationSweep(frame,new AABB(0,0,0,1001,1,1),frame));
        assertThrows(IllegalArgumentException.class,()->RailAircraftMotionState.rotationSweep(frame,new AABB(Double.NaN,0,0,1,1,1),frame));
    }
}
