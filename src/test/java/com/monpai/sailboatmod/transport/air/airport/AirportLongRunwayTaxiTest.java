package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AirportLongRunwayTaxiTest {
    private static List<Vec3> line(Vec3 a,Vec3 b){
        List<Vec3> out=new ArrayList<>();int n=(int)Math.ceil(a.distanceTo(b)/256);
        for(int i=0;i<=n;i++)out.add(a.lerp(b,i/(double)n));return out;
    }
    private static AirportTaxiRoute route(List<Vec3> points){return new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"r","s",points,28,8,1,-1);}
    private static AirportFacility runway(){return new AirportFacility("r",AirportFacility.Type.RUNWAY,new BlockPos(0,81,0),new BlockPos(2048,81,0),28,12);}
    private static AirportParkingStand stand(Vec3 p){return new AirportParkingStand("s",p,90,28,28,8,true);}
    @Test void parserEnvelopeIs3072WhileSegmentsAndPointCountStayBounded(){
        assertTrue(route(line(new Vec3(0,81,0),new Vec3(3072,81,0))).validGeometry());
        assertFalse(route(line(new Vec3(0,81,0),new Vec3(3072.1,81,0))).validGeometry());
        assertTrue(route(List.of(new Vec3(0,81,0),new Vec3(256,81,0))).validGeometry());
        assertFalse(route(List.of(new Vec3(0,81,0),new Vec3(256.1,81,0))).validGeometry());
        assertThrows(IllegalArgumentException.class,()->route(java.util.stream.IntStream.range(0,65).mapToObj(i->new Vec3(i,81,0)).toList()));
    }
    @Test void correctCAndDEndpointRoutesCanReturnToTheirNearbyStands(){
        for(boolean c:new boolean[]{true,false}){
            BlockPos airport=new BlockPos(c?-32:-1600,81,560);
            var runway=new AirportFacility("r",AirportFacility.Type.RUNWAY,new BlockPos(c?64:-1473,81,c?512:608),new BlockPos(c?-1984:575,81,c?512:608),28,12);
            assertTrue(runway.start().distSqr(airport)<=2176D*2176&&runway.end().distSqr(airport)<=2176D*2176);
            Vec3 parked=new Vec3(c?128.5:-1791.5,81,c?512.5:608.5);
            Vec3 end=Vec3.atBottomCenterOf(runway.end());
            double offset=c?-64:64;
            List<Vec3> points=new ArrayList<>();points.add(end);points.add(end.add(0,0,offset));
            points.addAll(line(end.add(0,0,offset),parked.add(0,0,offset)).subList(1,line(end.add(0,0,offset),parked.add(0,0,offset)).size()));points.add(parked);
            var route=route(points);assertTrue(route.validGeometry(),"C/D endpoint corridor needs the bounded long envelope");
            assertTrue(AirportGroundValidation.withinManagementDistance(route,List.of(runway),List.of(stand(parked)),airport));
            var layout=new AirportGroundLayout(1,List.of(stand(parked)),List.of(route));
            assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.references(layout,List.of(runway)));
        }
    }
    @Test void ordinaryFarRoutesWrongEndsAndUnreferencedRunwaysNeverGainTheException(){
        var airport=new BlockPos(0,81,0);var s=stand(new Vec3(-20.5,81,.5));
        var wrong=route(line(new Vec3(1800.5,81,.5),s.position()));
        assertFalse(AirportGroundValidation.withinManagementDistance(wrong,List.of(runway()),List.of(s),airport));
        var linked=route(line(Vec3.atBottomCenterOf(runway().end()),s.position()));
        assertFalse(AirportGroundValidation.withinManagementDistance(linked,List.of(),List.of(s),airport));
        var ordinary=route(List.of(new Vec3(800.5,81,.5),new Vec3(750.5,81,.5)));
        assertFalse(AirportGroundValidation.withinManagementDistance(ordinary,List.of(runway()),List.of(s),airport));
    }
    @Test void remoteSegmentsMustStayInsideTheExpandedDeckNotJustTheirNodes(){
        var s=stand(new Vec3(-20.5,81,.5));List<Vec3> p=new ArrayList<>(line(Vec3.atBottomCenterOf(runway().end()),new Vec3(650.5,81,100.5)));
        p.add(new Vec3(450.5,81,220.5));p.addAll(line(p.getLast(),s.position()).subList(1,line(p.getLast(),s.position()).size()));
        assertFalse(AirportGroundValidation.withinManagementDistance(route(p),List.of(runway()),List.of(s),new BlockPos(0,81,0)));
    }
    @Test void groundConstructorDoesNotEagerlyBuildCells() throws Exception {
        var scan=new AirportGroundValidation.Scan(AirportGroundValidationTest.layout(),List.of(AirportGroundValidationTest.runway()));
        var cells=AirportGroundValidation.Scan.class.getDeclaredField("cells");cells.setAccessible(true);
        assertTrue(((List<?>)cells.get(scan)).isEmpty(),"cell construction belongs to the tick budget too");
        assertEquals(AirportGroundValidation.Result.WAITING,scan.advance(7,p->{throw new AssertionError("build must finish before world reads");},p->true,p->true));
        assertEquals(7,scan.stepsUsed());
    }
    @Test void aRemoteStandCannotBorrowTheRunwayLinkedException(){
        var s=stand(new Vec3(1800.5,81,.5));var in=route(line(Vec3.atBottomCenterOf(runway().end()),s.position()));
        assertFalse(AirportGroundValidation.withinManagementDistance(in,List.of(runway()),List.of(s),new BlockPos(0,81,0)));
    }
    @Test void deckMargin96IsClosedAndAnAdditionalCentimetreIsRejected(){
        var s=stand(new Vec3(-20.5,81,.5));
        for(double z:new double[]{110.5,110.51}){
            var points=new ArrayList<>(line(Vec3.atBottomCenterOf(runway().end()),new Vec3(1000.5,81,z)));
            var local=line(points.getLast(),new Vec3(300.5,81,z));points.addAll(local.subList(1,local.size()));var finish=line(points.getLast(),s.position());points.addAll(finish.subList(1,finish.size()));
            assertEquals(z==110.5,AirportGroundValidation.withinManagementDistance(route(points),List.of(runway()),List.of(s),new BlockPos(0,81,0)));
        }
    }
    @Test void longGroundBuildAndTerrainBothRemainIncrementalUntilRealCompletion(){
        var runway=new AirportFacility("r",AirportFacility.Type.RUNWAY,new BlockPos(64,81,512),new BlockPos(-1984,81,512),28,12);
        var s=stand(new Vec3(128.5,81,512.5));var points=new ArrayList<Vec3>();points.add(Vec3.atBottomCenterOf(runway.end()));points.add(points.getFirst().add(0,0,-64));
        var returning=line(points.getLast(),s.position().add(0,0,-64));points.addAll(returning.subList(1,returning.size()));points.add(s.position());
        var scan=new AirportGroundValidation.Scan(new AirportGroundLayout(1,List.of(s),List.of(route(points))),List.of(runway));
        var result=AirportGroundValidation.Result.WAITING;long totalReads=0;int ticks=0;
        while(result==AirportGroundValidation.Result.WAITING&&ticks++<AirportGroundValidation.MAX_SCAN_STEPS/2048+2){
            int[] reads={0};result=scan.advance(2048,p->{reads[0]++;return true;},p->true,p->true);
            assertTrue(scan.stepsUsed()<=2048);assertTrue(reads[0]<=2048);totalReads+=reads[0];
        }
        assertEquals(AirportGroundValidation.Result.VALID,result);assertTrue(ticks>200);assertTrue(totalReads>2*262144L,"this real corridor exceeded the previous ground capacity");
    }
    @Test void longOutboundMustJoinTheRunwayStartAndRetainItsOutsideHold(){
        var s=stand(new Vec3(2140.5,81,.5));var p=new ArrayList<>(line(s.position(),new Vec3(-50.5,81,.5)));p.add(new Vec3(.5,81,.5));
        var out=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"r","s",p,28,8,1,p.size()-2);
        assertTrue(AirportGroundValidation.withinManagementDistance(out,List.of(runway()),List.of(s),new BlockPos(2048,81,0)));
        assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.references(new AirportGroundLayout(1,List.of(s),List.of(out)),List.of(runway())));
        var wrong=new AirportTaxiRoute("wrong",AirportTaxiRoute.Direction.OUTBOUND,"r","s",line(s.position(),new Vec3(500.5,81,.5)),28,8,1,1);
        assertFalse(AirportGroundValidation.withinManagementDistance(wrong,List.of(runway()),List.of(s),new BlockPos(2048,81,0)));
    }
    @Test void visualPlannerCanSubmitTheLongLinkedCandidateThroughTheNormalPacket(){
        var r=new AirportFacility("r",AirportFacility.Type.RUNWAY,new BlockPos(64,81,512),new BlockPos(-1984,81,512),28,12);
        var s=stand(new Vec3(128.5,81,512.5));var points=new ArrayList<Vec3>();points.add(Vec3.atBottomCenterOf(r.end()));points.add(points.getFirst().add(0,0,-64));
        var straight=line(points.getLast(),s.position().add(0,0,-64));points.addAll(straight.subList(1,straight.size()));points.add(s.position());
        var snapshot=new AirportScreenData(net.minecraft.world.level.Level.OVERWORLD.location(),new BlockPos(-32,81,560),"C","Town","Owner",true,0,List.of(r),new AirportGroundLayout(0,List.of(s),List.of()),List.of(),"");
        var model=new com.monpai.sailboatmod.client.airport.AirportPlannerModel(snapshot);assertTrue(model.startNew(com.monpai.sailboatmod.client.airport.AirportPlannerModel.Tool.INBOUND));
        model.updateProperties(model.draft().orElseThrow().withRunwayId("r").withStandId("s").withPoints(points).withDimensions(24,28,8));
        assertTrue(model.canSave());var submitted=AirportTaxiRoute.load(model.savePacket().toActionTag().getCompound("TaxiRoute"));assertEquals(points,submitted.points());assertTrue(submitted.length()>2048);
    }
}
