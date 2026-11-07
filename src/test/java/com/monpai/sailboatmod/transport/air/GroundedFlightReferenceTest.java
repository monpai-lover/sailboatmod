package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.physical.GroundSurfaceObservation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GroundedFlightReferenceTest {
    private static final UUID BODY=new UUID(50,1);
    private static final long NOW=10_000_000_000L;
    private static long nanos(long tick) { return NOW+tick*50_000_000L; }
    private GroundSurfaceObservation surface(UUID body,long tick,double plane,double radius,int shift) {
        var wheels=new ArrayList<GroundSurfaceObservation.WheelEvidence>();
        for(var p:List.of(new BlockPos(0,0,0),new BlockPos(4,0,0),new BlockPos(0,0,4),new BlockPos(4,0,4))) {
            var rays=new ArrayList<GroundSurfaceObservation.RayHit>();
            double extension=radius+.1;
            for(int i=-1;i<=1;i++)rays.add(new GroundSurfaceObservation.RayHit(i,
                    new Vec3(p.getX()+i,plane+extension,p.getZ()),new Vec3(p.getX()+i,plane+extension-5,p.getZ()),
                    new Vec3(p.getX()+i,plane,p.getZ()),BlockPos.containing(p.getX()+i,plane-1,p.getZ()),
                    extension,true,true,true,true));
            wheels.add(new GroundSurfaceObservation.WheelEvidence(p.offset(shift,0,0),body,radius,
                    .65-(extension+.10833333333333334-radius),extension,rays));
        }
        var surface=GroundSurfaceObservation.fromEvidence(body,tick,nanos(tick),4,wheels);
        assertTrue(surface.available(),surface.reason());return surface;
    }
    private GroundedFlightReference.Frame frame(UUID body,long tick,double reference,double speed,double angular,Object config) {
        return new GroundedFlightReference.Frame(body,reference,speed,angular,nanos(tick)-1,true,4,config);
    }
    private Optional<GroundedFlightReference.Measurement> sample(GroundedFlightReference tracker,long tick,double plane,double height) {
        return tracker.observe(surface(BODY,tick,plane,.75,0),frame(BODY,tick,plane+height,0,0,"original"),tick,nanos(tick));
    }
    private GroundedFlightReference.Measurement stable(GroundedFlightReference tracker,long first,double plane,double height) {
        for(long tick=first;tick<first+40;tick+=5)assertTrue(sample(tracker,tick,plane,height).isEmpty());
        return sample(tracker,first+40,plane,height).orElseThrow();
    }
    @Test void measuredReferenceUsesActualSurfaceAtArbitraryWorldHeightsAndGearHeights() {
        for(double plane:new double[]{-60,0,81,200})for(double height:new double[]{1.2,2.651264190673828,7.4}) {
            var measured=stable(new GroundedFlightReference(),0,plane,height);
            assertEquals(height,measured.referenceAboveSurface(),1e-10);
            assertEquals(plane,measured.planeY());assertTrue(measured.matchesSurface(plane));
            assertFalse(measured.matchesSurface(plane+.01));assertFalse(measured.matchesSurface(Double.NaN));
        }
    }
    @Test void waitingForFortyTicksDoesNotAcceptTheFirstGroundedSample() {
        var tracker=new GroundedFlightReference();assertTrue(sample(tracker,0,-60,2.65).isEmpty());
        for(long tick=5;tick<40;tick+=5)assertTrue(sample(tracker,tick,-60,2.65).isEmpty());
        assertTrue(sample(tracker,40,-60,2.65).isPresent());
    }
    @Test void movingRotatingUnavailableAndWrongBodyFramesBreakTheStableInterval() {
        for(int bad=0;bad<5;bad++) {
            var tracker=new GroundedFlightReference();stable(tracker,0,-60,2.65);
            var valid=frame(BODY,45,-57.35,0,0,"original");
            var invalid=new GroundedFlightReference.Frame(bad==4?UUID.randomUUID():BODY,valid.referenceY(),
                    bad==0?.251:0,bad==1?Math.toRadians(2.01):0,valid.sampledNanos(),bad!=2,bad==3?3:4,"original");
            assertTrue(tracker.observe(surface(BODY,45,-60,.75,0),invalid,45,nanos(45)).isEmpty());
            stable(tracker,50,-60,2.65);
        }
    }
    @Test void staleFutureAndFrozenNativeSnapshotsCannotProduceAReference() {
        var tracker=new GroundedFlightReference();stable(tracker,0,-60,2.65);
        for(long sample:new long[]{nanos(45)-250_000_001L,nanos(45)+1}) {
            var invalid=new GroundedFlightReference.Frame(BODY,-57.35,0,0,sample,true,4,"original");
            assertTrue(tracker.observe(surface(BODY,45,-60,.75,0),invalid,45,nanos(45)).isEmpty());
        }
        stable(tracker,50,-60,2.65);
        var frozen=frame(BODY,90,-57.35,0,0,"original");
        assertTrue(tracker.observe(surface(BODY,95,-60,.75,0),frozen,95,nanos(95)).isEmpty());
        stable(tracker,100,-60,2.65);
    }
    @Test void staleSurfaceAndMissingNativeProofNeverUseThePreviousMeasurement() {
        for(int bad=0;bad<3;bad++) {
            var tracker=new GroundedFlightReference();stable(tracker,0,-60,2.65);
            var proof=bad==0?GroundSurfaceObservation.unavailable("miss"):surface(BODY,bad==1?44:46,-60,.75,0);
            assertTrue(tracker.observe(proof,frame(BODY,45,-57.35,0,0,"original"),45,nanos(45)).isEmpty());
            stable(tracker,50,-60,2.65);
        }
    }
    @Test void bodyWheelLocationTireAndConfigurationChangesRequireNewMeasurements() {
        for(int change=0;change<4;change++) {
            var tracker=new GroundedFlightReference();stable(tracker,0,-60,2.65);
            UUID body=change==0?new UUID(50,2):BODY;
            for(long tick=45;tick<=85;tick+=5) {
                var proof=surface(body,tick,-60,change==1?1.25:.75,change==2?1:0);
                var measured=tracker.observe(proof,frame(body,tick,-57.35,0,0,change==3?"new load":"original"),tick,nanos(tick));
                assertEquals(tick==85,measured.isPresent(),"change="+change+" tick="+tick);
            }
        }
    }
    @Test void excessiveGapClockRegressionAndExplicitResetRequireAFreshInterval() {
        var tracker=new GroundedFlightReference();stable(tracker,0,-60,2.65);
        stable(tracker,55,-60,2.65); // 15-tick gap resets continuity
        stable(tracker,0,-60,2.65); // clock moved backwards
        tracker.reset();stable(tracker,45,-60,2.65);
        stable(new GroundedFlightReference(),90,-60,2.65); // restart has no trusted height
    }
    @Test void gradualOffsetDriftCannotAccumulateAcrossAnOtherwiseStableInterval() {
        var tracker=new GroundedFlightReference();
        for(long tick=0;tick<=80;tick+=5)assertTrue(sample(tracker,tick,-60,2.65+tick*.004).isEmpty());
        stable(tracker,85,-60,3.2);
    }
    @Test void invalidReferenceDimensionsRejectInsteadOfUsingHullOrSavedValues() {
        for(double height:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.1,65}) {
            var tracker=new GroundedFlightReference();assertTrue(sample(tracker,0,-60,height).isEmpty());
        }
    }
    @Test void completedMeasurementExpiresBeforeADeferredSubmission() {
        var measured=stable(new GroundedFlightReference(),0,-60,2.65);
        assertTrue(measured.freshAt(40,nanos(40)));
        assertFalse(measured.freshAt(41,nanos(40)));
        assertFalse(measured.freshAt(40,nanos(40)+250_000_001L));
        assertFalse(measured.freshAt(40,nanos(40)-2));
    }
    @Test void duplicatePollDoesNotAdvanceOrReturnAnAlreadyReadyReference() {
        var tracker=new GroundedFlightReference();stable(tracker,0,-60,2.65);
        assertTrue(sample(tracker,40,-60,2.65).isEmpty());
        assertTrue(sample(tracker,45,-60,2.65).isPresent());
    }
    @Test void bothRunwayEndpointsUseMeasuredOffsetWithDifferentAirportElevations() {
        var measured=stable(new GroundedFlightReference(),0,-60,2.651264190673828);
        var source=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("a",
                com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(0,-60,0),new BlockPos(200,-60,0),32,16);
        var target=new com.monpai.sailboatmod.transport.air.airport.AirportFacility("b",
                com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,new BlockPos(1000,12,0),new BlockPos(1200,12,0),32,16);
        var route=FlightRoutePlanner.plan(AircraftClass.FIXED_WING,source,target,List.of(new Vec3(0,50,0),new Vec3(1000,50,0)),
                measured.referenceAboveSurface(),12,20);
        assertEquals(14.651264190673828,route.get(route.size()-2).y,1e-10);
        assertEquals(route.get(route.size()-2).y,route.getLast().y);
    }
}
