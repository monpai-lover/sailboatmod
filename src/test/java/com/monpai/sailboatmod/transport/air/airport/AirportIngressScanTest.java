package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AirportIngressScanTest {
    private AirportGroundValidation.Scan scan(){return AirportGroundValidation.ingressScan(new Vec3(40,81,0),new Vec3(0,81,0),30,8);}
    @Test void ingressGenerationAndTerrainReadsStayWithinSharedBudgetUntilAllSupportIsProven(){
        var scan=scan();AtomicInteger loaded=new AtomicInteger(),clear=new AtomicInteger(),support=new AtomicInteger();
        var result=scan.advance(0,p->{loaded.incrementAndGet();return true;},p->{clear.incrementAndGet();return true;},p->{support.incrementAndGet();return true;});
        assertEquals(AirportGroundValidation.Result.WAITING,result);assertEquals(0,loaded.get()+clear.get()+support.get());
        int ticks=0;
        while(result==AirportGroundValidation.Result.WAITING&&ticks++<300){
            result=scan.advance(2048,p->{loaded.incrementAndGet();return true;},p->{clear.incrementAndGet();return true;},p->{support.incrementAndGet();return true;});
            assertTrue(scan.stepsUsed()<=2048);
        }
        assertEquals(AirportGroundValidation.Result.VALID,result);assertTrue(ticks>1);
        assertTrue(loaded.get()>0&&clear.get()>0&&support.get()>0,"The extra corridor must actually inspect both free volume and support");
    }
    @Test void unloadedIngressCannotReadBlocksOrBecomeClear(){
        var scan=scan();AtomicInteger terrain=new AtomicInteger();var result=AirportGroundValidation.Result.WAITING;
        for(int i=0;i<100&&result==AirportGroundValidation.Result.WAITING;i++)
            result=scan.advance(2048,p->false,p->{terrain.incrementAndGet();return true;},p->{terrain.incrementAndGet();return true;});
        assertEquals(AirportGroundValidation.Result.UNLOADED,result);assertEquals(0,terrain.get());
    }
    @Test void obstructionAndMissingSupportFailInsteadOfCopyingOriginalRouteValidation(){
        var obstructed=scan();var missingSupport=scan();var a=AirportGroundValidation.Result.WAITING;var b=a;
        for(int i=0;i<100&&(a==AirportGroundValidation.Result.WAITING||b==AirportGroundValidation.Result.WAITING);i++){
            a=obstructed.advance(2048,p->true,p->false,p->true);
            b=missingSupport.advance(2048,p->true,p->true,p->false);
        }
        assertEquals(AirportGroundValidation.Result.OBSTRUCTED,a);assertEquals(AirportGroundValidation.Result.UNSUPPORTED,b);
    }
    @Test void ingressScanRejectsNonFiniteOrOverlongSegmentsAndNeverExpandsExistingLimits(){
        assertEquals(AirportGroundValidation.Result.INVALID_GEOMETRY,AirportGroundValidation.ingressScan(Vec3.ZERO,new Vec3(257,0,0),30,8).advance(1,p->true,p->true,p->true));
        assertEquals(AirportGroundValidation.Result.INVALID_GEOMETRY,AirportGroundValidation.ingressScan(Vec3.ZERO,new Vec3(10,0,0),65,8).advance(1,p->true,p->true,p->true));
        assertEquals(AirportGroundValidation.Result.INVALID_GEOMETRY,AirportGroundValidation.ingressScan(Vec3.ZERO,new Vec3(Double.NaN,0,0),30,8).advance(1,p->true,p->true,p->true));
    }
}
