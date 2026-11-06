package com.monpai.sailboatmod.transport.board;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.LogisticsCarrier.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;

class TownVoyageServiceTest {
    static final Endpoint HOME = new Endpoint("minecraft:overworld", 1, "town-a", "Home");
    static final Endpoint AWAY = new Endpoint("minecraft:overworld", 2, "town-b", "Away");
    static TownVoyageService.Index catalogIndex() {
        var index=new TownVoyageService.Index();
        index.observeEndpoint(HOME,true);index.observeEndpoint(AWAY,true);return index;
    }
    static Key key(int n) { return new Key(new CarrierKey("entity", "minecraft:overworld", new UUID(0,n)), new UUID(1,n)); }
    static Observation observation(Key key, State state, long tick, long etaTick, String geometry) {
        return new Observation(key, Kind.WATER, "Carrier", "Route", HOME, AWAY, "SAILING", state, true, tick, 20,
                etaTick < 0 ? OptionalLong.empty() : OptionalLong.of(500), etaTick, geometry);
    }
    static List<PublicRow> rows(TownVoyageService.Index i, String town, long now) {
        i.view(town, Filter.ALL, 0, now); i.refresh(now); return i.view(town, Filter.ALL, 0, now).rows();
    }
    @Test void deduplicatesCurrentCarrierAndReturnLegReplacesWithoutLateRetire() {
        var i = catalogIndex(); Key k=key(1);
        i.publish(observation(k,State.MOVING,100,100,"g"),100);
        i.publish(observation(k,State.MOVING,101,101,"g"),101);
        assertEquals(1,rows(i,"town-a",101).size());
        Key returned=new Key(k.carrier(),UUID.randomUUID());
        i.publish(observation(returned,State.MOVING,120,120,"return"),120);
        i.retire(k.carrier(),k.legId());
        assertEquals(returned,i.currentKey(k.carrier()).orElseThrow());
        assertEquals(1,rows(i,"town-a",140).size());
        i.retire(k.carrier(),returned.legId());
        assertTrue(i.currentKey(k.carrier()).isEmpty());
        assertTrue(rows(i,"town-a",160).isEmpty());
    }
    @Test void exactTownOrIndexesBothEndsAndLocalOnceWithoutCountryOrVisibility() {
        var i=catalogIndex();
        i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertEquals(Relation.OUTBOUND,rows(i,"town-a",100).getFirst().relation());
        assertEquals(Relation.INBOUND,rows(i,"town-b",120).getFirst().relation());
        assertTrue(rows(i,"same-country-other-town",140).isEmpty());
        var o=observation(key(2),State.MOVING,140,140,"g");
        i.publish(new Observation(o.key(),o.kind(),o.carrierName(),o.routeName(),HOME,HOME,o.phase(),o.state(),true,140,20,o.etaTicks(),140,"g"),140);
        assertEquals(2,rows(i,"town-a",160).size());
        assertEquals(1,rows(i,"town-a",160).stream().filter(r->r.relation()==Relation.LOCAL).count());
    }
    @Test void blockedStaleUnloadedAndRestoredNeverExposeEtaAndResumeNeedsNewEvidence() {
        for(State blocked:List.of(State.PAUSED,State.FUEL_BLOCKED,State.WAITING_TERRAIN,State.WAITING_RESOURCE,State.RECOVERING,State.UNLOADED,State.STALE)) {
            var i=catalogIndex(); Key k=key(1);
            i.publish(observation(k,State.MOVING,100,100,"g"),100);
            assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isPresent());
            i.publish(observation(k,blocked,120,100,"g"),120);
            assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isEmpty(),blocked.name());
            i.publish(observation(k,State.MOVING,140,100,"g"),140);
            assertTrue(rows(i,"town-a",140).getFirst().etaTicks().isEmpty(),"resume " + blocked);
            i.publish(observation(k,State.MOVING,160,160,"g"),160);
            assertTrue(rows(i,"town-a",160).getFirst().etaTicks().isPresent(),"fresh " + blocked);
        }
        var i=catalogIndex(); var o=observation(key(1),State.MOVING,100,100,"g");
        i.publish(o,100); assertEquals(State.STALE,rows(i,"town-a",161).getFirst().state());
        i.markUnloaded(o.key().carrier()); assertEquals(State.UNLOADED,rows(i,"town-a",181).getFirst().state());
        i.restore(List.of(o),List.of(HOME,AWAY));
        assertEquals(State.UNLOADED,rows(i,"town-a",201).getFirst().state());
        assertTrue(rows(i,"town-a",201).getFirst().etaTicks().isEmpty());
    }
    @Test void heartbeatIntervalControlsStaleBoundaryAndFutureEvidenceFailsClosed() {
        var i=catalogIndex(); var o=observation(key(1),State.MOVING,100,100,"g");
        i.publish(new Observation(o.key(),o.kind(),o.carrierName(),o.routeName(),HOME,AWAY,o.phase(),o.state(),true,100,40,o.etaTicks(),100,"g"),100);
        assertTrue(rows(i,"town-a",220).getFirst().etaTicks().isPresent());
        assertEquals(State.STALE,rows(i,"town-a",240).getFirst().state());
        i.publish(observation(key(2),State.MOVING,300,300,"g"),240);
        assertTrue(rows(i,"town-a",260).stream().noneMatch(r->r.etaTicks().isPresent()));
    }
    @Test void endpointUnloadRetainsEtaRemovalClearsAndRebindReindexesExactDimensionPosition() {
        var i=catalogIndex(); i.observeEndpoint(HOME,true); i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isPresent());
        i.markEndpointUnloaded(HOME.dimensionId(),HOME.terminalPos());
        assertEquals(HOME,i.endpoint(HOME.dimensionId(),HOME.terminalPos(),"Fallback"));
        assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isPresent());
        i.markEndpointRemoved(HOME.dimensionId(),HOME.terminalPos());
        i.markEndpointUnloaded(HOME.dimensionId(),HOME.terminalPos());
        assertTrue(rows(i,"town-a",140).getFirst().etaTicks().isEmpty());
        i.observeEndpoint(new Endpoint(HOME.dimensionId(),HOME.terminalPos(),"town-c","Renamed"),true);
        assertTrue(rows(i,"town-a",160).isEmpty());
        assertEquals("Renamed",rows(i,"town-c",180).getFirst().sourceName());
        assertTrue(i.endpoint("minecraft:the_nether",1,"Fallback").townId().isEmpty());
    }
    @Test void unknownSourceKnownTargetAppearsAndEndpointMetadataCorrectsFutureHeartbeats() {
        var i=catalogIndex(); var o=observation(key(1),State.MOVING,100,100,"g");
        i.publish(new Observation(o.key(),o.kind(),o.carrierName(),o.routeName(),Endpoint.unknown(),AWAY,o.phase(),o.state(),true,100,20,o.etaTicks(),100,"g"),100);
        assertEquals(Relation.INBOUND,rows(i,"town-b",100).getFirst().relation());
        assertTrue(rows(i,"town-b",100).getFirst().etaTicks().isPresent());
        i.observeEndpoint(new Endpoint(AWAY.dimensionId(),AWAY.terminalPos(),"town-c","NewName"),true);
        i.publish(new Observation(o.key(),o.kind(),o.carrierName(),o.routeName(),Endpoint.unknown(),AWAY,o.phase(),o.state(),true,120,20,o.etaTicks(),120,"g"),120);
        assertTrue(rows(i,"town-b",120).isEmpty());
        assertEquals("NewName",rows(i,"town-c",140).getFirst().targetName());
    }
    @Test void sortedPagesAreBoundedWithVisibleOverflowAndKnownEtasBeforeOpaqueIdentity() {
        var i=catalogIndex();
        for(int n=1;n<=70;n++) i.publish(observation(key(n),n%2==0?State.MOVING:State.PAUSED,100,n%2==0?100:-1,"g"),100);
        rows(i,"town-a",100); BoardView first=i.view("town-a",Filter.ALL,0,100);
        assertEquals(8,first.totalPages()); assertEquals(6,first.overflow()); assertEquals(8,first.rows().size());
        List<PublicRow> all=new ArrayList<>(); for(int p=0;p<8;p++) all.addAll(i.view("town-a",Filter.ALL,p,100).rows());
        assertEquals(64,all.size()); assertEquals(64,all.stream().map(PublicRow::rowId).distinct().count());
        assertTrue(all.subList(0,35).stream().allMatch(r->r.etaTicks().isPresent()));
        assertEquals(all.stream().filter(r->r.etaTicks().isPresent()).map(PublicRow::rowId).sorted().toList(),all.stream().filter(r->r.etaTicks().isPresent()).map(PublicRow::rowId).toList());
        assertEquals(7,i.view("town-a",Filter.ALL,1000,100).page());
        assertTrue(i.view("town-a",Filter.AIR,0,100).rows().isEmpty());
    }
    @Test void hundredBoardsReuseSingleAggregationAndImmutableProjectionForTwentyTicks() {
        var i=catalogIndex(); i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
        assertTrue(i.view("town-a",Filter.ALL,0,100).syncing()); i.refresh(100);
        var first=i.view("town-a",Filter.ALL,0,100);
        for(int n=0;n<100;n++) { assertSame(first,i.view("town-a",Filter.ALL,0,110)); i.refresh(110); }
        assertEquals(1,i.aggregationCount()); assertEquals(500,first.rows().getFirst().etaTicks().orElseThrow());
        assertThrows(UnsupportedOperationException.class,()->first.rows().clear());
        i.refresh(120); assertEquals(2,i.aggregationCount());
    }
    @Test void boundedCarriersEndpointsAndGroundGeometryBudgetFailClosedAndRelease() {
        var i=catalogIndex();
        for(int n=1;n<=4100;n++) i.publish(observation(key(n),State.MOVING,100,100,"g"),100);
        assertEquals(4096,i.observations().size());
        for(int n=1;n<=8200;n++) i.observeEndpoint(new Endpoint("minecraft:overworld",n,"town-a","E"),true);
        assertEquals(8192,i.endpoints().size());
        for(int n=1;n<=32;n++) assertTrue(i.reserveGroundGeometry(key(n).carrier(),"g",8192));
        assertEquals(262144,i.groundPoints()); assertFalse(i.reserveGroundGeometry(key(33).carrier(),"g",1));
        assertTrue(i.reserveGroundGeometry(key(1).carrier(),"g2",4096)); assertEquals(258048,i.groundPoints());
        assertFalse(i.reserveGroundGeometry(key(1).carrier(),"bad",8193)); assertEquals(258048,i.groundPoints());
        i.markUnloaded(key(1).carrier()); assertEquals(253952,i.groundPoints());
        i.retire(key(2).carrier(),key(2).legId()); assertEquals(245760,i.groundPoints());
    }
    @Test void missingCatalogAtCapacityCannotRegainAvailabilityThroughCarrierHeartbeat() {
        // These live observations carry last-known town/name only; neither endpoint was observed in this session.
        var i=new TownVoyageService.Index();var k=key(1);
        for(int n=10000;n<18192;n++) {
            var endpoint=new Endpoint("minecraft:overworld",n,"town-c","Removed");
            i.observeEndpoint(endpoint,true);i.markEndpointRemoved(endpoint.dimensionId(),endpoint.terminalPos());
        }
        i.publish(observation(k,State.MOVING,100,100,"g"),100);
        long beforeRemoval=i.generation(k);
        i.markEndpointRemoved(HOME.dimensionId(),HOME.terminalPos());
        var row=rows(i,"town-a",100).getFirst();assertTrue(row.etaTicks().isEmpty());
        assertEquals(State.ETA_UNKNOWN,row.state());assertNotEquals(beforeRemoval,i.generation(k));
        assertEquals(8192,i.endpoints().size());
        i.observeEndpoint(new Endpoint("minecraft:overworld",10000,"town-c","Available Again"),true);
        var heartbeat=observation(k,State.MOVING,120,120,"g");
        i.publish(new Observation(heartbeat.key(),heartbeat.kind(),heartbeat.carrierName(),heartbeat.routeName(),
                i.endpoint(HOME.dimensionId(),HOME.terminalPos(),"Unknown"),i.endpoint(AWAY.dimensionId(),AWAY.terminalPos(),"Unknown"),
                heartbeat.phase(),heartbeat.state(),heartbeat.loaded(),heartbeat.observedTick(),heartbeat.heartbeatTicks(),heartbeat.etaTicks(),heartbeat.etaObservedTick(),heartbeat.geometryId()),120);
        var retained=rows(i,"town-a",120);assertEquals(1,retained.size());assertEquals("Home",retained.getFirst().sourceName());
        assertTrue(retained.getFirst().etaTicks().isEmpty());
        assertTrue(i.endpoints().stream().noneMatch(endpoint->endpoint.terminalPos()==HOME.terminalPos() || endpoint.terminalPos()==AWAY.terminalPos()));
        i.observeEndpoint(new Endpoint("minecraft:overworld",10001,"town-c","Available Again"),true);
        i.observeEndpoint(HOME,true);i.observeEndpoint(AWAY,true);
        i.publish(observation(k,State.MOVING,140,140,"g"),140);
        assertTrue(rows(i,"town-a",140).getFirst().etaTicks().isPresent());
        i.observeEndpoint(new Endpoint(HOME.dimensionId(),HOME.terminalPos(),"","Unbound"),true);
        assertTrue(rows(i,"town-a",160).isEmpty());
    }
    @Test void endpointCapacityNeverEvictsRemovedTombstonesOrRevivesThemFromCarrierHeartbeat() {
        for(boolean allRemoved:List.of(false,true)) {
            var i=catalogIndex();
            for(int n=1;n<=8192;n++) {
                Endpoint endpoint=n==1?HOME:n==2?AWAY:new Endpoint("minecraft:overworld",n,"town-a","E");
                i.observeEndpoint(endpoint,true);
                if(allRemoved || n==1)i.markEndpointRemoved(endpoint.dimensionId(),endpoint.terminalPos());
            }
            i.observeEndpoint(new Endpoint("minecraft:overworld",8193,"town-c","New"),true);
            assertEquals(8192,i.endpoints().size());
            assertTrue(i.unavailableEndpoints().contains(HOME),"retained tombstone " + allRemoved);
            if(allRemoved)assertTrue(i.endpoints().stream().noneMatch(endpoint->endpoint.terminalPos()==8193));
            i.publish(observation(key(1),State.MOVING,100,100,"g"),100);
            assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty(),"heartbeat " + allRemoved);
            i.observeEndpoint(HOME,true);i.observeEndpoint(AWAY,true);
            i.publish(observation(key(1),State.MOVING,120,120,"g"),120);
            assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isPresent(),"real observation " + allRemoved);
        }
    }
    @Test void modeFilterBoundsItsOwnMatchingRowsInsteadOfHidingMinorityTransport() {
        var i=catalogIndex();
        for(int n=1;n<=64;n++)i.publish(observation(key(n),State.MOVING,100,100,"g"),100);
        var o=observation(key(65),State.MOVING,100,-1,"g");
        i.publish(new Observation(o.key(),Kind.AIR,o.carrierName(),o.routeName(),o.source(),o.target(),o.phase(),o.state(),o.loaded(),o.observedTick(),o.heartbeatTicks(),o.etaTicks(),o.etaObservedTick(),o.geometryId()),100);
        rows(i,"town-a",100);
        assertEquals(1,i.view("town-a",Filter.ALL,0,100).overflow());
        var air=i.view("town-a",Filter.AIR,0,100);assertEquals(1,air.rows().size());assertEquals(0,air.overflow());
    }
    @Test void restoredFutureTickDoesNotPreventActualHeartbeatInANewSession() {
        var i=catalogIndex();var k=key(1);
        i.restore(List.of(observation(k,State.MOVING,1000,1000,"g")),List.of(HOME,AWAY));
        i.publish(observation(k,State.MOVING,10,10,"g"),10);
        var row=rows(i,"town-a",10).getFirst();assertEquals(State.MOVING,row.state());assertTrue(row.etaTicks().isPresent());
    }
    @Test void worldClockFreezeImmediatelyClearsEtaAtUnchangedTickAndRequiresNewEvidence() {
        var i=catalogIndex();var k=key(1);
        i.publish(observation(k,State.MOVING,100,100,"g"),100);assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isPresent());
        i.clockSuspended(true,100);assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());
        i.clockSuspended(false,100);assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isEmpty());
        i.publish(observation(k,State.MOVING,100,100,"g"),100);assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isEmpty());
        i.publish(observation(k,State.MOVING,140,140,"g"),140);assertTrue(rows(i,"town-a",140).getFirst().etaTicks().isPresent());
    }
    @Test void newLegPublicationKeepsAlreadyReservedReplacementGeometry() {
        var i=catalogIndex();var k=key(1);
        i.publish(observation(k,State.MOVING,100,100,"g"),100);
        assertTrue(i.reserveGroundGeometry(k.carrier(),"g",10));
        i.releaseGroundGeometry(k.carrier());
        assertTrue(i.reserveGroundGeometry(k.carrier(),"return",20));
        i.publish(observation(new Key(k.carrier(),UUID.randomUUID()),State.MOVING,120,120,"return"),120);
        assertEquals(20,i.groundPoints());
    }
    @Test void missingGroundProgressCannotCarryForwardAnOldEstimate() {
        var i=catalogIndex();var k=key(1);
        i.publish(observation(k,State.MOVING,100,100,"g"),100);
        assertTrue(rows(i,"town-a",100).getFirst().etaTicks().isPresent());
        i.publish(observation(k,State.MOVING,120,-1,"g"),120);
        assertTrue(rows(i,"town-a",120).getFirst().etaTicks().isEmpty());
    }
    @Test void sharedProjectionAgesConfirmedEtaWithoutTurningPredictionIntoArrival() {
        for (Kind kind : Kind.values()) {
            var index = catalogIndex();
            var key = key(1);
            index.publish(new Observation(key, kind, "Carrier", "Route", HOME, AWAY, "MOVING", State.MOVING,
                    true, 100, 20, OptionalLong.of(30), 100, "g"), 100);
            assertEquals(30, rows(index, "town-b", 100).getFirst().etaTicks().orElseThrow(), kind.name());
            var aged = rows(index, "town-b", 120).getFirst();
            assertEquals(10, aged.etaTicks().orElseThrow(), kind.name());
            assertEquals(Relation.INBOUND, aged.relation());
            var elapsed = rows(index, "town-b", 140).getFirst();
            assertEquals(aged.rowId(), elapsed.rowId());
            assertEquals(State.ETA_UNKNOWN, elapsed.state());
            assertTrue(elapsed.etaTicks().isEmpty());
            assertEquals(key, index.currentKey(key.carrier()).orElseThrow());
        }
    }
    @Test void beforeServerStartLookupAndPublisherRemainSafeAndStateMapsPublicReasons() {
        assertTrue(TownVoyageService.existing(null).isEmpty());
        assertDoesNotThrow(()->TownVoyagePublisher.observe(null,null));
        assertDoesNotThrow(()->TownVoyagePublisher.retireCurrent(null,key(1).carrier()));
        assertEquals(Endpoint.unknown(),TownVoyagePublisher.endpoint(null,null,"X"));
        assertEquals(State.PAUSED,TownVoyagePublisher.state(true,"fuel_empty"));
        assertEquals(State.FUEL_BLOCKED,TownVoyagePublisher.state(false,"insufficient_power"));
        assertEquals(State.WAITING_TERRAIN,TownVoyagePublisher.state(false,"waiting_terrain"));
        assertEquals(State.RECOVERING,TownVoyagePublisher.state(false,"recovering"));
        assertEquals(State.WAITING_RESOURCE,TownVoyagePublisher.state(false,"waiting_resource"));
        assertEquals(State.MOVING,TownVoyagePublisher.state(false,"private_status_never_exported"));
    }
}
