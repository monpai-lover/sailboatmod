package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.airport.AirportGroundPreparation.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AirportGroundPreparationProviderTest {
    @TempDir Path directory;
    AirportGroundPreparationIntegrationTest fixture;
    @BeforeAll static void bootstrap(){AirportGroundPreparationIntegrationTest.bootstrap();}
    @BeforeEach void setup()throws Exception{fixture=new AirportGroundPreparationIntegrationTest();fixture.directory=directory;fixture.setup();}
    @AfterEach void cleanup(){fixture.cleanup();}
    @Test void bothAirportsAdvanceTheirOwnWaitingScansWithoutChangingThePinnedPair()throws Exception{
        var target=secondAirport();var cursor=new AirportGroundPreparation();int[] calls={0,0};Object[] scans={null,null};int[] waits={0,0};Outcome result=null;
        for(int i=0;i<400;i++,fixture.level.time+=5){
            result=cursor.advance(fixture.level.time,List.of(fixture.good),List.of(fixture.arrivalGood),fixture.compatible,
                    candidate->{calls[0]++;var check=fixture.airport.prepareGroundCandidate(fixture.attempt,fixture.aircraft,candidate.assignment());observe(fixture.airport,check,0,scans,waits);return check;},
                    candidate->{calls[1]++;var check=target.prepareGroundCandidate(fixture.attempt,fixture.aircraft,candidate.assignment());observe(target,check,1,scans,waits);return check;});
            assertEquals(calls[0],calls[1],"source WAIT must not serialize the destination's independent per-airport budget");
            assertEquals(fixture.good.assignment(),result.leg().source().assignment());
            assertEquals(fixture.arrivalGood.assignment(),result.leg().destination().assignment());
            if(result.status()==Status.READY)break;
            assertEquals(Status.WAITING,result.status());
        }
        assertEquals(Status.READY,result.status());assertTrue(waits[0]>1&&waits[1]>1);assertNotSame(scans[0],scans[1]);
    }
    @Test void exceptionRecheckingPinnedGeometryFailsClosedWithoutCallingProviders(){
        var cursor=new AirportGroundPreparation();int[] calls={0};
        var provider=(AirportGroundPreparation.Provider)c->{calls[0]++;return new Check(Status.WAITING,"waiting");};
        assertEquals(Status.WAITING,cursor.advance(100,List.of(fixture.good),List.of(fixture.arrivalGood),fixture.compatible,provider,provider).status());
        int before=calls[0];var throwing=new AirportGroundSelection.LegGate(){
            public boolean taxi(AirportGroundSelection.Candidate a,AirportGroundSelection.Candidate b){throw new IllegalStateException("optional mod state vanished");}
            public boolean flight(AirportFacility a,AirportFacility b){return true;}
        };
        var result=assertDoesNotThrow(()->cursor.advance(105,List.of(fixture.good),List.of(fixture.arrivalGood),throwing,provider,provider));
        assertEquals(Status.BLOCKED,result.status());assertNull(result.leg());assertEquals(before,calls[0]);
    }
    @Test void providerExceptionFailsClosedAndNeverReportsReady(){
        var cursor=new AirportGroundPreparation();
        var result=assertDoesNotThrow(()->cursor.advance(100,List.of(fixture.good),List.of(fixture.arrivalGood),fixture.compatible,
                c->{throw new IllegalStateException("optional terrain observation failed");},c->new Check(Status.READY,"")));
        assertEquals(Status.BLOCKED,result.status());assertNull(result.leg());
    }
    @Test void nullProviderResultFailsClosed(){
        var cursor=new AirportGroundPreparation();int[] targetCalls={0};
        var result=cursor.advance(100,List.of(fixture.good),List.of(fixture.arrivalGood),fixture.compatible,c->null,c->{targetCalls[0]++;return new Check(Status.READY,"");});
        assertEquals(Status.BLOCKED,result.status());assertNull(result.leg());assertEquals(0,targetCalls[0]);
    }
    private void observe(AirportBlockEntity airport,Check check,int index,Object[] scans,int[] waits){
        if(check.status()!=Status.WAITING)return;
        try{Field field=AirportBlockEntity.class.getDeclaredField("groundPreflights");field.setAccessible(true);Object scan=((Map<?,?>)field.get(airport)).get(fixture.attempt);
            if(scan!=null){if(scans[index]==null)scans[index]=scan;else assertSame(scans[index],scan,"each WAITING airport must retain its actual Scan");waits[index]++;}
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private AirportBlockEntity secondAirport()throws Exception{
        var target=AirportGroundPreparationIntegrationTest.allocate(AirportBlockEntity.class);
        AirportGroundPreparationIntegrationTest.set(BlockEntity.class,target,"worldPosition",new BlockPos(0,64,16));target.setLevel(fixture.level);
        AirportGroundPreparationIntegrationTest.set(BlockEntity.class,target,"blockState",Blocks.AIR.defaultBlockState());
        for(Field field:AirportBlockEntity.class.getDeclaredFields())if(Map.class.isAssignableFrom(field.getType())){field.setAccessible(true);field.set(target,new LinkedHashMap<>());}
        var facilities=new LinkedHashMap<String,AirportFacility>();for(var f:fixture.airport.facilities())facilities.put(f.id(),f);
        AirportGroundPreparationIntegrationTest.set(AirportBlockEntity.class,target,"facilities",facilities);
        AirportGroundPreparationIntegrationTest.set(AirportBlockEntity.class,target,"groundLayout",fixture.layout);
        AirportGroundPreparationIntegrationTest.set(AirportBlockEntity.class,target,"reservations",new AirportReservationBook());
        AirportGroundPreparationIntegrationTest.set(AirportBlockEntity.class,target,"budgetTick",Long.MIN_VALUE);return target;
    }
}
