package com.monpai.sailboatmod.transport.air.ground;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission.*;

class GroundRecoveryIngressToleranceTest {
    @Test void fixedFiveCentimeterAllowanceIncludesTheScannedStartButNeverGrowsWithDrift(){
        UUID id=UUID.randomUUID(),body=UUID.randomUUID();
        var p=new Plan(id,body,"minecraft:overworld",2,"runway","stand",id.toString(),
                List.of(new Vec3(0,81,0),new Vec3(-50,81,0),new Vec3(-80,81,0)),1,
                List.of(new Vec3(-100,81,100),new Vec3(-50,81,100)),90,-90,1,4,new Calibration(true,.75,32,30,15,0));
        var s=new State(id,body,p.dimensionId(),2,"runway","stand",id.toString(),Phase.TAXI_OUT,1,0,-1,false);
        Vec3 current=new Vec3(40,83,0),forward=new Vec3(-1,0,0);
        var c=GroundRecoveryIngress.candidate(p,s,current,forward,10,6);assertNotNull(c);
        var proof=GroundRecoveryIngress.authorize(c,new GroundRecoveryIngress.Evidence(true,true,true,true,true));assertNotNull(proof);
        assertAll(
                ()->assertTrue(GroundRecoveryIngress.allows(proof,p,s,current.add(.01,0,0),forward),"One centimeter native pose jitter remains inside the fixed allowance"),
                ()->assertTrue(c.scanStart().x>=40.05-1e-8,"The terrain scan must actually include the fixed allowance"),
                ()->assertFalse(GroundRecoveryIngress.allows(proof,p,s,current.add(1,0,0),forward),"The proof cannot grow to follow a one-meter drift")
        );
    }
}
