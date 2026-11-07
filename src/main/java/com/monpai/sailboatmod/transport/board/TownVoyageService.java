package com.monpai.sailboatmod.transport.board;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Server-session arrivals read model. Never participates in navigation authority. */
public final class TownVoyageService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<MinecraftServer, TownVoyageService> SERVICES = new IdentityHashMap<>();
    private final Index index;
    private final TownVoyageSavedData data;
    private final AirJobs air;
    private long persistedVersion = -1;
    private boolean closed;

    private TownVoyageService(TownVoyageSavedData data) {
        this.data = data;
        index = new Index();
        index.restore(data.observations(), data.endpoints());
        for (Endpoint endpoint : data.unavailableEndpoints()) index.markEndpointRemoved(endpoint.dimensionId(), endpoint.terminalPos());
        air = new AirJobs(index, AirJobs.executor(), TownVoyageEta::air);
    }
    /** Called only from normal ServerStarted; reads board-owned SavedData once. */
    public static void start(MinecraftServer server) {
        if (server == null) return;
        synchronized (SERVICES) {
            if (SERVICES.containsKey(server)) return;
            try {
                TownVoyageSavedData data = server.overworld().getDataStorage().computeIfAbsent(TownVoyageSavedData.FACTORY, TownVoyageSavedData.DATA_NAME);
                TownVoyageService service = new TownVoyageService(data);
                SERVICES.put(server, service);
                TownVoyagePublisher.drain(server, service.index);
            } catch (RuntimeException failure) {
                LOGGER.warn("Town arrivals read model could not initialize; navigation remains independent");
            }
        }
    }
    public static Optional<TownVoyageService> existing(MinecraftServer server) {
        synchronized (SERVICES) { return Optional.ofNullable(SERVICES.get(server)); }
    }
    public static void tick(MinecraftServer server) {
        existing(server).ifPresent(service -> {
            if (service.closed) return;
            long now = Math.max(0, server.overworld().getGameTime());
            service.index.clockSuspended(server.tickRateManager().isFrozen(), now);
            service.air.drain(now);
            service.index.refresh(now);
            if (now % 20 == 0) service.persist();
        });
    }
    public static void stopping(MinecraftServer server) {
        TownVoyageService service;
        synchronized (SERVICES) { service = SERVICES.remove(server); }
        if (service != null) {
            service.closed = true;
            service.persist();
            service.air.close();
            service.index.clear();
        }
        TownVoyagePublisher.discard(server);
    }
    public static void stopped(MinecraftServer server) { stopping(server); }
    private void persist() {
        if (persistedVersion == index.changeVersion) return;
        data.replace(index.observations(), index.endpoints(), index.unavailableEndpoints());
        persistedVersion = index.changeVersion;
    }
    public void publish(Observation observation, long now) { if (!closed) index.publish(observation, now); }
    public void retire(CarrierKey carrier, UUID leg) { if (!closed) index.retire(carrier, leg); }
    public void markUnloaded(CarrierKey carrier) { if (!closed) index.markUnloaded(carrier); }
    public Optional<Key> currentKey(CarrierKey carrier) { return closed ? Optional.empty() : index.currentKey(carrier); }
    /** Shared read-only ETA for an exact, currently observed destination. */
    public OptionalLong remainingEta(CarrierKey carrier, String dimension, long pos, long now) {
        return closed ? OptionalLong.empty() : index.remainingEta(carrier, dimension, pos, now);
    }
    public void observeEndpoint(Endpoint endpoint, boolean loaded) { if (!closed) index.observeEndpoint(endpoint, loaded); }
    public void markEndpointUnloaded(String dim, long pos) { if (!closed) index.markEndpointUnloaded(dim, pos); }
    public void markEndpointRemoved(String dim, long pos) { if (!closed) index.markEndpointRemoved(dim, pos); }
    public Endpoint endpoint(String dim, long pos, String name) { return closed ? Endpoint.unknown() : index.endpoint(dim, pos, name); }
    public BoardView view(String town, Filter filter, int page, long now) { return closed ? empty(filter, now) : index.view(town, filter, page, now); }
    public void requestAirEta(Key key, String geometry, RailAircraftPath path, double distance, int stand, long now) {
        if (!closed) air.request(key, geometry, path, distance, stand, now);
    }
    public boolean reserveGroundGeometry(CarrierKey carrier, String geometry, int points) { return !closed && index.reserveGroundGeometry(carrier, geometry, points); }
    public void releaseGroundGeometry(CarrierKey carrier) { index.releaseGroundGeometry(carrier); }
    private static BoardView empty(Filter filter, long now) { return new BoardView(0, Math.max(0, now), filter == null ? Filter.ALL : filter, 0, 1, 0, true, List.of()); }
    private static boolean advancing(State state) { return state == State.MOVING || state == State.DWELLING || state == State.ESTIMATING || state == State.ETA_UNKNOWN; }
    private static Observation copy(Observation o, Endpoint source, Endpoint target, State state, boolean loaded, OptionalLong eta, long etaTick) {
        return new Observation(o.key(), o.kind(), o.carrierName(), o.routeName(), source, target, o.phase(), state, loaded,
                o.observedTick(), o.heartbeatTicks(), eta, etaTick, o.geometryId());
    }
    private record Address(String dimension, long pos) {
        static Address of(Endpoint endpoint) { return new Address(endpoint.dimensionId(), endpoint.terminalPos()); }
        static Address valid(String dimension, long pos) { return of(new Endpoint(dimension, pos, "", "Unknown")); }
    }

    /** Pure bounded index, independent of Minecraft servers and entity loading. */
    public static final class Index {
        private static final int MAX_CARRIERS = 4096, MAX_ENDPOINTS = 8192, MAX_TOWNS = 256, MAX_TOWN_ROWS = 64, MAX_GROUND_POINTS = 262144;
        private static final long INTEREST_TICKS = 1200;
        private static final class Entry {
            Observation observation;
            boolean seen, clockEvidenceRequired;
            long generation, etaFloor;
            Entry(Observation observation, boolean seen, long generation, long etaFloor) {
                this.observation = observation; this.seen = seen; this.generation = generation; this.etaFloor = etaFloor;
            }
        }
        private record Place(Endpoint endpoint, boolean loaded, boolean removed) { }
        private record Reservation(String geometry, int points) { }
        private record Projection(Map<Filter, List<BoardView>> filters, Map<String, Long> airExpiry, long nextAirExpiry) { }
        private static final Comparator<PublicRow> ROW_ORDER = Comparator.comparing((PublicRow row) -> row.etaTicks().isEmpty())
                .thenComparingLong(row -> row.etaTicks().orElse(Long.MAX_VALUE)).thenComparing(PublicRow::rowId);
        private final Map<CarrierKey, Entry> carriers = new LinkedHashMap<>();
        private final Map<String, Set<CarrierKey>> towns = new HashMap<>();
        private final Map<Address, Set<CarrierKey>> references = new HashMap<>();
        private final Map<Address, Place> places = new LinkedHashMap<>();
        private final Map<CarrierKey, Reservation> ground = new HashMap<>();
        private final LinkedHashMap<String, Long> interests = new LinkedHashMap<>();
        private final Map<String, Projection> projections = new HashMap<>();
        private long lastRefresh = -1, revision, aggregationCount, nextGeneration, changeVersion;
        private int groundPoints;
        private boolean clockSuspended;

        public void publish(Observation input, long now) {
            if (input == null || now < 0 || input.observedTick() > now) return;
            CarrierKey carrier = input.key().carrier();
            Entry old = carriers.get(carrier);
            if (old == null && carriers.size() >= MAX_CARRIERS || old != null && old.seen && input.observedTick() < old.observation.observedTick()) return;
            boolean sameLeg = old != null && old.observation.key().equals(input.key());
            Endpoint source = canonical(input.source(), sameLeg ? old.observation.source() : Endpoint.unknown());
            Endpoint target = canonical(input.target(), sameLeg ? old.observation.target() : Endpoint.unknown());
            Observation observed = copy(input, source, target, input.state(), input.loaded(), input.etaTicks(), input.etaObservedTick());
            boolean sameGeometry = sameLeg && old.observation.geometryId().equals(observed.geometryId());
            boolean oldEligible = sameGeometry && usable(old, now);
            boolean newEligible = !clockSuspended && observed.loaded() && advancing(observed.state()) && fresh(observed, now) && !unavailable(observed);
            boolean explicitUnknown = observed.state() == State.ESTIMATING || observed.state() == State.ETA_UNKNOWN;
            boolean barrier = !sameGeometry || !oldEligible || !newEligible || explicitUnknown;
            long generation = barrier ? ++nextGeneration : old.generation;
            long floor = old == null || !sameGeometry || !old.seen && !old.clockEvidenceRequired
                    ? observed.observedTick() : barrier ? Math.max(old.etaFloor, now) : old.etaFloor;
            if (clockSuspended) floor = Math.max(floor, nextTick(now));
            OptionalLong eta = observed.etaTicks(); long etaTick = observed.etaObservedTick();
            if (!newEligible || explicitUnknown || etaTick < floor || etaTick > now || etaTick > observed.observedTick()) { eta = OptionalLong.empty(); etaTick = -1; }
            // Air heartbeats carry progress without calculating ETA; retain a recent, uninterrupted estimate.
            if (eta.isEmpty() && observed.kind() == com.monpai.sailboatmod.transport.LogisticsCarrier.Kind.AIR && newEligible && !barrier && old.observation.etaTicks().isPresent()
                    && old.observation.etaObservedTick() >= floor && now - old.observation.etaObservedTick() <= 100) {
                eta = old.observation.etaTicks(); etaTick = old.observation.etaObservedTick();
            }
            State state = !observed.loaded() ? State.UNLOADED : !fresh(observed, now) ? State.STALE : observed.state();
            if (advancing(state) && !newEligible) state = State.ETA_UNKNOWN;
            if (old != null) removeIndexes(carrier, old.observation);
            Entry current = new Entry(copy(observed, source, target, state, observed.loaded(), eta, etaTick), !clockSuspended, generation, floor);
            current.clockEvidenceRequired = clockSuspended;
            carriers.put(carrier, current);
            addIndexes(carrier, current.observation);
            changeVersion++;
        }
        public void restore(List<Observation> rows, List<Endpoint> endpoints) {
            clear();
            if (endpoints != null) for (int n = 0; n < Math.min(MAX_ENDPOINTS, endpoints.size()); n++) observeEndpoint(endpoints.get(n), false);
            if (rows != null) for (int n = 0; n < Math.min(MAX_CARRIERS, rows.size()); n++) {
                Observation o = rows.get(n);
                if (o == null) continue;
                Observation restored = copy(o, o.source(), o.target(), State.UNLOADED, false, OptionalLong.empty(), -1);
                publish(restored, restored.observedTick());
                Entry entry = carriers.get(o.key().carrier());
                if (entry != null) entry.seen = false;
            }
        }
        public void retire(CarrierKey carrier, UUID leg) {
            Entry current = carriers.get(carrier);
            if (current == null || !current.observation.key().legId().equals(leg)) return;
            carriers.remove(carrier); removeIndexes(carrier, current.observation); releaseGroundGeometry(carrier); changeVersion++;
        }
        public void markUnloaded(CarrierKey carrier) {
            releaseGroundGeometry(carrier);
            Entry current = carriers.get(carrier);
            if (current == null) return;
            invalidate(current, State.UNLOADED, false, current.observation.observedTick()); changeVersion++;
        }
        public Optional<Key> currentKey(CarrierKey carrier) { Entry current = carriers.get(carrier); return current == null ? Optional.empty() : Optional.of(current.observation.key()); }
        OptionalLong remainingEta(CarrierKey carrier, String dimension, long pos, long now) {
            Entry entry = carriers.get(carrier);
            if (entry == null || !usable(entry, now)) return OptionalLong.empty();
            Observation observation = entry.observation;
            Endpoint target = observation.target();
            if (observation.state() != State.MOVING || !target.dimensionId().equals(dimension) || target.terminalPos() != pos
                    || observation.etaTicks().isEmpty() || observation.etaObservedTick() < 0
                    || observation.etaObservedTick() > now) return OptionalLong.empty();
            return boundedEta(OptionalLong.of(observation.etaTicks().getAsLong() - (now - observation.etaObservedTick())));
        }
        public void observeEndpoint(Endpoint endpoint, boolean loaded) {
            if (endpoint == null || endpoint.dimensionId().isEmpty()) return;
            Address address = Address.of(endpoint);
            Place old = places.get(address);
            Place next = new Place(endpoint, loaded, false);
            if (next.equals(old)) return;
            if (old == null && !makePlaceRoom()) return;
            places.put(address, next);
            for (CarrierKey carrier : List.copyOf(references.getOrDefault(address, Set.of()))) {
                Entry current = carriers.get(carrier); if (current == null) continue;
                Observation o = current.observation;
                Endpoint source = Address.of(o.source()).equals(address) ? endpoint : o.source();
                Endpoint target = Address.of(o.target()).equals(address) ? endpoint : o.target();
                removeIndexes(carrier, o);
                current.observation = copy(o, source, target, o.state(), o.loaded(), o.etaTicks(), o.etaObservedTick());
                addIndexes(carrier, current.observation);
            }
            if (old == null || !old.equals(places.get(address))) changeVersion++;
        }
        public void markEndpointUnloaded(String dim, long pos) {
            Address address = address(dim, pos); if (address == null) return;
            Place old = places.get(address);
            if (old != null && old.loaded()) { places.put(address, new Place(old.endpoint(), false, old.removed())); changeVersion++; }
        }
        public void markEndpointRemoved(String dim, long pos) {
            Address address = address(dim, pos); if (address == null) return;
            Place old = places.get(address);
            // A full metadata catalog must never prevent invalidating current carrier trust.
            for (CarrierKey carrier : references.getOrDefault(address, Set.of())) {
                Entry current = carriers.get(carrier);
                if (current != null) invalidate(current, advancing(current.observation.state()) ? State.ETA_UNKNOWN : current.observation.state(), current.observation.loaded(), current.observation.observedTick());
            }
            changeVersion++;
            if (old == null && !makePlaceRoom()) return;
            Endpoint endpoint = old == null ? new Endpoint(dim, pos, "", "Unavailable") : old.endpoint();
            places.put(address, new Place(endpoint, false, true));
        }
        /** Startup authority overflow invalidates restored catalog trust before replaying fresh metadata. */
        void invalidateCatalog() {
            places.replaceAll((address, place) -> new Place(place.endpoint(), false, true));
            for (Entry entry : carriers.values()) {
                Observation o = entry.observation;
                invalidate(entry, advancing(o.state()) ? State.ETA_UNKNOWN : o.state(), o.loaded(), o.observedTick());
                entry.seen = false;
            }
            projections.clear(); lastRefresh = -1; changeVersion++;
        }
        public Endpoint endpoint(String dim, long pos, String name) {
            Address address = address(dim, pos); if (address == null) return Endpoint.unknown();
            Place place = places.get(address);
            return place == null ? new Endpoint(dim, pos, "", name) : place.endpoint();
        }
        public BoardView view(String town, Filter filter, int page, long now) {
            Filter safeFilter = filter == null ? Filter.ALL : filter;
            if (!validTown(town) || now < 0) return empty(safeFilter, now);
            if (!interests.containsKey(town) && interests.size() >= MAX_TOWNS) {
                String oldest = interests.keySet().iterator().next(); interests.remove(oldest); projections.remove(oldest);
            }
            interests.put(town, now);
            Projection projection = projections.get(town);
            if (projection == null) return empty(safeFilter, now);
            if (now > projection.nextAirExpiry()) {
                projection = expireProjection(projection, now);
                projections.put(town, projection);
            }
            List<BoardView> pages = projection.filters().get(safeFilter);
            return pages.get(Math.clamp(page, 0, pages.size() - 1));
        }
        /** All board consumers reuse one immutable town aggregation in each 20-tick interval. */
        public void clockSuspended(boolean suspended, long now) {
            if (clockSuspended == suspended || now < 0) return;
            clockSuspended = suspended;
            for (Entry entry : carriers.values()) {
                Observation o = entry.observation;
                invalidate(entry, advancing(o.state()) ? State.ETA_UNKNOWN : o.state(), o.loaded(), now);
                entry.seen = false;
                entry.clockEvidenceRequired = true;
                entry.etaFloor = nextTick(now);
            }
            // Freeze transitions must update already visible screens even if game time did not advance.
            lastRefresh = -1;
            changeVersion++;
        }
        private static long nextTick(long tick) { return tick == Long.MAX_VALUE ? Long.MAX_VALUE : tick + 1; }
        public void refresh(long now) {
            if (now < 0 || lastRefresh >= 0 && now - lastRefresh < 20) return;
            lastRefresh = now; revision++;
            for (Iterator<Map.Entry<String, Long>> it = interests.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Long> interest = it.next();
                if (now - interest.getValue() > INTEREST_TICKS) { projections.remove(interest.getKey()); it.remove(); continue; }
                String town = interest.getKey(); List<PublicRow> rows = new ArrayList<>();
                Map<String, Long> observedAirExpiry = new HashMap<>();
                Set<CarrierKey> townCarriers = towns.getOrDefault(town, Set.of());
                for (CarrierKey carrier : townCarriers) {
                    if (carrier.providerId().equals(CREATE_RAIL_PROVIDER) && carrier.scopeId().equals("global")
                            && townCarriers.contains(new CarrierKey(CREATE_RAIL_PROVIDER, RAIL_FREIGHT_SCOPE, carrier.carrierId())))
                        continue;
                    Entry current = carriers.get(carrier); if (current == null) continue;
                    if (current.seen && current.observation.loaded() && !fresh(current.observation, now) && current.observation.state() != State.STALE) {
                        invalidate(current, State.STALE, true, now); changeVersion++;
                    }
                    Observation o = current.observation;
                    if (o.kind() == com.monpai.sailboatmod.transport.LogisticsCarrier.Kind.AIR && o.etaTicks().isPresent()
                            && (o.etaObservedTick() > now || now - o.etaObservedTick() > 100)) {
                        // Expiring an old estimate does not invalidate a newer, still-valid in-flight calculation.
                        current.observation = copy(o, o.source(), o.target(), advancing(o.state()) ? State.ETA_UNKNOWN : o.state(), o.loaded(), OptionalLong.empty(), -1);
                        o = current.observation; changeVersion++;
                    }
                    if (!usable(current, now) && o.etaTicks().isPresent()) o = copy(o, o.source(), o.target(), o.state(), o.loaded(), OptionalLong.empty(), -1);
                    Optional<PublicRow> projected = TownVoyageSnapshot.forTown(o, town);
                    if (projected.isPresent()) {
                        // Wire ETA is the remaining duration at this projection's ObservedTick.
                        PublicRow row = projected.get().afterTicks(now - Math.max(0, o.etaObservedTick()));
                        rows.add(row);
                        if (row.kind() == com.monpai.sailboatmod.transport.LogisticsCarrier.Kind.AIR && row.etaTicks().isPresent())
                            observedAirExpiry.put(row.rowId(), o.etaObservedTick() > Long.MAX_VALUE - 100 ? Long.MAX_VALUE : o.etaObservedTick() + 100);
                    }
                }
                rows.sort(ROW_ORDER);
                EnumMap<Filter, List<BoardView>> filters = new EnumMap<>(Filter.class);
                Map<String, Long> cachedAirExpiry = new HashMap<>();
                for (Filter filter : Filter.values()) {
                    List<PublicRow> matching = rows.stream().filter(row -> filter == Filter.ALL || row.kind().name().equals(filter.name())).toList();
                    int overflow = Math.max(0, matching.size() - MAX_TOWN_ROWS);
                    List<PublicRow> selected = matching.subList(0, Math.min(MAX_TOWN_ROWS, matching.size()));
                    for (PublicRow row : selected) {
                        Long expiry = observedAirExpiry.get(row.rowId());
                        if (expiry != null) cachedAirExpiry.put(row.rowId(), expiry);
                    }
                    filters.put(filter, pages(selected, filter, overflow, revision, now));
                }
                projections.put(town, projection(filters, cachedAirExpiry)); aggregationCount++;
            }
        }
        private static Projection projection(EnumMap<Filter, List<BoardView>> filters, Map<String, Long> expiry) {
            return new Projection(Map.copyOf(filters), Map.copyOf(expiry), expiry.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE));
        }
        private static List<BoardView> pages(List<PublicRow> rows, Filter filter, int overflow, long revision, long now) {
            int count = Math.max(1, (rows.size() + MAX_ROWS - 1) / MAX_ROWS);
            List<BoardView> views = new ArrayList<>(count);
            for (int page = 0; page < count; page++) views.add(new BoardView(revision, now, filter, page, count, overflow, false,
                    rows.subList(page * MAX_ROWS, Math.min(rows.size(), (page + 1) * MAX_ROWS))));
            return List.copyOf(views);
        }
        /** Scrub only cached public rows between normal aggregations; no carrier, endpoint, or worker access. */
        private Projection expireProjection(Projection old, long now) {
            Map<String, Long> remaining = new HashMap<>();
            old.airExpiry().forEach((rowId, expiry) -> { if (now <= expiry) remaining.put(rowId, expiry); });
            EnumMap<Filter, List<BoardView>> filters = new EnumMap<>(Filter.class);
            long nextRevision = ++revision;
            for (Filter filter : Filter.values()) {
                List<BoardView> previous = old.filters().get(filter);
                List<PublicRow> rows = new ArrayList<>();
                for (BoardView page : previous) for (PublicRow row : page.rows()) {
                    Long expiry = old.airExpiry().get(row.rowId());
                    if (expiry != null && now > expiry) row = new PublicRow(row.rowId(), row.kind(), row.carrierName(), row.routeName(),
                            row.sourceName(), row.targetName(), row.relation(), row.publicPhase(),
                            advancing(row.state()) ? State.ETA_UNKNOWN : row.state(), OptionalLong.empty());
                    rows.add(row.afterTicks(now - page.observedTick()));
                }
                rows.sort(ROW_ORDER);
                filters.put(filter, pages(rows, filter, previous.getFirst().overflow(), nextRevision, now));
            }
            return projection(filters, remaining);
        }
        public List<Observation> observations() { return carriers.values().stream().map(entry -> entry.observation).toList(); }
        public List<Endpoint> endpoints() { return places.values().stream().map(Place::endpoint).toList(); }
        List<Endpoint> unavailableEndpoints() { return places.values().stream().filter(Place::removed).map(Place::endpoint).toList(); }
        public boolean reserveGroundGeometry(CarrierKey carrier, String geometry, int points) {
            if (carrier == null || points < 2 || points > TownVoyageEta.MAX_PATH_POINTS || !validGeometry(geometry)) return false;
            Reservation old = ground.get(carrier);
            if (old == null && ground.size() >= MAX_CARRIERS) return false;
            int next = groundPoints - (old == null ? 0 : old.points()) + points;
            if (next > MAX_GROUND_POINTS) return false;
            ground.put(carrier, new Reservation(geometry, points)); groundPoints = next; return true;
        }
        public void releaseGroundGeometry(CarrierKey carrier) { Reservation old = ground.remove(carrier); if (old != null) groundPoints -= old.points(); }
        int groundPoints() { return groundPoints; }
        long aggregationCount() { return aggregationCount; }
        long generation(Key key) { Entry entry = key == null ? null : carriers.get(key.carrier()); return entry != null && entry.observation.key().equals(key) ? entry.generation : -1; }
        boolean eligible(Key key, String geometry, long now) {
            Entry entry = key == null ? null : carriers.get(key.carrier());
            return entry != null && entry.observation.key().equals(key) && entry.observation.kind() == com.monpai.sailboatmod.transport.LogisticsCarrier.Kind.AIR
                    && entry.observation.geometryId().equals(geometry) && usable(entry, now);
        }
        boolean applyEta(Key key, String geometry, long generation, long submitted, OptionalLong eta, long now) {
            if (!eligible(key, geometry, now) || submitted > now || now - submitted > 100 || generation(key) != generation) return false;
            Entry entry = carriers.get(key.carrier());
            if (submitted < entry.etaFloor) return false;
            OptionalLong bounded = TownVoyageSnapshot.boundedEta(eta);
            Observation o = entry.observation;
            entry.observation = copy(o, o.source(), o.target(), bounded.isPresent() ? State.MOVING : State.ETA_UNKNOWN, true, bounded, bounded.isPresent() ? submitted : -1);
            changeVersion++; return true;
        }
        void estimating(Key key) {
            Entry entry = key == null ? null : carriers.get(key.carrier());
            if (entry == null || !entry.observation.key().equals(key) || !advancing(entry.observation.state()) || entry.observation.etaTicks().isPresent()) return;
            Observation o = entry.observation;
            entry.observation = copy(o, o.source(), o.target(), State.ESTIMATING, o.loaded(), OptionalLong.empty(), -1); changeVersion++;
        }
        void unknown(Key key, long now, State state) {
            Entry entry = key == null ? null : carriers.get(key.carrier());
            if (entry == null || !entry.observation.key().equals(key) || !advancing(entry.observation.state())) return;
            invalidate(entry, state, entry.observation.loaded(), now); changeVersion++;
        }
        private boolean usable(Entry entry, long now) { Observation o = entry.observation; return !clockSuspended && entry.seen && o.loaded() && advancing(o.state()) && fresh(o, now) && !unavailable(o); }
        private static boolean fresh(Observation o, long now) { return now >= o.observedTick() && now - o.observedTick() <= Math.max(60L, 3L * o.heartbeatTicks()); }
        private boolean unavailable(Observation o) { return unavailable(o.source()) || unavailable(o.target()); }
        private boolean unavailable(Endpoint endpoint) {
            if (endpoint.dimensionId().isEmpty()) return false;
            Place place = places.get(Address.of(endpoint));
            // Only normal endpoint observations (or their persisted catalog) establish availability.
            return place == null || place.removed();
        }
        private Endpoint canonical(Endpoint incoming, Endpoint previous) {
            Endpoint supplied = incoming.dimensionId().isEmpty() && !previous.dimensionId().isEmpty() ? previous : incoming;
            Place place = places.get(Address.of(supplied));
            if (place != null) return place.endpoint();
            // Missing catalog data cannot erase same-leg last-known town/name or establish availability.
            if (supplied.townId().isEmpty() && Address.of(supplied).equals(Address.of(previous))) return previous;
            return supplied;
        }
        private boolean makePlaceRoom() {
            if (places.size() < MAX_ENDPOINTS) return true;
            for (Iterator<Address> it = places.keySet().iterator(); it.hasNext();) {
                Address candidate = it.next();
                if (!references.containsKey(candidate) && !places.get(candidate).removed()) { it.remove(); return true; }
            }
            return false;
        }
        private void invalidate(Entry entry, State state, boolean loaded, long now) {
            Observation o = entry.observation;
            entry.observation = copy(o, o.source(), o.target(), state, loaded, OptionalLong.empty(), -1);
            entry.generation = ++nextGeneration; entry.etaFloor = Math.max(entry.etaFloor, now);
        }
        private static boolean validTown(String town) {
            try { return !TownVoyageSnapshot.identity(town, 128, false).isEmpty(); } catch (IllegalArgumentException ignored) { return false; }
        }
        private static boolean validGeometry(String geometry) {
            try { TownVoyageSnapshot.identity(geometry, 256, false); return true; } catch (IllegalArgumentException ignored) { return false; }
        }
        private static Address address(String dim, long pos) {
            try { return dim == null || dim.isEmpty() ? null : Address.valid(dim, pos); } catch (IllegalArgumentException ignored) { return null; }
        }
        private void addIndexes(CarrierKey carrier, Observation o) {
            add(towns, o.source().townId(), carrier); add(towns, o.target().townId(), carrier);
            if (!o.source().dimensionId().isEmpty()) add(references, Address.of(o.source()), carrier);
            if (!o.target().dimensionId().isEmpty()) add(references, Address.of(o.target()), carrier);
        }
        private void removeIndexes(CarrierKey carrier, Observation o) {
            remove(towns, o.source().townId(), carrier); remove(towns, o.target().townId(), carrier);
            remove(references, Address.of(o.source()), carrier); remove(references, Address.of(o.target()), carrier);
        }
        private static <K> void add(Map<K, Set<CarrierKey>> map, K key, CarrierKey carrier) {
            if (key instanceof String text && text.isEmpty()) return;
            map.computeIfAbsent(key, unused -> new HashSet<>()).add(carrier);
        }
        private static <K> void remove(Map<K, Set<CarrierKey>> map, K key, CarrierKey carrier) {
            Set<CarrierKey> values = map.get(key); if (values == null) return;
            values.remove(carrier); if (values.isEmpty()) map.remove(key);
        }
        private void clear() {
            carriers.clear(); towns.clear(); references.clear(); places.clear(); interests.clear(); projections.clear(); ground.clear();
            groundPoints = 0; lastRefresh = -1; changeVersion++;
        }
    }

    @FunctionalInterface interface AirEstimator { OptionalLong estimate(RailAircraftPath path, double distance, int stand); }
    /** Worker only receives immutable path/progress values. Completion is applied exclusively by drain. */
    static final class AirJobs {
        private record CacheKey(Key key, String geometry, double distance, int stand) { }
        private record Job(UUID session, CacheKey key, RailAircraftPath path, long generation, long submitted) { }
        private record Completion(Job job, OptionalLong eta) { }
        private record Cached(UUID session, long generation, long submitted, OptionalLong eta) { }
        private final UUID session = UUID.randomUUID();
        private final Index index;
        private final ExecutorService executor;
        private final AirEstimator estimator;
        private final Map<CarrierKey, Job> active = new HashMap<>();
        private final LinkedHashMap<CarrierKey, Long> attempts = new LinkedHashMap<>();
        private final ArrayBlockingQueue<Completion> completed = new ArrayBlockingQueue<>(9);
        private final LinkedHashMap<CacheKey, Cached> cache = new LinkedHashMap<>();
        private volatile boolean closed;
        AirJobs(Index index, ExecutorService executor, AirEstimator estimator) {
            this.index = Objects.requireNonNull(index); this.executor = Objects.requireNonNull(executor); this.estimator = Objects.requireNonNull(estimator);
        }
        static ThreadPoolExecutor executor() {
            return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "sailboatmod-town-arrivals-eta"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
        void request(Key key, String geometry, RailAircraftPath path, double distance, int stand, long now) {
            if (closed || key == null || now < 0 || !index.eligible(key, geometry, now)) return;
            if (path == null || !Double.isFinite(distance) || distance < 0 || distance > path.length()
                    || stand < 0 || stand > 40 || stand > 0 && Double.compare(distance, path.length()) != 0 || !path.fingerprint().equals(geometry)) {
                index.unknown(key, now, State.ETA_UNKNOWN); return;
            }
            CarrierKey carrier = key.carrier();
            if (active.containsKey(carrier)) return;
            Long last = attempts.get(carrier); if (last != null && (now < last || now - last < 40)) return;
            if (!attempts.containsKey(carrier) && attempts.size() >= 4096) attempts.remove(attempts.keySet().iterator().next());
            attempts.put(carrier, now);
            CacheKey cacheKey = new CacheKey(key, geometry, distance, stand);
            Cached hit = cache.get(cacheKey);
            if (hit != null && hit.session().equals(session)
                    && index.applyEta(key, geometry, hit.generation(), hit.submitted(), hit.eta(), now)) return;
            index.estimating(key);
            if (active.size() >= 9) { index.unknown(key, now, State.ESTIMATING); return; }
            Job job = new Job(session, cacheKey, path, index.generation(key), now);
            active.put(carrier, job);
            try {
                executor.execute(() -> {
                    if (closed) return;
                    OptionalLong eta;
                    try { eta = TownVoyageSnapshot.boundedEta(estimator.estimate(job.path(), job.key().distance(), job.key().stand())); }
                    catch (RuntimeException ignored) { eta = OptionalLong.empty(); }
                    if (!closed) completed.offer(new Completion(job, eta));
                });
            } catch (RejectedExecutionException rejected) { active.remove(carrier, job); index.unknown(key, now, State.ESTIMATING); }
        }
        /** At most nine completions can exist, so a tick never performs an unbounded drain. */
        void drain(long now) {
            if (closed) return;
            for (int n = 0; n < 9; n++) {
                Completion completion = completed.poll(); if (completion == null) break;
                Job job = completion.job();
                if (!active.remove(job.key().key().carrier(), job) || !job.session().equals(session)) continue;
                if (!index.applyEta(job.key().key(), job.key().geometry(), job.generation(), job.submitted(), completion.eta(), now)) continue;
                if (completion.eta().isPresent()) {
                    cache.put(job.key(), new Cached(session, job.generation(), job.submitted(), completion.eta()));
                    if (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
                }
            }
        }
        void close() {
            closed = true; active.clear(); attempts.clear(); completed.clear(); cache.clear(); executor.shutdownNow();
        }
        int inFlight() { return active.size(); }
        int cacheSize() { return cache.size(); }
    }
}
