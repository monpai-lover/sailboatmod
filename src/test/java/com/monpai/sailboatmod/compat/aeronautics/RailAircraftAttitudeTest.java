package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftAttitudeTest {
    private double[] read(Direction nose, Quaterniond q) throws Exception {
        return (double[]) RailAircraftMotionState.class.getDeclaredMethod("attitude", Direction.class, Quaterniond.class)
                .invoke(null,nose,q);
    }
    @Test void nativeAttitudeRoundTripsAllNosesAndBothSignsOfPitchAndBank() throws Exception {
        for(var nose:new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST})
            for(double pitch:new double[]{-.21,0,.18}) for(double bank:new double[]{-.48,0,.51})
                for(double heading:new double[]{-3.12,-1.2,.3,3.12}) {
                    var tangent=new Vec3(Math.cos(pitch)*Math.cos(heading),Math.sin(pitch),Math.cos(pitch)*Math.sin(heading));
                    assertArrayEquals(new double[]{pitch,bank,heading},read(nose,RailAircraftMotionState.orientation(nose,tangent,bank)),1e-9);
                }
    }
    @Test void nativeQuaternionSignAndScaleDoNotChangeAttitude() throws Exception {
        var q=RailAircraftMotionState.orientation(Direction.WEST,new Vec3(-1,.12,.05),.3);
        assertArrayEquals(read(Direction.WEST,q),read(Direction.WEST,new Quaterniond(q).mul(-2)),1e-9);
    }
}
