package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.ground.GroundRunwayReadiness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual AirportBE with the existing terrain/reservation fixture, not a mirrored readiness policy. */
class AirportGroundRunwayReadinessTest {
    @TempDir Path directory;
    AirportGroundOperationsTest f;
    @BeforeAll static void bootstrap(){AirportGroundOperationsTest.bootstrap();}
    @BeforeEach void setup()throws Exception{f=new AirportGroundOperationsTest();f.directory=directory;f.setup();}
    @AfterEach void cleanup(){f.clear();}
    private GroundAssignment prepare(){
        for(int i=0;i<200;i++,f.level.time+=5){var p=f.airport.preparedGroundDeparture(f.attempt,f.aircraft.proxy,0,0);if(p.isPresent())return p.get();}
        return fail("Original preparation must be genuinely VALID before handoff regression");
    }
    private GroundAssignment atRunway(){
        var assignment=prepare();assertTrue(f.airport.reserveGroundAssignment(f.attempt,assignment,f.aircraft.id,1000));
        f.aircraft.hull=f.aircraft.hull.move(30,0,0);f.level.time+=25;return assignment;
    }
    private GroundRunwayReadiness status(GroundAssignment assignment){return f.airport.groundRunwayReadiness(assignment,f.aircraft.proxy,0,-90);}
    @Test void anExpiredOrdinaryRunwayCacheIsWaitingEvenWhileTheMissionPreflightRemainsValid()throws Exception{
        var assignment=atRunway();long priorExpiry=f.airport.reservations().getFirst().expiresAtTick();
        assertTrue(f.airport.preparedGroundAssignment(f.attempt,f.aircraft.id,assignment).isPresent());
        assertEquals(GroundRunwayReadiness.WAITING,status(assignment),"Bounded scan exhaustion must not become hard alignment rejection");
        Object state=((Map<?,?>)get(f.airport,"validation")).get("main");assertNotNull(state);
        assertEquals(AirportValidation.Result.WAITING,get(state,"result"));
        assertEquals(priorExpiry,f.airport.reservations().getFirst().expiresAtTick(),"A partial scan may not renew an FC-ready permit");
    }
    @Test void repeatedWaitingProgressesTheSameScanWithin2048BudgetThenRenewsOnlyOnReady()throws Exception{
        var assignment=atRunway();assertEquals(GroundRunwayReadiness.WAITING,status(assignment));
        Object state=((Map<?,?>)get(f.airport,"validation")).get("main"),scan=get(state,"scan");
        GroundRunwayReadiness result=GroundRunwayReadiness.WAITING;int calls=0;
        while(result==GroundRunwayReadiness.WAITING&&calls++<100){
            f.level.time+=5;result=status(assignment);
            assertSame(state,((Map<?,?>)get(f.airport,"validation")).get("main"));assertSame(scan,get(state,"scan"));
            assertTrue(((AirportValidation.Scan)scan).stepsUsed()<=2048);
        }
        assertEquals(GroundRunwayReadiness.READY,result);assertTrue(calls>0);
        assertEquals(f.level.time+AirportReservationBook.MAX_TTL_TICKS,f.airport.reservations().getFirst().expiresAtTick());
        assertTrue(f.airport.groundRunwayReady(assignment,f.aircraft.proxy,0,-90),"Legacy true is retained only for actual READY");
    }
    @Test void missingSelectedGroundOrRunwayChunksWaitWithoutCreatingAReadyPermit(){
        var assignment=atRunway();long expiry=f.airport.reservations().getFirst().expiresAtTick();
        f.level.unloaded.add(new ChunkPos(-2,0));
        assertEquals(GroundRunwayReadiness.WAITING,status(assignment),"An unloaded selected stand/route is unknown, not obstructed");
        assertFalse(f.airport.groundRunwayReady(assignment,f.aircraft.proxy,0,-90));
        assertEquals(expiry,f.airport.reservations().getFirst().expiresAtTick());
        f.level.unloaded.clear();f.level.unloaded.add(new ChunkPos(4,0));
        assertEquals(GroundRunwayReadiness.WAITING,status(assignment),"The runway approach chunk is also an asynchronous wait");
    }
    @Test void anActualTerrainObstructionRemainsBlockedAfterTheRealScanFinishes()throws Exception{
        var assignment=atRunway();f.level.blocked.add(new BlockPos(10,66,0));
        GroundRunwayReadiness result=GroundRunwayReadiness.WAITING;
        for(int i=0;i<100&&result==GroundRunwayReadiness.WAITING;i++,f.level.time+=5)result=status(assignment);
        Object state=((Map<?,?>)get(f.airport,"validation")).get("main");assertNotNull(state);
        assertEquals(AirportValidation.Result.OBSTRUCTED,get(state,"result"),"A BLOCKED result cannot merely be the first unfinished poll");
        assertEquals(GroundRunwayReadiness.BLOCKED,result);assertFalse(f.airport.groundRunwayReady(assignment,f.aircraft.proxy,0,-90));
    }
    @Test void badYawSupportOrAssignmentDoNotMasqueradeAsPendingTerrain(){
        var assignment=atRunway();
        assertEquals(GroundRunwayReadiness.BLOCKED,f.airport.groundRunwayReadiness(assignment,f.aircraft.proxy,0,0));
        f.aircraft.grounded=false;assertEquals(GroundRunwayReadiness.BLOCKED,status(assignment));f.aircraft.grounded=true;
        assertEquals(GroundRunwayReadiness.BLOCKED,status(new GroundAssignment(3,"main","a","out","in")));
    }
    @Test void aForeignCurrentRunwayPermitIsNeverRenewedOrReplaced()throws Exception{
        var assignment=atRunway();UUID foreign=UUID.randomUUID();var book=new AirportReservationBook();assertTrue(book.reserve("main",foreign,f.level.time,1000));
        AirportGroundOperationsTest.set(AirportBlockEntity.class,f.airport,"reservations",book);
        assertEquals(GroundRunwayReadiness.BLOCKED,status(assignment));assertEquals(foreign,f.airport.reservations().getFirst().aircraftId());
    }
    private static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
}
