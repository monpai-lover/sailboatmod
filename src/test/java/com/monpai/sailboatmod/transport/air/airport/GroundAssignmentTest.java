package com.monpai.sailboatmod.transport.air.airport;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundAssignmentTest {
    private final AirportFacility out=new AirportFacility("west",AirportFacility.Type.RUNWAY,new BlockPos(0,64,0),new BlockPos(-48,64,0),8,8);
    private final AirportFacility in=new AirportFacility("east",AirportFacility.Type.RUNWAY,out.end(),out.start(),8,8);
    private final AirportParkingStand stand=new AirportParkingStand("stand",new Vec3(20.5,64,.5),90,12,8,8,true);
    private final AirportTaxiRoute outbound=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"west","stand",List.of(stand.position(),new Vec3(10.5,64,.5),new Vec3(.5,64,.5)),8,8,1,1);
    private final AirportTaxiRoute inbound=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"east","stand",List.of(new Vec3(.5,64,.5),stand.position()),8,8,1,-1);
    private AirportGroundLayout layout(){return new AirportGroundLayout(8,List.of(stand),List.of(outbound,inbound));}
    private GroundAssignment assignment(){return new GroundAssignment(8,"west","stand","out","in");}
    @Test void oppositeRolesChangeOnlyRunwayAndRoundTripRetainsAllCapturedIds(){
        var arrival=assignment().forDirection(layout(),List.of(out,in),AirportTaxiRoute.Direction.INBOUND).orElseThrow();
        assertEquals(new GroundAssignment(8,"east","stand","out","in"),arrival);
        assertEquals(assignment(),arrival.forDirection(layout(),List.of(out,in),AirportTaxiRoute.Direction.OUTBOUND).orElseThrow());
        assertEquals(arrival,GroundAssignment.load(arrival.save()),"existing five-field NBT retains the selected role without a format change");
    }
    @Test void staleRevisionMissingRouteAndUnrelatedRunwayCannotAutoSelect(){
        assertTrue(assignment().forDirection(layout().withRevision(9),List.of(out,in),AirportTaxiRoute.Direction.INBOUND).isEmpty());
        assertTrue(new GroundAssignment(8,"west","stand","missing","in").forDirection(layout(),List.of(out,in),AirportTaxiRoute.Direction.OUTBOUND).isEmpty());
        assertTrue(new GroundAssignment(8,"other","stand","out","in").forDirection(layout(),List.of(out,in),AirportTaxiRoute.Direction.OUTBOUND).isEmpty());
        assertTrue(assignment().forDirection(layout(),List.of(out),AirportTaxiRoute.Direction.INBOUND).isEmpty());
    }
    @Test void anotherStandAndSwappedDirectionsCannotBorrowTheCapturedPair(){
        assertTrue(new GroundAssignment(8,"west","other","out","in").forDirection(layout(),List.of(out,in),AirportTaxiRoute.Direction.OUTBOUND).isEmpty());
        assertTrue(new GroundAssignment(8,"west","stand","in","out").forDirection(layout(),List.of(out,in),AirportTaxiRoute.Direction.OUTBOUND).isEmpty());
    }
    @Test void legacySameRunwayPairRemainsIdenticalAndWinsOverAnotherInbound(){
        var legacyIn=new AirportTaxiRoute("legacy",AirportTaxiRoute.Direction.INBOUND,"west","stand",List.of(new Vec3(-47.5,64,.5),stand.position()),8,8,1,-1);
        var layout=new AirportGroundLayout(8,List.of(stand),List.of(outbound,inbound,legacyIn));
        var selected=GroundAssignment.select(layout,List.of(out,in),"west","stand",AirportTaxiRoute.Direction.OUTBOUND).orElseThrow();
        assertEquals(new GroundAssignment(8,"west","stand","out","legacy"),selected);
        assertEquals(selected,selected.forDirection(layout,List.of(out,in),AirportTaxiRoute.Direction.INBOUND).orElseThrow());
    }
}
