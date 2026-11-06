package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.air.airport.AirportGroundValidation;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import com.monpai.sailboatmod.transport.air.ground.GroundRecoveryIngress;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundRecoveryIngressUnloadRegressionTest {
    private RegisteredVehicleControllerBlockEntity fixture()throws Exception{
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        var r=(RegisteredVehicleControllerBlockEntity)((Unsafe)f.get(null)).allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        UUID id=UUID.randomUUID(),body=UUID.randomUUID();
        var p=new AircraftGroundMission.Plan(id,body,"minecraft:overworld",2,"runway","stand",id.toString(),
                List.of(new Vec3(0,81,0),new Vec3(-50,81,0),new Vec3(-80,81,0)),1,
                List.of(new Vec3(-100,81,100),new Vec3(-50,81,100)),90,-90,1,4,
                new AircraftGroundMission.Calibration(true,.75,32,30,15,0));
        var s=new AircraftGroundMission.State(id,body,p.dimensionId(),2,"runway","stand",id.toString(),AircraftGroundMission.Phase.TAXI_OUT,1,0,-1,false);
        var c=GroundRecoveryIngress.candidate(p,s,new Vec3(40,83,1),new Vec3(-1,0,0),10,6);
        assertNotNull(c,"Pure ingress GREEN must be applied before this independent RVC RED");
        set(r,"groundIngressCandidate",c);set(r,"groundIngressClaimRevision",-1L);return r;
    }
    private static final class Port implements RegisteredVehicleControllerBlockEntity.GroundIngressPort{
        boolean bound=true,loaded=true,claims=true;long revision=7;int calls;boolean valid;
        final List<AirportGroundValidation.Scan> scans=new ArrayList<>();
        public boolean boundAndFreshStopped(){return bound;}
        public boolean fullyLoaded(){return loaded;}
        public boolean claimsValid(){return claims;}
        public long claimRevision(){return revision;}
        public AirportGroundValidation.Result advance(AirportGroundValidation.Scan scan,int budget){assertTrue(budget>0&&budget<=2048);scans.add(scan);calls++;return valid?AirportGroundValidation.Result.VALID:AirportGroundValidation.Result.WAITING;}
    }
    private RegisteredVehicleControllerBlockEntity.GroundIngressStatus advance(Object r,Port p)throws Exception{
        Method m=RegisteredVehicleControllerBlockEntity.class.getDeclaredMethod("advanceGroundIngress",RegisteredVehicleControllerBlockEntity.GroundIngressPort.class);m.setAccessible(true);
        return (RegisteredVehicleControllerBlockEntity.GroundIngressStatus)m.invoke(r,p);
    }
    @Test void lossOfFullTerrainRevokesProofAndRequiresAnewScanWithinTheOriginalCandidate()throws Exception{
        var r=fixture();var p=new Port();p.valid=true;
        assertEquals(RegisteredVehicleControllerBlockEntity.GroundIngressStatus.READY,advance(r,p));
        Object proof=get(r,"groundIngressProof"),scan=get(r,"groundIngressScan"),candidate=get(r,"groundIngressCandidate");
        assertNotNull(proof);p.loaded=false;
        assertEquals(RegisteredVehicleControllerBlockEntity.GroundIngressStatus.WAITING,advance(r,p));
        assertNull(get(r,"groundIngressProof"),"The pre-unload VALID result must not authorize terrain after reload");
        assertNull(get(r,"groundIngressScan"));assertSame(candidate,get(r,"groundIngressCandidate"));
        p.loaded=true;p.valid=false;
        assertEquals(RegisteredVehicleControllerBlockEntity.GroundIngressStatus.WAITING,advance(r,p));
        assertNotSame(scan,get(r,"groundIngressScan"));assertNull(get(r,"groundIngressProof"));
        p.valid=true;assertEquals(RegisteredVehicleControllerBlockEntity.GroundIngressStatus.READY,advance(r,p));
        assertNotSame(proof,get(r,"groundIngressProof"));assertSame(candidate,get(r,"groundIngressCandidate"));
    }
    private static Object get(Object r,String n)throws Exception{Field f=r.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(r);}
    private static void set(Object r,String n,Object v)throws Exception{Field f=r.getClass().getDeclaredField(n);f.setAccessible(true);f.set(r,v);}
}
