package com.monpai.sailboatmod.transport.board;

import static com.monpai.sailboatmod.transport.board.TownVoyageServiceTest.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TownVoyagePublisherTest {
    @Test void prestartEndpointObservationsReplayAfterRestoreWithoutHoldingLevelsOrEntities() {
        var pending=new TownVoyagePublisher.PendingEndpoints();Object server=new Object();
        var i=new TownVoyageService.Index(); i.restore(java.util.List.of(observation(key(1),State.MOVING,100,100,"g")),java.util.List.of(HOME,AWAY));
        pending.observe(server,new Endpoint(HOME.dimensionId(),HOME.terminalPos(),"renamed-town","Renamed"));
        pending.unloaded(server,HOME.dimensionId(),HOME.terminalPos());
        assertEquals(1,pending.size(server));pending.drain(server,i);
        assertEquals(0,pending.size(server));assertEquals("renamed-town",i.endpoint(HOME.dimensionId(),HOME.terminalPos(),"X").townId());
        assertTrue(rows(i,"town-a",100).isEmpty());assertEquals(1,rows(i,"renamed-town",120).size());
    }
    @Test void removalThenUnloadRemainsUnavailableUntilFreshObservation() {
        var pending=new TownVoyagePublisher.PendingEndpoints();Object server=new Object();var i=new TownVoyageService.Index();
        pending.observe(server,HOME);pending.removed(server,HOME.dimensionId(),HOME.terminalPos());pending.unloaded(server,HOME.dimensionId(),HOME.terminalPos());
        pending.drain(server,i);i.observeEndpoint(AWAY,true);i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());
        i.observeEndpoint(HOME,true);i.publish(observation(key(1),State.MOVING,120,120,"g"),120);
        assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isPresent());
    }
    @Test void startupBufferOverflowCannotLoseRemovalAndRetainRestoredEndpointAvailability() {
        var pending=new TownVoyagePublisher.PendingEndpoints();Object server=new Object();
        var i=new TownVoyageService.Index();
        i.restore(java.util.List.of(observation(key(1),State.MOVING,100,100,"g")),java.util.List.of(HOME,AWAY));
        for(int n=10000;n<18192;n++)pending.observe(server,new Endpoint("minecraft:overworld",n,"town-c","Other"));
        pending.removed(server,HOME.dimensionId(),HOME.terminalPos());assertEquals(8192,pending.size(server));
        long priorGeneration=i.generation(key(1));pending.drain(server,i);
        assertNotEquals(priorGeneration,i.generation(key(1)));i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());
        assertTrue(i.endpoints().size()<=8192);assertEquals(0,pending.size(server));
        i.observeEndpoint(HOME,true);i.observeEndpoint(AWAY,true);i.publish(observation(key(1),State.MOVING,120,120,"g"),120);
        assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isPresent());

        Object replayServer=new Object();var replay=new TownVoyageService.Index();
        replay.restore(java.util.List.of(observation(key(1),State.MOVING,100,100,"g")),java.util.List.of(HOME,AWAY));
        pending.observe(replayServer,HOME);pending.observe(replayServer,AWAY);
        for(int n=10000;n<18190;n++)pending.observe(replayServer,new Endpoint("minecraft:overworld",n,"town-c","Other"));
        pending.removed(replayServer,"minecraft:overworld",99999);pending.drain(replayServer,replay);
        replay.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(replay,"town-a",100).getFirst().etaTicks().isPresent());

        Object stoppedServer=new Object(),otherServer=new Object();
        for(int n=10000;n<18192;n++)pending.observe(stoppedServer,new Endpoint("minecraft:overworld",n,"town-c","Other"));
        pending.removed(stoppedServer,HOME.dimensionId(),HOME.terminalPos());pending.discard(stoppedServer);
        var other=catalogIndex();pending.observe(otherServer,HOME);pending.drain(otherServer,other);
        other.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(other,"town-a",100).getFirst().etaTicks().isPresent());
        pending.open(stoppedServer);var restarted=catalogIndex();pending.observe(stoppedServer,HOME);pending.drain(stoppedServer,restarted);
        restarted.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(restarted,"town-a",100).getFirst().etaTicks().isPresent());
    }
    @Test void removeOnlyStartupEventRetainsLastKnownTownWhileRealObservedUnbindingRemainsAuthoritative() {
        var pending=new TownVoyagePublisher.PendingEndpoints();Object server=new Object();var i=new TownVoyageService.Index();
        i.restore(java.util.List.of(observation(key(1),State.MOVING,100,100,"g")),java.util.List.of(HOME,AWAY));
        pending.removed(server,HOME.dimensionId(),HOME.terminalPos());pending.unloaded(server,HOME.dimensionId(),HOME.terminalPos());
        pending.drain(server,i);i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        var retained=rows(i,"town-a",100);assertEquals(1,retained.size());assertEquals("Home",retained.getFirst().sourceName());
        assertTrue(retained.getFirst().etaTicks().isEmpty());
        i.observeEndpoint(HOME,true);i.publish(observation(key(1),State.MOVING,120,120,"g"),120);
        assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isPresent());

        Object reboundServer=new Object();var rebound=new TownVoyageService.Index();
        rebound.restore(java.util.List.of(observation(key(1),State.MOVING,100,100,"g")),java.util.List.of(HOME,AWAY));
        pending.observe(reboundServer,new Endpoint(HOME.dimensionId(),HOME.terminalPos(),"","Unbound"));
        pending.removed(reboundServer,HOME.dimensionId(),HOME.terminalPos());pending.drain(reboundServer,rebound);
        rebound.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(rebound,"town-a",100).isEmpty());assertTrue(rows(rebound,"town-b",120).getFirst().etaTicks().isEmpty());
    }
    @Test void sessionsAreIsolatedBoundedAndDiscardedOnStop() {
        var pending=new TownVoyagePublisher.PendingEndpoints();Object a=new Object(),b=new Object();var i=new TownVoyageService.Index();
        for(int n=1;n<=8200;n++)pending.observe(a,new Endpoint("minecraft:overworld",n,"town-a","E"));
        pending.observe(b,new Endpoint("minecraft:overworld",1,"town-b","Other World"));
        assertEquals(8192,pending.size(a));pending.drain(b,i);
        assertEquals("town-b",i.endpoint("minecraft:overworld",1,"X").townId());assertEquals(8192,pending.size(a));
        pending.discard(a);pending.drain(a,i);assertEquals(0,pending.size(a));assertEquals(1,i.endpoints().size());
    }
}
