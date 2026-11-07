package com.monpai.sailboatmod.transport.board;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TownVoyageAirJobsTest {
    static RailAircraftPath path() { return RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(0,80,0),new Vec3(0,80,100)),10,0,true)),0); }
    static Observation observation(Key key, State state, long tick, long etaTick, String geometry) {
        var o=TownVoyageServiceTest.observation(key,state,tick,etaTick,geometry);
        return new Observation(o.key(),com.monpai.sailboatmod.transport.LogisticsCarrier.Kind.AIR,o.carrierName(),o.routeName(),o.source(),o.target(),o.phase(),o.state(),o.loaded(),o.observedTick(),o.heartbeatTicks(),o.etaTicks(),o.etaObservedTick(),o.geometryId());
    }
    static final class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>(); boolean stopped;
        public void execute(Runnable r) { if(stopped) throw new RejectedExecutionException(); tasks.add(r); }
        void runOne() { tasks.removeFirst().run(); }
        public void shutdown() { stopped=true; }
        public List<Runnable> shutdownNow() { stopped=true; var remaining=List.copyOf(tasks); tasks.clear(); return remaining; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped; }
        public boolean awaitTermination(long timeout,TimeUnit unit) { return stopped; }
    }
    @Test void mainThreadDrainAppliesStampedEstimateWithoutInlineCalculationAndRateLimitsPerCarrier() {
        var i=catalogIndex(); var p=path(); var k=key(1); String g=p.fingerprint();
        i.publish(observation(k,State.MOVING,100,-1,g),100);
        var executor=new ManualExecutor(); var calls=new AtomicInteger();
        var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->{calls.incrementAndGet();return OptionalLong.of(321);});
        jobs.request(k,g,p,0,0,100); jobs.request(k,g,p,0,0,100);
        assertEquals(0,calls.get()); assertEquals(1,jobs.inFlight()); assertEquals(1,executor.tasks.size());
        executor.runOne(); assertEquals(1,calls.get()); assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());
        jobs.drain(101); assertEquals(0,jobs.inFlight());
        assertEquals(301,rows(i,"town-a",120).getFirst().etaTicks().orElseThrow());
        assertEquals(100,i.observations().getFirst().etaObservedTick());
        jobs.request(k,g,p,5,0,139); assertTrue(executor.tasks.isEmpty());
        jobs.request(k,g,p,5,0,140); assertEquals(1,executor.tasks.size()); jobs.close();
    }
    @Test void exactSingleDaemonWorkerQueueEightInflightNineNeverRunsOnCaller() throws Exception {
        var executor=TownVoyageService.AirJobs.executor(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var thread=new AtomicReference<Thread>(); var calls=new AtomicInteger();
        var i=catalogIndex(); var p=path(); String g=p.fingerprint();
        var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->{
            calls.incrementAndGet();thread.set(Thread.currentThread());entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return OptionalLong.of(50);
        });
        try {
            for(int n=1;n<=10;n++) { i.publish(observation(key(n),State.MOVING,100,n==10?100:-1,g),100); jobs.request(key(n),g,p,0,0,100); if(n==1) assertTrue(entered.await(3,TimeUnit.SECONDS)); }
            assertEquals(1,executor.getCorePoolSize()); assertEquals(1,executor.getMaximumPoolSize());
            assertEquals(8,executor.getQueue().size()); assertEquals(9,jobs.inFlight()); assertEquals(1,calls.get());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,executor.getRejectedExecutionHandler());
            assertNotSame(Thread.currentThread(),thread.get()); assertTrue(thread.get().isDaemon());
            assertTrue(rows(i,"town-a",100).stream().allMatch(r->r.etaTicks().isEmpty()));
        } finally { release.countDown(); jobs.close(); }
    }
    @Test void rejectsCompletionAfterNewLegGeometryPauseResumeUnloadAndEndpointRemoval() {
        for(String mutation:List.of("leg","geometry","pause-resume","unload","endpoint")) {
            var i=catalogIndex(); var p=path(); var k=key(1); String g=p.fingerprint();
            i.publish(observation(k,State.MOVING,100,-1,g),100);
            var executor=new ManualExecutor(); var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->OptionalLong.of(88));
            jobs.request(k,g,p,0,0,100);
            switch(mutation) {
                case "leg" -> i.publish(observation(new Key(k.carrier(),UUID.randomUUID()),State.MOVING,110,-1,g),110);
                case "geometry" -> i.publish(observation(k,State.MOVING,110,-1,"other"),110);
                case "pause-resume" -> { i.publish(observation(k,State.PAUSED,110,-1,g),110); i.publish(observation(k,State.MOVING,111,-1,g),111); }
                case "unload" -> i.markUnloaded(k.carrier());
                case "endpoint" -> i.markEndpointRemoved(HOME.dimensionId(),HOME.terminalPos());
            }
            executor.runOne(); jobs.drain(120);
            assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isEmpty(),mutation);
            jobs.close();
        }
    }
    @Test void refreshedProjectionExpiresCarriedAirEtaWhileHeartbeatIsStillFresh() {
        var i=catalogIndex();var k=key(1);String g=path().fingerprint();
        i.publish(observation(k,State.MOVING,100,100,g),100);
        i.publish(observation(k,State.MOVING,140,-1,g),140);
        i.publish(observation(k,State.MOVING,160,-1,g),160);
        assertTrue(rows(i,"town-a",200).getFirst().etaTicks().isPresent());
        var expired=rows(i,"town-a",220).getFirst();assertTrue(expired.etaTicks().isEmpty());assertEquals(State.ETA_UNKNOWN,expired.state());
        assertTrue(i.observations().getFirst().etaTicks().isEmpty());
    }
    @Test void cachedProjectionExpiresAirEtaOnUseAndHundredBoardsReuseScrubbedRows() {
        var i=catalogIndex();var k=key(1);String g=path().fingerprint();
        i.publish(observation(k,State.MOVING,100,100,g),100);
        i.publish(observation(k,State.MOVING,140,-1,g),140);
        i.publish(observation(k,State.MOVING,160,-1,g),160);
        var fresh=observation(key(2),State.MOVING,160,160,g);
        i.publish(new Observation(fresh.key(),fresh.kind(),fresh.carrierName(),fresh.routeName(),fresh.source(),fresh.target(),fresh.phase(),fresh.state(),fresh.loaded(),fresh.observedTick(),fresh.heartbeatTicks(),OptionalLong.of(1000),160,g),160);
        assertEquals(400,rows(i,"town-a",200).getFirst().etaTicks().orElseThrow());
        var expired=i.view("town-a",Filter.ALL,0,201);
        assertEquals(959,expired.rows().getFirst().etaTicks().orElseThrow(),
                "Scrubbing an expired flight must not rebase another flight's estimate");
        assertTrue(expired.rows().getLast().etaTicks().isEmpty());
        assertEquals(State.ETA_UNKNOWN,expired.rows().getLast().state());
        assertEquals(expired.rows(),i.view("town-a",Filter.AIR,0,201).rows());
        for(int n=0;n<100;n++)assertSame(expired,i.view("town-a",Filter.ALL,0,201));
        assertEquals(1,i.aggregationCount());
    }
    @Test void ageOneHundredAcceptedButOlderAndStaleObservationsDiscarded() {
        for(long age:List.of(100L,101L)) {
            var i=catalogIndex(); var p=path(); var k=key(1);String g=p.fingerprint();
            i.publish(observation(k,State.MOVING,100,-1,g),100);
            var executor=new ManualExecutor();var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->OptionalLong.of(180));
            jobs.request(k,g,p,0,0,100); executor.runOne();
            i.publish(observation(k,State.MOVING,140,-1,g),140);i.publish(observation(k,State.MOVING,190,-1,g),190);jobs.drain(100+age);
            assertEquals(age==100,rows(i,"town-a",100+age).getFirst().etaTicks().isPresent());jobs.close();
        }
    }
    @Test void oldSessionCannotAffectNewIndexAndStopRejectsQueuedOrLateWork() {
        var p=path();var k=key(1);String g=p.fingerprint(); var old=catalogIndex();old.publish(observation(k,State.MOVING,100,-1,g),100);
        var executor=new ManualExecutor();var jobs=new TownVoyageService.AirJobs(old,executor,(path,d,s)->OptionalLong.of(99));
        jobs.request(k,g,p,0,0,100);Runnable late=executor.tasks.removeFirst();jobs.close();late.run();jobs.drain(101);
        assertEquals(0,jobs.inFlight()); assertTrue(rows(old,"town-a",101).getFirst().etaTicks().isEmpty());
        var fresh=catalogIndex();fresh.restore(List.of(observation(k,State.MOVING,100,100,g)),List.of());
        assertTrue(rows(fresh,"town-a",101).getFirst().etaTicks().isEmpty());
        jobs.request(k,g,p,0,0,140);assertTrue(executor.tasks.isEmpty()); assertTrue(executor.stopped);
    }
    @Test void frozenWorldRejectsCompletedWorkerAndSameTickCacheUntilFreshHeartbeat() {
        var i=catalogIndex();var p=path();var k=key(1);String g=p.fingerprint();
        i.publish(observation(k,State.MOVING,100,-1,g),100);var executor=new ManualExecutor();
        var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->OptionalLong.of(90));
        jobs.request(k,g,p,0,0,100);executor.runOne();
        i.clockSuspended(true,100);jobs.drain(100);i.clockSuspended(false,100);
        assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());
        assertEquals(0,jobs.cacheSize());jobs.close();
    }
    @Test void explicitUnknownProducerStateDropsIncomingAndCarriedEtaAndRejectsOldWorker() {
        for(State unknown:List.of(State.ESTIMATING,State.ETA_UNKNOWN)) {
            var i=catalogIndex();var p=path();var k=key(1);String g=p.fingerprint();
            i.publish(observation(k,State.MOVING,100,100,g),100);
            i.publish(observation(k,unknown,120,120,g),120);
            assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isEmpty(),"incoming " + unknown);
            i.publish(observation(k,State.MOVING,140,140,g),140);
            i.publish(observation(k,unknown,160,-1,g),160);
            assertTrue(rows(i,"town-a",160).getFirst().etaTicks().isEmpty(),"carried " + unknown);
            var executor=new ManualExecutor();var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->OptionalLong.of(90));
            i.publish(observation(k,State.MOVING,180,-1,g),180);jobs.request(k,g,p,0,0,180);
            i.publish(observation(k,unknown,200,-1,g),200);executor.runOne();jobs.drain(201);
            assertTrue(rows(i,"town-a",220).getFirst().etaTicks().isEmpty(),"late " + unknown);
            i.publish(observation(k,unknown,240,-1,g),240);jobs.request(k,g,p,0,0,240);executor.runOne();jobs.drain(241);
            assertTrue(rows(i,"town-a",260).getFirst().etaTicks().isPresent(),"new job " + unknown);jobs.close();
        }
    }
    @Test void invalidConfirmedAirProgressClearsPriorEtaInsteadOfReusingIt() {
        var i=catalogIndex();var p=path();var k=key(1);String g=p.fingerprint();
        i.publish(observation(k,State.MOVING,100,100,g),100);
        var executor=new ManualExecutor();var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->OptionalLong.of(90));
        jobs.request(k,g,p,Double.NaN,0,100);assertTrue(executor.tasks.isEmpty());
        assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());jobs.close();
    }
    @Test void groundCarriersNeverEnterAirWorkerEvenWhenCallerSuppliesACompiledPath() {
        var i=catalogIndex();var p=path();var k=key(1);String g=p.fingerprint();
        i.publish(TownVoyageServiceTest.observation(k,State.MOVING,100,-1,g),100);
        var executor=new ManualExecutor();var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->OptionalLong.of(90));
        jobs.request(k,g,p,0,0,100);assertTrue(executor.tasks.isEmpty());jobs.close();
    }
    @Test void invalidGeometryStateAndEstimatorFailureStayUnknownAndCacheIsBounded() {
        var p=path();String g=p.fingerprint();var i=catalogIndex();var executor=new ManualExecutor();
        var jobs=new TownVoyageService.AirJobs(i,executor,(path,d,s)->{ if(d==1)throw new IllegalArgumentException("defer");return OptionalLong.of(90); });
        var k=key(1);i.publish(observation(k,State.PAUSED,100,-1,g),100);jobs.request(k,g,p,0,0,100);assertTrue(executor.tasks.isEmpty());
        i.publish(observation(k,State.MOVING,120,-1,g),120);jobs.request(k,"old",p,0,0,120);assertTrue(executor.tasks.isEmpty());
        jobs.request(k,g,p,1,0,120);executor.runOne();jobs.drain(121);assertTrue(rows(i,"town-a",121).getFirst().etaTicks().isEmpty());
        for(int n=2;n<=260;n++) {i.publish(observation(key(n),State.MOVING,140,-1,g),140);jobs.request(key(n),g,p,0,0,140);executor.runOne();jobs.drain(141);}
        assertEquals(256,jobs.cacheSize());jobs.close();
    }
}
