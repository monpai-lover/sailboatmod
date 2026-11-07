package com.monpai.sailboatmod.transport.physical;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

class AirportLoadingWindowTest {
    private static final UUID OWNER=UUID.randomUUID(),BODY=UUID.randomUUID();
    private static final AABB HULL=new AABB(-4,63,-4,4,70,4);
    private static final Vec3 NEXT=new Vec3(100,64,0);
    private static boolean pins(TransportLoadingWindow w,Collection<ChunkPos> chunks)throws Exception {
        try{return (boolean)TransportLoadingWindow.class.getMethod("setAirportPins",Collection.class).invoke(w,chunks);}
        catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    private static void clear(TransportLoadingWindow w)throws Exception {TransportLoadingWindow.class.getMethod("clearAirportPins").invoke(w);}
    @Test void airportLeaseHasItsOwnCapAndSurvivesTheMovingWindow()throws Exception {
        var b=new Backend();b.loaded=true;var w=new TransportLoadingWindow(b);
        Set<ChunkPos> airport=new LinkedHashSet<>();for(int i=0;i<4096;i++)airport.add(new ChunkPos(1000+i,1000));
        assertTrue(pins(w,airport));assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
        assertTrue(b.active().containsAll(airport));assertTrue(b.active().size()<=4224);
        b.tick++;assertTrue(w.update(null,OWNER,BODY,HULL.move(1024,0,0),new Vec3(1200,64,0)));
        assertTrue(b.active().containsAll(airport));w.close();assertTrue(b.active().isEmpty());
    }
    @Test void clearingAirportLeaseKeepsOverlappingMovingChunksAndBody()throws Exception {
        var b=new Backend();b.loaded=true;var w=new TransportLoadingWindow(b);
        ChunkPos overlap=new ChunkPos(0,0),remote=new ChunkPos(2000,2000);
        assertTrue(pins(w,List.of(overlap,remote)));assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
        clear(w);assertTrue(b.retained.contains(overlap));assertFalse(b.active().contains(remote));assertTrue(b.body);
        assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));assertFalse(b.active().contains(remote));
    }
    @Test void oversizeAirportReplacementReleasesOnlyItsOldLeaseAndStaysBlocked()throws Exception {
        var b=new Backend();b.loaded=true;var w=new TransportLoadingWindow(b);ChunkPos remote=new ChunkPos(2000,2000);
        assertTrue(pins(w,List.of(remote)));assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
        List<ChunkPos> huge=new ArrayList<>();for(int i=0;i<4097;i++)huge.add(new ChunkPos(3000+i,3000));
        assertFalse(pins(w,huge));assertFalse(b.active().contains(remote));assertTrue(b.retained.contains(new ChunkPos(0,0)));assertTrue(b.body);
        assertFalse(w.update(null,OWNER,BODY,HULL,NEXT));assertEquals("airport_loading_too_large",w.reason());
        clear(w);assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
    }
    @Test void coldAirportsShareOneFourPerTickAndSixteenInFlightBudget()throws Exception {
        var b=new Backend();var w=new TransportLoadingWindow(b);List<ChunkPos> airports=new ArrayList<>();
        for(int i=0;i<200;i++)airports.add(new ChunkPos(2000+i,2000));assertTrue(pins(w,airports));
        for(int t=0;t<8;t++){w.update(null,OWNER,BODY,HULL,NEXT);w.update(null,OWNER,BODY,HULL,NEXT);assertTrue(b.requests<=Math.min(16,4*(t+1)));b.tick++;}
        assertEquals(16,b.requests);assertFalse(w.update(null,OWNER,BODY,HULL,NEXT));
        b.loaded=true;assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));assertTrue(b.active().containsAll(airports));
    }
    @Test void clearedSharedFutureCannotResurrectAnAirportTicket()throws Exception {
        var b=new Backend();var w=new TransportLoadingWindow(b);ChunkPos remote=new ChunkPos(2000,2000);
        assertTrue(pins(w,List.of(remote)));w.update(null,OWNER,BODY,HULL,NEXT);
        // Make the local moving window visible so subsequent requests reach the remote airport.
        b.localLoaded=true;b.tick++;w.update(null,OWNER,BODY,HULL,NEXT);
        CompletableFuture<Boolean> future=b.pending.get(remote);assertNotNull(future);
        clear(w);assertFalse(future.isCancelled());future.complete(true);b.loaded=true;b.tick++;
        assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));assertFalse(b.active().contains(remote));
    }
    @Test void registrationAndBodyReplacementCannotInheritAirportPins()throws Exception {
        for(boolean newBody:new boolean[]{false,true}){
            var b=new Backend();b.loaded=true;var w=new TransportLoadingWindow(b);ChunkPos remote=new ChunkPos(2000,2000);
            assertTrue(pins(w,List.of(remote)));assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
            assertTrue(w.update(null,newBody?OWNER:UUID.randomUUID(),newBody?UUID.randomUUID():BODY,HULL,NEXT));
            assertFalse(b.active().contains(remote));
        }
    }
    @Test void airportTicketsAreEphemeralAndOverlapNeverBecomesPersistentAirportTerrain()throws Exception {
        var b=new Backend();b.loaded=true;var w=new TransportLoadingWindow(b);ChunkPos overlap=new ChunkPos(0,0),remote=new ChunkPos(2000,2000);
        assertTrue(pins(w,List.of(overlap,remote)));assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
        assertTrue(b.airport.containsAll(List.of(overlap,remote)));assertFalse(b.retained.contains(remote));
        // Simulate the persisted moving tickets surviving a process crash; region tickets do not.
        var restarted=new Backend();restarted.retained.addAll(b.retained);
        assertFalse(restarted.active().contains(remote));assertTrue(restarted.retained.contains(overlap));
        clear(w);assertFalse(b.airport.contains(overlap));assertTrue(b.retained.contains(overlap));
        assertTrue(pins(w,List.of(remote)));assertTrue(w.update(null,OWNER,BODY,HULL,NEXT));
        w.update(null,UUID.randomUUID(),BODY,HULL,NEXT);assertTrue(b.airport.isEmpty());
    }
    private static final class Backend implements TransportLoadingWindow.Backend {
        long tick;boolean loaded,localLoaded,body;int requests;
        Set<ChunkPos> retained=new HashSet<>(),airport=new HashSet<>();Map<ChunkPos,CompletableFuture<Boolean>> pending=new HashMap<>();
        Set<ChunkPos> active(){Set<ChunkPos> all=new HashSet<>(retained);all.addAll(airport);return all;}
        public long tick(){return tick;}public boolean isLoaded(ChunkPos p){return loaded||localLoaded&&p.x<1000;}
        public CompletableFuture<Boolean> request(ChunkPos p){requests++;var f=new CompletableFuture<Boolean>();pending.put(p,f);return f;}
        public void ticket(UUID o,ChunkPos p,boolean add){if(add)retained.add(p);else retained.remove(p);}
        public void airportTicket(UUID o,ChunkPos p,boolean add){if(add)airport.add(p);else airport.remove(p);}
        public boolean structure(UUID p,UUID o,boolean retain){body=retain;return true;}
    }
}
