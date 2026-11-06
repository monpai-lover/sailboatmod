package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AirportGroundValidationTest {
    static AirportFacility runway(){return new AirportFacility("main",AirportFacility.Type.RUNWAY,new BlockPos(0,64,0),new BlockPos(48,64,0),7,8);}
    static AirportGroundLayout layout(){return new AirportGroundLayout(2,List.of(new AirportParkingStand("a",new Vec3(-20.5,64,.5),0,12,8,8,true)),List.of(
            new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"main","a",List.of(new Vec3(-20.5,64,.5),new Vec3(-10,64,.5),new Vec3(.5,64,.5)),8,8,2,1),
            new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"main","a",List.of(new Vec3(40.5,64,.5),new Vec3(-20.5,64,.5)),8,8,2,-1)));}
    @Test void missingRunwayOrStandAndDisconnectedEndpointsAreRejected(){
        assertEquals(AirportGroundValidation.Result.MISSING_REFERENCE,AirportGroundValidation.references(layout(),List.of()));
        var value=layout();var wrong=new AirportTaxiRoute("bad",AirportTaxiRoute.Direction.INBOUND,"main","a",List.of(new Vec3(40.5,64,.5),new Vec3(-40,64,.5)),8,8,2,-1);
        assertEquals(AirportGroundValidation.Result.DISCONNECTED_ROUTE,AirportGroundValidation.references(new AirportGroundLayout(3,value.stands(),List.of(wrong)),List.of(runway())));
        var noStand=new AirportGroundLayout(3,List.of(),value.taxiRoutes());
        assertEquals(AirportGroundValidation.Result.MISSING_REFERENCE,AirportGroundValidation.references(noStand,List.of(runway())));
    }
    @Test void unknownCellWinsBeforeAnyTerrainReadAndBudgetsNeverImplyClear(){
        var scan=new AirportGroundValidation.Scan(layout(),List.of(runway()));
        assertEquals(AirportGroundValidation.Result.WAITING,scan.advance(7,p->true,p->{throw new AssertionError();},p->{throw new AssertionError();}));
        assertEquals(7,scan.stepsUsed());
        assertEquals(AirportGroundValidation.Result.UNLOADED,scan.advance(262144,p->p.getX()<15,p->{throw new AssertionError();},p->{throw new AssertionError();}));
    }
    @Test void groundCorridorChecksWingSweepAndSupportIncludingTurnOutsideCentreLine(){
        assertEquals(AirportGroundValidation.Result.OBSTRUCTED,AirportGroundValidation.validate(layout(),List.of(runway()),p->true,p->!p.equals(new BlockPos(-10,66,4)),p->true));
        assertEquals(AirportGroundValidation.Result.UNSUPPORTED,AirportGroundValidation.validate(layout(),List.of(runway()),p->true,p->true,p->!p.equals(new BlockPos(-10,63,4))));
        assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.validate(layout(),List.of(runway()),p->true,p->true,p->true));
    }
    @Test void standAndRouteSeparatedByOneBlockAreNotConnected(){
        var original=layout();
        var raised=new AirportParkingStand("a",new Vec3(-20.5,65,.5),0,12,8,8,true);
        var disconnected=new AirportGroundLayout(3,List.of(raised),original.taxiRoutes());
        assertEquals(AirportGroundValidation.Result.DISCONNECTED_ROUTE,AirportGroundValidation.references(disconnected,List.of(runway())));
        assertNotEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.validate(disconnected,List.of(runway()),p->true,p->p.getY()!=64,p->p.getY()==63||p.getY()==64));
    }
    @Test void overlappingGroundVolumesRetainBothSupportAndClearanceRequirements(){
        // The upper stand's solid support is inside the lower stand's clearance envelope.
        var lower=new AirportParkingStand("lower",new Vec3(-20.5,64,.5),0,12,8,8,true);
        var upper=new AirportParkingStand("upper",new Vec3(-20.5,65,.5),0,12,8,8,true);
        for(var order:List.of(List.of(upper,lower),List.of(lower,upper))){
            var overlapping=new AirportGroundLayout(3,order,List.of());
            assertEquals(AirportGroundValidation.Result.OBSTRUCTED,AirportGroundValidation.validate(overlapping,List.of(),p->true,p->p.getY()!=64,p->p.getY()==63||p.getY()==64));
        }
    }
    @Test void hugeValidGeometryStopsAtAggregateEditorBudget(){
        List<AirportParkingStand> stands=new ArrayList<>();for(int i=0;i<32;i++)stands.add(new AirportParkingStand("s"+i,new Vec3(i*70,64,0),0,64,64,64,true));
        AtomicInteger reads=new AtomicInteger();
        assertEquals(AirportGroundValidation.Result.TOO_LARGE,AirportGroundValidation.validate(new AirportGroundLayout(1,stands,List.of()),List.of(),p->{reads.incrementAndGet();return true;},p->true,p->true));
        assertEquals(0,reads.get());
    }
}
