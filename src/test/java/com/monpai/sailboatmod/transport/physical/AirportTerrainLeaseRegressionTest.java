package com.monpai.sailboatmod.transport.physical;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Native UNKNOWN has timeout1 (strict age>1); cold FULL needs three native ticks. */
class AirportTerrainLeaseRegressionTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID BODY = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final AABB HULL = new AABB(0, 63, 0, 8, 70, 8);
    private static final Vec3 NEXT = HULL.getCenter();
    private static final ChunkPos AIRPORT = new ChunkPos(2000, 2000);

    @Test void coldFullSurvivesUnknownExpiryBetweenFiveTickControllerUpdates() {
        // First establish the native lifetime failure independently of the window.
        var unleased = new ExpiringBackend();
        CompletableFuture<Boolean> unknownOnly = unleased.request(AIRPORT);
        unleased.advanceTo(3);
        assertFalse(unknownOnly.join());
        assertFalse(unleased.isLoaded(AIRPORT));

        var backend = new ExpiringBackend();
        var window = airportWindow(backend, List.of(AIRPORT));
        assertFalse(update(window));
        backend.advanceTo(3); // The RVC has not updated since tick0.
        assertTrue(backend.isLoaded(AIRPORT), "An admitted request must own a FULL lease before UNKNOWN expires");
        backend.advanceTo(5);
        assertTrue(update(window));
        assertEquals(List.of("temporary+", "request"), backend.eventsFor(AIRPORT).subList(0, 2));
        assertEquals(1, backend.temporaryAdds(AIRPORT));
        assertEquals(0, backend.temporaryRemoves(AIRPORT), "Airport promotion must retain the same live ticket without a gap");
        assertEquals(OWNER, backend.temporary.get(AIRPORT));
        assertFalse(backend.persistent.containsKey(AIRPORT), "Airport terrain must stay ephemeral after FULL");
    }

    @Test void coldMovingAndMetadataRequestsUseTheSameLeaseUntilPersistentHandoff() {
        var backend = new ExpiringBackend();
        backend.localAlreadyLoaded = false;
        var window = new TransportLoadingWindow(backend);
        window.setPinnedEndpoints(List.of(new BlockPos(AIRPORT.getMinBlockX(), 81, AIRPORT.getMinBlockZ())));
        for (int tick = 0; tick <= 20; tick += 5) {
            backend.advanceTo(tick);
            update(window);
        }
        assertTrue(update(window), "Cold moving and metadata chunks must finish despite five-tick sampling");
        assertTrue(backend.persistent.containsKey(AIRPORT));
        assertTrue(backend.temporary.isEmpty(), "Only moving/metadata final tickets may remain after promotion");
        Set<ChunkPos> expected = new HashSet<>(TransportLoadingWindow.desiredChunks(HULL, NEXT));
        expected.add(AIRPORT);
        for (ChunkPos chunk : expected) {
            List<String> events = backend.eventsFor(chunk);
            assertTrue(events.indexOf("temporary+") < events.indexOf("request"));
            assertTrue(events.indexOf("persistent+") < events.indexOf("temporary-"),
                    "Acquire the ordinary moving ticket before releasing its provisional ticket: " + chunk);
        }
    }

    @Test void successfulFutureBeforeVisibilityKeepsItsLeaseAndDoesNotRequestAgain() {
        var backend = new ExpiringBackend();
        backend.mode = ResultMode.EARLY_SUCCESS;
        var window = airportWindow(backend, List.of(AIRPORT));
        assertFalse(update(window));
        for (int tick : new int[]{5, 10}) {
            backend.advanceTo(tick);
            assertFalse(update(window));
        }
        assertEquals(1, backend.requests.getOrDefault(AIRPORT, 0),
                "A successful future awaiting real getChunkNow visibility is still one admitted request");
        assertEquals(OWNER, backend.temporary.get(AIRPORT));
        assertEquals(0, backend.temporaryRemoves(AIRPORT));
        backend.visible.add(AIRPORT);
        backend.advanceTo(15);
        assertTrue(update(window));
        assertEquals(1, backend.temporaryAdds(AIRPORT));
        assertEquals(0, backend.temporaryRemoves(AIRPORT));
    }

    @Test void closeRetargetRejectAndIdentityChangesReleaseUnfinishedOwnLeases() {
        for (End end : End.values()) {
            var backend = new ExpiringBackend();
            var window = airportWindow(backend, List.of(AIRPORT));
            update(window);
            assertEquals(OWNER, backend.temporary.get(AIRPORT), "Pending lease before " + end);
            CompletableFuture<Boolean> future = backend.latest.get(AIRPORT);
            switch (end) {
                case CLOSE -> window.close();
                case RETARGET -> window.setAirportPins(List.of(new ChunkPos(3000, 3000)));
                case REJECT -> window.rejectAirportPins();
                case OWNER_CHANGE -> window.update(null, UUID.randomUUID(), BODY, HULL, NEXT);
                case BODY_CHANGE -> window.update(null, OWNER, UUID.randomUUID(), HULL, NEXT);
            }
            assertFalse(backend.temporary.containsKey(AIRPORT), end.toString());
            assertEquals(1, backend.temporaryRemoves(AIRPORT), end.toString());
            assertFalse(future.isCancelled(), "Shared native future must not be cancelled: " + end);
            backend.advanceTo(5);
            assertFalse(backend.temporary.containsKey(AIRPORT), "A late callback cannot reacquire removed ownership");
        }
    }

    @Test void falseExceptionalAndSynchronouslyThrowingRequestsReleaseTheProvisionalLease() {
        for (ResultMode mode : List.of(ResultMode.FALSE, ResultMode.EXCEPTIONAL, ResultMode.THROW)) {
            var backend = new ExpiringBackend();
            backend.mode = mode;
            var window = airportWindow(backend, List.of(AIRPORT));
            update(window);
            backend.advanceTo(5);
            update(window);
            assertEquals(1, backend.temporaryAdds(AIRPORT), "Admission must precede the failing native request: " + mode);
            assertEquals(1, backend.temporaryRemoves(AIRPORT), "Release failed request once: " + mode);
            assertFalse(backend.temporary.containsKey(AIRPORT), mode.toString());
            assertEquals(1, backend.requests.getOrDefault(AIRPORT, 0), "Failure backoff must not immediately replay: " + mode);
        }
    }

    @Test void deniedAdmissionAndUnavailablePermitNeverRunTheCallbackRequestOrLease() {
        for (Admission admission : List.of(Admission.DENIED, Admission.PERMIT_UNAVAILABLE)) {
            var backend = new ExpiringBackend();
            backend.admission = admission;
            var window = airportWindow(backend, remoteChunks(24));
            update(window);
            update(window); // Same real tick cannot acquire a second attempt budget.
            assertEquals(0, backend.admittedCallbacks, admission.toString());
            assertTrue(backend.requests.isEmpty(), "Denied admission is not a false native request: " + admission);
            assertTrue(backend.temporary.isEmpty(), admission.toString());
            assertTrue(backend.admissionAttempts.getOrDefault(0L, 0) <= 4,
                    "The budget counts admission attempts, including denials");
        }
    }

    @Test void permanentFailuresAtTheHeadCannotStarveTheTwentyFirstAirportChunk() {
        var backend = new ExpiringBackend();
        List<ChunkPos> airports = remoteChunks(22);
        backend.alwaysFail.addAll(airports.subList(0, 20));
        var window = airportWindow(backend, airports);
        for (int tick = 0; tick <= 200; tick += 5) {
            backend.advanceTo(tick);
            update(window);
        }
        for (ChunkPos healthy : airports.subList(20, 22)) {
            assertTrue(backend.requests.containsKey(healthy), "A repeatedly eligible failure prefix cannot monopolize four slots");
            assertTrue(backend.isLoaded(healthy), "Later healthy requests must keep their admitted lease through FULL");
        }
        assertTrue(backend.admissionAttempts.values().stream().allMatch(attempts -> attempts <= 4));
    }

    @Test void unfinishedRequestsShareFourAttemptsPerRealTickAndSixteenProvisionalLeases() {
        var backend = new ExpiringBackend();
        backend.mode = ResultMode.NEVER_FINISH;
        var window = airportWindow(backend, remoteChunks(40));
        for (int tick = 0; tick < 10; tick++) {
            backend.advanceTo(tick);
            update(window);
            update(window);
        }
        assertEquals(16, backend.requests.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(16, backend.temporary.size());
        assertEquals(16, backend.maximumUnfinished);
        assertTrue(backend.admissionAttempts.values().stream().allMatch(attempts -> attempts <= 4));
        window.close();
        assertTrue(backend.temporary.isEmpty());
        assertTrue(backend.latest.values().stream().noneMatch(CompletableFuture::isCancelled));
    }

    @Test void repeatingIdenticalAirportPinsDoesNotResetFairProgressPastPermanentFailures() {
        var backend = new ExpiringBackend();
        List<ChunkPos> airports = remoteChunks(22);
        backend.alwaysFail.addAll(airports.subList(0, 20));
        var window = airportWindow(backend, airports);
        for (int tick = 0; tick <= 200; tick += 5) {
            backend.advanceTo(tick);
            assertTrue(window.setAirportPins(new ArrayList<>(airports)));
            update(window);
        }
        for (ChunkPos healthy : airports.subList(20, 22)) {
            assertTrue(backend.isLoaded(healthy), "Repeated normal configuration must not restart the failed prefix");
            assertEquals(OWNER, backend.temporary.get(healthy));
            assertEquals(1, backend.temporaryAdds(healthy), "Equal pin sets must not churn an admitted ticket");
        }
        assertTrue(backend.admissionAttempts.values().stream().allMatch(attempts -> attempts <= 4));
    }

    @Test void clearingRejectingOrRetargetingAirportPinsKeepsAnOverlappingColdMovingLease() {
        for (String action : List.of("clear", "reject", "retarget")) {
            var backend = new ExpiringBackend();
            backend.localAlreadyLoaded = false;
            ChunkPos overlap = new ChunkPos(-1, -1); // First cold chunk in the ordinary moving window.
            var window = airportWindow(backend, List.of(overlap));
            assertFalse(update(window));
            assertEquals(OWNER, backend.temporary.get(overlap));
            switch (action) {
                case "clear" -> window.clearAirportPins();
                case "reject" -> window.rejectAirportPins();
                case "retarget" -> assertTrue(window.setAirportPins(List.of(AIRPORT)));
                default -> fail("Unexpected test action");
            }
            assertEquals(OWNER, backend.temporary.get(overlap), "Still required by movingWanted after " + action);
            assertEquals(0, backend.temporaryRemoves(overlap), action);
            backend.advanceTo(3);
            assertTrue(backend.isLoaded(overlap), "Moving generation must outlive UNKNOWN despite " + action);
            backend.advanceTo(5);
            update(window);
            assertEquals(OWNER, backend.persistent.get(overlap));
            assertFalse(backend.temporary.containsKey(overlap));
            List<String> events = backend.eventsFor(overlap);
            assertTrue(events.indexOf("persistent+") < events.indexOf("temporary-"), action);
            assertEquals(1, backend.temporaryRemoves(overlap), action);
        }
    }

    @Test void throwingMovingTicketHandoffKeepsItsTemporaryLeaseForRetryOrClose() {
        for (boolean retry : new boolean[]{true, false}) {
            var backend = new ExpiringBackend();
            var window = new TransportLoadingWindow(backend);
            window.setPinnedEndpoints(List.of(new BlockPos(AIRPORT.getMinBlockX(), 81, AIRPORT.getMinBlockZ())));
            assertFalse(update(window));
            assertEquals(OWNER, backend.temporary.get(AIRPORT));
            backend.advanceTo(5);
            backend.throwPersistentAdd = true;
            backend.failingPersistentChunk = AIRPORT;
            try {
                assertFalse(update(window), "A failed persistent handoff cannot report ready");
            } catch (IllegalStateException expectedFailure) {
                assertEquals("persistent handoff failed", expectedFailure.getMessage());
            }
            assertEquals(OWNER, backend.temporary.get(AIRPORT));
            assertFalse(backend.persistent.containsKey(AIRPORT));
            assertEquals(0, backend.temporaryRemoves(AIRPORT), "Keep known FULL protection after a throwing31 add");
            assertTrue(backend.eventsFor(AIRPORT).contains("persistent-throw"));
            if (retry) {
                backend.throwPersistentAdd = false;
                assertTrue(update(window));
                assertEquals(OWNER, backend.persistent.get(AIRPORT));
                List<String> events = backend.eventsFor(AIRPORT);
                assertTrue(events.indexOf("persistent+") < events.indexOf("temporary-"));
            }
            window.close();
            assertTrue(backend.temporary.isEmpty(), "The tracked temporary lease must remain cleanable");
            assertTrue(backend.persistent.isEmpty());
            assertEquals(1, backend.temporaryRemoves(AIRPORT));
            assertFalse(backend.latest.get(AIRPORT).isCancelled());
        }
    }

    private static TransportLoadingWindow airportWindow(ExpiringBackend backend, List<ChunkPos> pins) {
        var window = new TransportLoadingWindow(backend);
        assertTrue(window.setAirportPins(pins));
        return window;
    }

    private static boolean update(TransportLoadingWindow window) {
        return window.update(null, OWNER, BODY, HULL, NEXT);
    }

    private static List<ChunkPos> remoteChunks(int count) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) chunks.add(new ChunkPos(AIRPORT.x + i, AIRPORT.z));
        return chunks;
    }

    private enum End { CLOSE, RETARGET, REJECT, OWNER_CHANGE, BODY_CHANGE }
    private enum Admission { ALLOWED, DENIED, PERMIT_UNAVAILABLE }
    private enum ResultMode { NORMAL, FALSE, EXCEPTIONAL, THROW, EARLY_SUCCESS, NEVER_FINISH }
    private record Event(ChunkPos chunk, String operation) {}
    private static final class ColdJob {
        final ChunkPos chunk;
        final long unknownCreatedAt;
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        int generationTicks;
        ColdJob(ChunkPos chunk, long now) { this.chunk = chunk; unknownCreatedAt = now; }
    }

    private static final class ExpiringBackend implements TransportLoadingWindow.Backend {
        long now;
        boolean localAlreadyLoaded = true;
        boolean throwPersistentAdd;
        ChunkPos failingPersistentChunk;
        ResultMode mode = ResultMode.NORMAL;
        Admission admission = Admission.ALLOWED;
        int admittedCallbacks, maximumUnfinished;
        final Set<ChunkPos> visible = new HashSet<>(), alwaysFail = new HashSet<>();
        final Map<ChunkPos, UUID> persistent = new HashMap<>(), temporary = new HashMap<>();
        final Map<ChunkPos, Integer> requests = new LinkedHashMap<>();
        final Map<Long, Integer> admissionAttempts = new HashMap<>();
        final Map<ChunkPos, CompletableFuture<Boolean>> latest = new HashMap<>();
        final List<ColdJob> jobs = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        @Override public long tick() { return now; }
        @Override public boolean isLoaded(ChunkPos chunk) {
            return visible.contains(chunk) || localAlreadyLoaded && Math.abs(chunk.x) < 100 && Math.abs(chunk.z) < 100;
        }

        // Deliberately no @Override: this desired admission seam also compiles against the old Backend.
        public Optional<CompletableFuture<Boolean>> tryRequest(ChunkPos chunk, Runnable onAdmitted) {
            admissionAttempts.merge(now, 1, Integer::sum);
            if (admission != Admission.ALLOWED) return Optional.empty();
            admittedCallbacks++;
            onAdmitted.run();
            return Optional.of(request(chunk));
        }

        @Override public CompletableFuture<Boolean> request(ChunkPos chunk) {
            requests.merge(chunk, 1, Integer::sum);
            events.add(new Event(chunk, "request"));
            if (mode == ResultMode.THROW) throw new IllegalStateException("native enqueue rejected after admission");
            CompletableFuture<Boolean> result;
            if (mode == ResultMode.FALSE || alwaysFail.contains(chunk)) result = CompletableFuture.completedFuture(false);
            else if (mode == ResultMode.EXCEPTIONAL) result = CompletableFuture.failedFuture(new IllegalStateException("native failure"));
            else if (mode == ResultMode.EARLY_SUCCESS) result = CompletableFuture.completedFuture(true);
            else {
                ColdJob job = new ColdJob(chunk, now);
                jobs.add(job);
                result = job.result;
            }
            latest.put(chunk, result);
            maximumUnfinished = Math.max(maximumUnfinished, (int) jobs.stream().filter(j -> !j.result.isDone()).count());
            return result;
        }

        void advanceTo(long tick) {
            assertTrue(tick >= now);
            while (now < tick) {
                now++;
                for (ColdJob job : jobs) {
                    if (job.result.isDone()) continue;
                    boolean retained = temporary.containsKey(job.chunk) || persistent.containsKey(job.chunk);
                    if (!retained && now - job.unknownCreatedAt > 1) {
                        job.result.complete(false); // Native UNKNOWN timeout1 uses strict age>1.
                    } else if (mode != ResultMode.NEVER_FINISH && ++job.generationTicks >= 3) {
                        visible.add(job.chunk);
                        job.result.complete(true);
                    }
                }
            }
        }

        @Override public void ticket(UUID owner, ChunkPos chunk, boolean retain) {
            if (retain && throwPersistentAdd && Objects.equals(failingPersistentChunk, chunk)) {
                events.add(new Event(chunk, "persistent-throw"));
                throw new IllegalStateException("persistent handoff failed");
            }
            if (retain) persistent.put(chunk, owner); else persistent.remove(chunk);
            events.add(new Event(chunk, retain ? "persistent+" : "persistent-"));
        }

        @Override public void airportTicket(UUID owner, ChunkPos chunk, boolean retain) {
            if (retain) {
                assertFalse(temporary.containsKey(chunk), "Do not acquire the same temporary ticket twice");
                temporary.put(chunk, owner);
            } else {
                assertEquals(owner, temporary.remove(chunk), "Only the owner can remove its existing ticket");
            }
            events.add(new Event(chunk, retain ? "temporary+" : "temporary-"));
        }

        @Override public boolean structure(UUID physicalId, UUID registrationId, boolean retain) { return true; }
        List<String> eventsFor(ChunkPos chunk) { return events.stream().filter(e -> e.chunk.equals(chunk)).map(Event::operation).toList(); }
        long temporaryAdds(ChunkPos chunk) { return eventsFor(chunk).stream().filter("temporary+"::equals).count(); }
        long temporaryRemoves(ChunkPos chunk) { return eventsFor(chunk).stream().filter("temporary-"::equals).count(); }
    }
}
