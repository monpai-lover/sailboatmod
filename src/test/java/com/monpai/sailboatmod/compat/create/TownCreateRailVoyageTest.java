package com.monpai.sailboatmod.compat.create;

import static com.monpai.sailboatmod.compat.create.CreateRailLogistics.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

import com.monpai.sailboatmod.market.logistics.rail.RailTrainSnapshot;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TownCreateRailVoyageTest {
    private static final UUID TRAIN = new UUID(0, 1);
    private static final Station A = station(11, "minecraft:overworld", 101, "Central");
    private static final Station B = station(12, "minecraft:the_nether", 202, "Central");
    private static final Station C = station(13, "minecraft:overworld", 303, "Harbor");

    private static Station station(long id, String dim, long pos, String name) {
        return new Station(new UUID(0, id), dim, pos, name);
    }
    private static BoardSample sample(UUID train, Station current, Station target, double remaining, double speed,
                                      boolean paused, boolean derailed, boolean signal, boolean chunks, boolean graph) {
        return new BoardSample(train, "Express", current, target, remaining, speed,
                paused, derailed, signal, chunks, graph, target != null, false);
    }
    private static BoardSample moving(double remaining) { return sample(TRAIN, null, B, remaining, .5, false, false, false, false, true); }
    private static BoardSample idle(Station current) { return sample(TRAIN, current, null, 0, 0, false, false, false, false, true); }
    private static CarrierKey carrier(UUID train) { return new CarrierKey("create-train", "global", train); }

    private static final class Harness implements Sink {
        final BoardTracker tracker;
        final Map<CarrierKey, Observation> rows = new HashMap<>();
        final List<CarrierKey> retired = new ArrayList<>(), unloaded = new ArrayList<>();
        final Map<String, String> towns = new HashMap<>();
        final Map<String, Integer> resolutions = new HashMap<>();
        final Set<String> unavailable = new HashSet<>();
        final Map<String, Endpoint> resolverPrevious = new HashMap<>();
        Harness() { this(4096); }
        Harness(int cap) {
            tracker = new BoardTracker(cap);
            towns.put(address(A), "town-a"); towns.put(address(B), "town-b"); towns.put(address(C), "town-c");
        }
        static String address(Station s) { return s.dimensionId() + "/" + s.terminalPos(); }
        Endpoint resolve(Station station, Endpoint previous) {
            String address = address(station);
            resolutions.merge(address, 1, Integer::sum);
            resolverPrevious.put(address, previous);
            if (unavailable.contains(station.dimensionId())) return previous;
            return new Endpoint(station.dimensionId(), station.terminalPos(), towns.getOrDefault(address, ""), station.displayName());
        }
        void scan(long tick, BoardSample... samples) { tracker.scan(List.of(samples), true, tick, this::resolve, this); }
        void scan(long tick, List<BoardSample> samples, boolean complete) { tracker.scan(samples, complete, tick, this::resolve, this); }
        Observation row() {
            Observation row = rows.get(carrier(TRAIN));
            assertNotNull(row, "active train must publish a row");
            return row;
        }
        @Override public void publish(Observation observation) { rows.put(observation.key().carrier(), observation); }
        @Override public void retireCurrent(CarrierKey carrier) { retired.add(carrier); rows.remove(carrier); }
        @Override public void markUnloaded(CarrierKey carrier) { unloaded.add(carrier); }
        void establishMotion() { scan(0, moving(220)); scan(20, moving(210)); scan(40, moving(200)); }
    }

    @Test void coldMidrouteIncludesKnownDestinationTownWithUnknownSource() {
        Harness h = new Harness(); h.scan(0, moving(220));
        assertEquals(Endpoint.unknown(), h.row().source());
        assertEquals("town-b", h.row().target().townId());
        assertEquals(Relation.INBOUND, forTown(h.row(), "town-b").orElseThrow().relation());
        assertTrue(forTown(h.row(), "town-a").isEmpty());
        assertEquals(carrier(TRAIN), h.row().key().carrier());
        assertEquals(LogisticsCarrier.Kind.LAND, h.row().kind());
        assertTrue(h.row().loaded(), "fresh authoritative simulation is available, without claiming entity chunks");
    }

    @Test void equalStationNamesKeepExactDimensionAndPositionBindings() {
        Harness h = new Harness(); h.scan(0, idle(A)); h.scan(20, moving(220));
        assertEquals("minecraft:overworld", h.row().source().dimensionId());
        assertEquals(101, h.row().source().terminalPos());
        assertEquals("minecraft:the_nether", h.row().target().dimensionId());
        assertEquals(202, h.row().target().terminalPos());
        assertEquals(Relation.OUTBOUND, forTown(h.row(), "town-a").orElseThrow().relation());
        assertEquals(Relation.INBOUND, forTown(h.row(), "town-b").orElseThrow().relation());
    }

    @Test void sameNameAndDimensionAtDifferentPositionsDoNotConflate() {
        Station other = station(14, A.dimensionId(), 404, A.displayName());
        Harness h = new Harness(); h.towns.put(Harness.address(other), "town-other");
        h.scan(0, idle(A)); h.scan(20, sample(TRAIN, null, other, 200, .5, false, false, false, false, true));
        assertEquals("town-other", h.row().target().townId());
        assertNotEquals(h.row().source().terminalPos(), h.row().target().terminalPos());
    }

    @Test void idleScheduleIsHiddenButItsObservedStationBecomesDeparture() {
        Harness h = new Harness(); h.scan(0, idle(A));
        assertTrue(h.rows.isEmpty());
        h.scan(20, moving(220)); assertEquals("town-a", h.row().source().townId());
    }

    @Test void arrivalRetiresOldLegAndReturnPublishesOneFreshLeg() {
        Harness h = new Harness(); h.scan(0, idle(A)); h.scan(20, moving(220));
        Key outbound = h.row().key(); h.scan(40, idle(B)); assertTrue(h.rows.isEmpty());
        h.scan(60, sample(TRAIN, null, A, 200, .5, false, false, false, false, true));
        Key inbound = h.row().key(); assertNotEquals(outbound.legId(), inbound.legId());
        assertEquals("town-b", h.row().source().townId()); assertEquals("town-a", h.row().target().townId());
        h.scan(80, sample(TRAIN, null, A, 190, .5, false, false, false, false, true));
        assertEquals(inbound, h.row().key()); assertEquals(1, h.rows.size());
    }

    @Test void observedArrivalRetiresEvenIfDestinationClearLagsTheStation() {
        Harness h = new Harness(); h.scan(0, moving(20));
        h.scan(20, sample(TRAIN, B, B, 0, 0, false, false, false, false, true));
        assertTrue(h.rows.isEmpty());
    }

    @Test void renamingAndPausingPreserveCurrentLegIdentity() {
        Harness h = new Harness(); h.establishMotion(); Key key = h.row().key();
        Station renamed = new Station(B.nativeId(), B.dimensionId(), B.terminalPos(), "Renamed terminal");
        h.scan(60, sample(TRAIN, null, renamed, 190, .5, true, false, false, false, true));
        assertEquals(key, h.row().key()); assertEquals("Renamed terminal", h.row().target().displayName());
        assertEquals(State.PAUSED, h.row().state()); assertTrue(h.row().etaTicks().isEmpty());
    }

    @Test void stationNativeIdChangeStartsNewLegEvenAtSameAddress() {
        Harness h = new Harness(); h.scan(0, moving(220)); Key old = h.row().key();
        Station replacement = new Station(new UUID(0, 99), B.dimensionId(), B.terminalPos(), B.displayName());
        h.scan(20, sample(TRAIN, null, replacement, 210, .5, false, false, false, false, true));
        assertNotEquals(old, h.row().key());
    }

    @Test void redirectKeepsActuallyVisitedSourceInsteadOfOldUnvisitedDestination() {
        Harness h = new Harness(); h.scan(0, idle(A)); h.scan(20, moving(220)); Key old = h.row().key();
        h.scan(40, sample(TRAIN, null, C, 100, .5, false, false, false, false, true));
        assertNotEquals(old, h.row().key()); assertEquals("town-a", h.row().source().townId());
        assertEquals("town-c", h.row().target().townId()); assertTrue(h.row().etaTicks().isEmpty());
    }

    @Test void coldRedirectKeepsSourceUnknown() {
        Harness h = new Harness(); h.scan(0, moving(220));
        h.scan(20, sample(TRAIN, null, C, 100, .5, false, false, false, false, true));
        assertEquals(Endpoint.unknown(), h.row().source());
    }

    @Test void confirmedTrackProgressUsesBlocksPerGameTickWithoutExtraTwenty() {
        Harness h = new Harness(); h.scan(0, moving(220)); assertTrue(h.row().etaTicks().isEmpty());
        h.scan(20, moving(210)); h.scan(40, moving(200));
        assertEquals(400, h.row().etaTicks().orElseThrow()); assertEquals(40, h.row().etaObservedTick());
        assertEquals(State.MOVING, h.row().state());
    }

    @Test void finiteCommandedSpeedAloneCannotManufactureEta() {
        Harness h = new Harness(); h.scan(0, moving(200));
        assertTrue(h.row().etaTicks().isEmpty()); assertEquals(State.ETA_UNKNOWN, h.row().state());
    }

    @ParameterizedTest @CsvSource({"paused,PAUSED", "signal,WAITING_RESOURCE", "chunks,WAITING_TERRAIN", "derailed,RECOVERING", "graph,RECOVERING"})
    void nativeBlockInvalidatesEtaImmediatelyAndResumeNeedsFreshProgress(String block, State expected) {
        Harness h = new Harness(); h.establishMotion(); Key key = h.row().key();
        h.scan(60, sample(TRAIN, null, B, 190, .5, block.equals("paused"), block.equals("derailed"),
                block.equals("signal"), block.equals("chunks"), !block.equals("graph")));
        assertEquals(key, h.row().key()); assertEquals(expected, h.row().state()); assertTrue(h.row().etaTicks().isEmpty());
        h.scan(80, moving(180)); assertTrue(h.row().etaTicks().isEmpty(), "first resumed heartbeat is insufficient");
        h.scan(100, moving(170)); assertEquals(340, h.row().etaTicks().orElseThrow());
    }

    @Test void invalidNativeTrainWithDestinationKeepsTheLegButDropsEtaUntilFreshProgress() {
        Harness h = new Harness(); h.establishMotion();
        Key key = h.row().key();
        h.scan(60, new BoardSample(TRAIN, "Express", null, B, 190, .5,
                false, false, false, false, true, true, true));
        assertEquals(key, h.row().key());
        assertEquals(State.RECOVERING, h.row().state());
        assertTrue(h.row().etaTicks().isEmpty());
        h.scan(80, moving(180));
        assertTrue(h.row().etaTicks().isEmpty());
        h.scan(100, moving(170));
        assertEquals(key, h.row().key());
        assertEquals(340, h.row().etaTicks().orElseThrow());
        h.scan(120, new BoardSample(TRAIN, "Express", null, null, 0, 0,
                false, false, false, false, true, false, true));
        assertTrue(h.rows.isEmpty());
    }

    @ParameterizedTest @CsvSource({"200,0", "205,.5", "200,.5", "NaN,.5", "190,NaN", "190,Infinity", "-1,.5"})
    void stopStallBackwardOrInvalidEvidenceDropsMotionTrust(double remaining, double speed) {
        Harness h = new Harness(); h.establishMotion();
        h.scan(60, sample(TRAIN, null, B, remaining, speed, false, false, false, false, true));
        assertTrue(h.row().etaTicks().isEmpty());
        h.scan(80, moving(180)); assertTrue(h.row().etaTicks().isEmpty());
        h.scan(100, moving(170)); assertEquals(340, h.row().etaTicks().orElseThrow());
    }

    @Test void detourChangesGeometryButKeepsTheActualLeg() {
        Harness h = new Harness(); h.establishMotion(); Key key = h.row().key(); String geometry = h.row().geometryId();
        h.scan(60, moving(250)); assertEquals(key, h.row().key());
        assertNotEquals(geometry, h.row().geometryId()); assertTrue(h.row().etaTicks().isEmpty());
    }

    @Test void negativeNativeSpeedCanCoherentlyAdvanceRemainingTrackDistance() {
        Harness h = new Harness(); h.scan(0, sample(TRAIN, null, B, 220, -.5, false, false, false, false, true));
        h.scan(20, sample(TRAIN, null, B, 210, -.5, false, false, false, false, true));
        assertEquals(420, h.row().etaTicks().orElseThrow());
    }

    @Test void staleSamplesAndRepeatedClockCannotInventRecentProgress() {
        Harness h = new Harness(); h.scan(0, moving(220)); h.scan(0, moving(210)); assertTrue(h.row().etaTicks().isEmpty());
        h.scan(240, moving(200)); assertTrue(h.row().etaTicks().isEmpty());
        h.scan(260, moving(190)); assertEquals(380, h.row().etaTicks().orElseThrow());
    }

    @Test void etaExceedingPublicBoundRemainsUnknown() {
        Harness h = new Harness(); h.scan(0, moving(1_000_000)); h.scan(20, moving(999_999.999));
        assertTrue(h.row().etaTicks().isEmpty());
    }

    @Test void longRunningProgressRetainsOnlyRecentBoundedMotionSamples() {
        Harness h = new Harness();
        for (int tick = 0; tick <= 240; tick++) h.scan(tick, moving(220 - tick * .5));
        assertEquals(200, h.row().etaTicks().orElseThrow());
    }

    @Test void nativeCancellationRetiresConfirmedLegAndColdCrashCannotInventTarget() {
        Harness h = new Harness(); h.establishMotion();
        BoardSample canceled = sample(TRAIN, null, null, 0, 0, false, true, false, false, false);
        h.scan(60, canceled); assertTrue(h.rows.isEmpty());
        h.scan(80, canceled); assertTrue(h.rows.isEmpty()); assertTrue(h.retired.contains(carrier(TRAIN)));
    }

    @Test void destinationWithoutActiveNavigationDoesNotPublish() {
        Harness h = new Harness();
        h.scan(0, new BoardSample(TRAIN, "Express", A, B, 200, .5, false, false, false, false, true, false, false));
        assertTrue(h.rows.isEmpty());
    }

    @Test void unknownNativeStationPositionDoesNotResolveOrFakeOverworldTown() {
        Harness h = new Harness(); Station unknown = station(12, "", 0, "Central");
        h.scan(0, sample(TRAIN, null, unknown, 200, .5, false, false, false, false, true));
        assertEquals(Endpoint.unknown(), h.row().target()); assertTrue(h.resolutions.isEmpty());
    }

    @Test void uniqueEndpointLookupIsDeduplicatedWithinEachScanAndClaimsRefreshNormally() {
        Harness h = new Harness(); List<BoardSample> trains = new ArrayList<>();
        for (int i = 1; i <= 4096; i++) trains.add(sample(new UUID(0, i), A, B, 200, .5, false, false, false, false, true));
        h.scan(0, trains, true);
        assertEquals(1, h.resolutions.get(Harness.address(A))); assertEquals(1, h.resolutions.get(Harness.address(B)));
        h.towns.put(Harness.address(B), "new-town"); h.scan(20, trains, true);
        assertEquals(2, h.resolutions.get(Harness.address(B))); assertEquals("new-town", h.row().target().townId());
    }

    @Test void unavailableNativeDimensionPreservesKnownBindingButFirstUnknownStaysUnknown() {
        Harness h = new Harness(); h.scan(0, moving(220)); h.unavailable.add(B.dimensionId()); h.scan(20, moving(210));
        assertEquals("town-b", h.row().target().townId());
        Harness cold = new Harness(); cold.unavailable.add(B.dimensionId()); cold.scan(0, moving(220));
        assertEquals("", cold.row().target().townId());
    }

    @Test void fullReadRetiresAbsentNativeTrainWhilePartialReadOnlyMarksItUnavailable() {
        Harness h = new Harness(); h.establishMotion(); Key key = h.row().key();
        h.scan(60, List.of(), false); assertEquals(key, h.row().key());
        assertEquals(List.of(carrier(TRAIN)), h.unloaded); assertTrue(h.retired.isEmpty());
        h.scan(80, moving(180)); assertTrue(h.row().etaTicks().isEmpty());
        h.scan(100, List.of(), true); assertTrue(h.rows.isEmpty()); assertTrue(h.tracker.trackedTrainIds().isEmpty());
    }

    @Test void clearForgetsOldWorldSourcesLegsAndEndpointBindings() {
        Harness h = new Harness(); h.scan(0, idle(A)); h.scan(20, moving(220)); Key old = h.row().key();
        h.tracker.clear(); h.rows.clear(); h.unavailable.add(B.dimensionId()); h.scan(40, moving(200));
        assertNotEquals(old, h.row().key()); assertEquals(Endpoint.unknown(), h.row().source());
        assertEquals("", h.row().target().townId());
    }

    @Test void capPressurePreservesTrackedIdentitiesDeterministicallyWithoutTruncatingWebList() {
        UUID tracked = new UUID(0, 99); BoardSample old = sample(tracked, null, B, 200, .5, false, false, false, false, true);
        List<BoardSample> candidates = List.of(old, moving(200), sample(new UUID(0, 2), null, B, 200, .5, false, false, false, false, true));
        List<RailTrainSnapshot> web = new ArrayList<>();
        for (int i = 0; i < 8; i++) web.add(new RailTrainSnapshot(new UUID(0, i), null, "Train", 0, 0, 0, false, null,
                false, null, Double.NaN, Double.NaN, 0, 0, 0));
        BoardReadBuilder first = new BoardReadBuilder(Set.of(tracked), 2);
        for (BoardSample s : candidates) if (first.accepts(s.trainId())) first.add(s);
        Readout result = first.finish(web); assertFalse(result.complete()); assertEquals(web, result.webTrains());
        assertEquals(Set.of(TRAIN, tracked), new HashSet<>(result.boardTrains().stream().map(BoardSample::trainId).toList()));
        BoardReadBuilder shuffled = new BoardReadBuilder(Set.of(tracked), 2);
        List<BoardSample> reverse = new ArrayList<>(candidates); Collections.reverse(reverse);
        for (BoardSample s : reverse) if (shuffled.accepts(s.trainId())) shuffled.add(s);
        assertEquals(result.boardTrains(), shuffled.finish(web).boardTrains());
    }

    @Test void providerIndexRemainsBoundedAndPartialPressureCannotDeleteUnseenTrains() {
        Harness h = new Harness(2); BoardSample second = sample(new UUID(0, 2), null, B, 200, .5, false, false, false, false, true);
        h.scan(0, List.of(moving(220), second), true);
        BoardSample third = sample(new UUID(0, 3), null, B, 200, .5, false, false, false, false, true);
        h.scan(20, List.of(third), false);
        assertEquals(Set.of(TRAIN, second.trainId()), h.tracker.trackedTrainIds());
        assertEquals(2, h.rows.size()); assertTrue(h.retired.isEmpty());
        assertTrue(h.unloaded.contains(carrier(TRAIN))); assertTrue(h.unloaded.contains(carrier(second.trainId())));
    }

    @Test void partialNativeReadRetiresProvenMissingTrainAndAdmitsItsReplacement() {
        Harness h = new Harness(2);
        UUID secondId = new UUID(0, 2), thirdId = new UUID(0, 3), fourthId = new UUID(0, 4);
        BoardSample second = sample(secondId, null, B, 220, .5, false, false, false, false, true);
        h.scan(0, List.of(moving(220), second), true);
        BoardReadBuilder builder = new BoardReadBuilder(h.tracker.trackedTrainIds(), 2);
        for (BoardSample s : List.of(second,
                sample(thirdId, null, B, 220, .5, false, false, false, false, true),
                sample(fourthId, null, B, 220, .5, false, false, false, false, true))) {
            builder.observedNativeId(s.trainId());
            if (builder.accepts(s.trainId())) builder.add(s);
        }
        Readout partial = builder.finish(List.of());
        assertFalse(partial.complete());
        h.tracker.scan(partial.boardTrains(), partial.complete(), partial.confirmedMissingTrackedIds(), 20, h::resolve, h);
        assertEquals(Set.of(secondId, thirdId), h.tracker.trackedTrainIds());
        assertFalse(h.rows.containsKey(carrier(TRAIN)));
        assertTrue(h.rows.containsKey(carrier(thirdId)));
        assertEquals(Set.of(TRAIN), partial.confirmedMissingTrackedIds());
        // Presence in the native pass is still authoritative when its board sample is invalid or filtered out.
        BoardReadBuilder nativePresence = new BoardReadBuilder(Set.of(TRAIN), 2);
        nativePresence.observedNativeId(TRAIN);
        assertTrue(nativePresence.finish(List.of()).confirmedMissingTrackedIds().isEmpty());
    }

    @Test void freshNativeStationRenameOverridesEarlierHistoricalSourceWithOneAddressLookup() {
        Harness h = new Harness(); UUID otherId = new UUID(0, 2);
        h.scan(0, idle(B), sample(otherId, A, null, 0, 0, false, false, false, false, true));
        BoardSample fromB = sample(TRAIN, null, A, 220, .5, false, false, false, false, true);
        BoardSample towardB = sample(otherId, null, B, 220, .5, false, false, false, false, true);
        h.scan(20, fromB, towardB);
        Key fromBKey = h.row().key(), towardBKey = h.rows.get(carrier(otherId)).key();
        Station renamedB = new Station(B.nativeId(), B.dimensionId(), B.terminalPos(), "New terminal name");
        h.scan(40, sample(TRAIN, null, A, 210, .5, false, false, false, false, true),
                sample(otherId, null, renamedB, 210, .5, false, false, false, false, true));
        assertEquals("New terminal name", h.row().source().displayName());
        assertEquals("New terminal name", h.rows.get(carrier(otherId)).target().displayName());
        assertEquals(fromBKey, h.row().key()); assertEquals(towardBKey, h.rows.get(carrier(otherId)).key());
        assertEquals(2, h.resolutions.get(Harness.address(B)), "one lookup per exact address on each active scan");
    }

    @Test void stationMetadataCacheEvictsBeyondIts8192ExactAddresses() {
        Harness h = new Harness(); List<BoardSample> trains = new ArrayList<>();
        Station firstTarget = station(50_001, A.dimensionId(), 50_001, "First target");
        h.towns.put(Harness.address(firstTarget), "first-town");
        for (int i = 0; i < 4096; i++) {
            Station from = station(40_000L + i * 2L, A.dimensionId(), 40_000L + i * 2L, "From");
            Station to = i == 0 ? firstTarget : station(40_001L + i * 2L, A.dimensionId(), 40_001L + i * 2L, "To");
            trains.add(sample(new UUID(0, i + 1), from, to, 200, .5, false, false, false, false, true));
        }
        h.scan(0, trains, true);
        h.scan(20, List.of(sample(new UUID(0, 9_999), A, B, 200, .5, false, false, false, false, true)), true);
        h.scan(40, sample(TRAIN, null, firstTarget, 200, .5, false, false, false, false, true));
        Endpoint previous = h.resolverPrevious.get(Harness.address(firstTarget));
        assertNotNull(previous, "metadata lookup must receive its prior exact-address cache entry");
        assertEquals("", previous.townId(), "oldest exact address was evicted");
    }

    @Test void sourceGuardRequiresOneNativeRegistryLoopAndKeepsOldOptionalBoundary() throws Exception {
        String bridge = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/compat/create/CreateTrainBridge.java"));
        String logistics = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/compat/create/CreateRailLogistics.java"));
        assertEquals(1, bridge.split("Create\\.RAILWAYS\\.trains", -1).length - 1);
        assertTrue(bridge.contains("BoardReadBuilder"), "the native pass also feeds the bounded board read");
        assertTrue(logistics.indexOf("!available()") < logistics.indexOf("CreateTrainBridge.overworldTrains()"));
        assertTrue(logistics.contains("TownVoyageService.existing(server)"));
        assertFalse(bridge.contains("getChunk(")); assertFalse(logistics.contains("getBlockEntity("));
    }
}
