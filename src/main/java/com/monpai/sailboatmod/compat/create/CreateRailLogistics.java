package com.monpai.sailboatmod.compat.create;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.market.logistics.ShippingTraceSavedData;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.logistics.rail.RailTraceTracker;
import com.monpai.sailboatmod.market.logistics.rail.RailTrainSnapshot;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.board.TownVoyageEta;
import com.monpai.sailboatmod.transport.board.TownVoyageService;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.*;

/**
 * Shows Create trains on the web map as rail shipments: every train that runs a schedule or is navigating, with its
 * route on the current leg, its status (in transit, at a station, stuck), timeline and history like boats and carriages.
 * The same native read feeds the public Town arrivals board without reading any shipment business data.
 * Updated once a second from the server tick. Does nothing when Create is not installed.
 */
public final class CreateRailLogistics {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "create";
    private static final int INTERVAL_TICKS = 20;
    static final int MAX_BOARD_TRAINS = 4096;
    private static final int MAX_STATION_ENDPOINTS = 8192;

    private static RailTraceTracker tracker = new RailTraceTracker();
    private static final BoardTracker boardTracker = new BoardTracker();
    private static MinecraftServer boardServer;
    private static Boolean available;
    private static boolean primed;

    private CreateRailLogistics() {
    }

    /** Names are labels; native identity includes the exact dimension and terminal position. */
    record Station(UUID nativeId, String dimensionId, long terminalPos, String displayName) {
        private StationIdentity identity() { return new StationIdentity(nativeId, dimensionId, terminalPos); }
        private boolean located() { return dimensionId != null && !dimensionId.isEmpty(); }
    }
    private record StationIdentity(UUID nativeId, String dimensionId, long terminalPos) { }
    private record Address(String dimensionId, long terminalPos) { }
    record BoardSample(UUID trainId, String carrierName, Station currentStation, Station destination,
                       double remainingTrackBlocks, double speedBlocksPerTick, boolean paused, boolean derailed,
                       boolean waitingForSignal, boolean waitingForChunks, boolean graphAvailable,
                       boolean navigationActive, boolean invalid) { }
    record Readout(List<RailTrainSnapshot> webTrains, List<BoardSample> boardTrains, boolean complete,
                   Set<UUID> confirmedMissingTrackedIds) {
        Readout {
            webTrains = List.copyOf(webTrains); boardTrains = List.copyOf(boardTrains);
            confirmedMissingTrackedIds = Set.copyOf(confirmedMissingTrackedIds);
        }
    }
    @FunctionalInterface interface EndpointResolver {
        Endpoint resolve(Station station, Endpoint previous);
    }
    interface Sink {
        void publish(Observation observation);
        void retireCurrent(CarrierKey carrier);
        void markUnloaded(CarrierKey carrier);
    }

    /** Pure observational state. No Create, server, entity, chunk or business objects are retained here. */
    static final class BoardTracker {
        private static final class Tracked {
            Station lastStation, source, target;
            UUID leg;
            String geometry = UUID.randomUUID().toString();
            double initialRemaining = Double.NaN, previousRemaining = Double.NaN;
            final List<TownVoyageEta.ProgressSample> motion = new ArrayList<>();
            void clearMotion() {
                motion.clear(); initialRemaining = Double.NaN; previousRemaining = Double.NaN;
            }
            void newGeometry() { geometry = UUID.randomUUID().toString(); clearMotion(); }
            void finish() { leg = null; source = null; target = null; clearMotion(); }
        }
        private final int capacity;
        private final Map<UUID, Tracked> trains = new LinkedHashMap<>();
        private final LinkedHashMap<Address, Endpoint> endpoints = new LinkedHashMap<>(16, .75F, true);
        BoardTracker() { this(MAX_BOARD_TRAINS); }
        BoardTracker(int capacity) {
            if (capacity < 1 || capacity > MAX_BOARD_TRAINS) throw new IllegalArgumentException("invalid_board_capacity");
            this.capacity = capacity;
        }
        void scan(List<BoardSample> samples, boolean complete, long tick, EndpointResolver resolver, Sink sink) {
            scan(samples, complete, Set.of(), tick, resolver, sink);
        }
        void scan(List<BoardSample> samples, boolean complete, Set<UUID> confirmedMissingTrackedIds,
                  long tick, EndpointResolver resolver, Sink sink) {
            if (samples == null || tick < 0) return;
            Set<UUID> seen = new HashSet<>();
            Map<StationIdentity, Station> nativeStations = new HashMap<>();
            for (BoardSample sample : samples) {
                if (sample == null || sample.trainId() == null) continue;
                if (seen.contains(sample.trainId())) continue;
                if (seen.size() < capacity) {
                    seen.add(sample.trainId());
                    // Bounded native metadata takes precedence over remembered departure labels.
                    if (sample.currentStation() != null) nativeStations.put(sample.currentStation().identity(), sample.currentStation());
                    if (sample.destination() != null) nativeStations.put(sample.destination().identity(), sample.destination());
                }
                else complete = false;
            }
            for (Iterator<Map.Entry<UUID, Tracked>> it = trains.entrySet().iterator(); it.hasNext();) {
                var entry = it.next();
                Tracked train = entry.getValue();
                train.lastStation = refreshedStation(train.lastStation, nativeStations);
                train.source = refreshedStation(train.source, nativeStations);
                train.target = refreshedStation(train.target, nativeStations);
                if (seen.contains(entry.getKey())) continue;
                if (complete || confirmedMissingTrackedIds.contains(entry.getKey())) {
                    sink.retireCurrent(carrier(entry.getKey())); it.remove();
                } else if (train.leg != null) {
                    train.clearMotion();
                    sink.markUnloaded(carrier(entry.getKey()));
                }
            }
            Map<Address, Endpoint> refreshed = new HashMap<>();
            Set<UUID> processed = new HashSet<>();
            for (BoardSample sample : samples) {
                if (sample == null || sample.trainId() == null || !seen.contains(sample.trainId()) || !processed.add(sample.trainId())) continue;
                Tracked train = trains.get(sample.trainId());
                if (train == null) {
                    if (sample.invalid() || trains.size() >= capacity) continue;
                    trains.put(sample.trainId(), train = new Tracked());
                }
                if (sample.currentStation() != null) train.lastStation = refreshedStation(sample.currentStation(), nativeStations);
                CarrierKey carrier = carrier(sample.trainId());
                if (!sample.navigationActive() || sample.destination() == null || same(sample.currentStation(), sample.destination())) {
                    sink.retireCurrent(carrier);
                    train.finish();
                    continue;
                }
                if (train.leg == null || !same(train.target, sample.destination())) {
                    if (train.leg != null) sink.retireCurrent(carrier);
                    train.leg = UUID.randomUUID();
                    train.source = train.lastStation;
                    train.newGeometry();
                }
                // Refresh display metadata while keeping names out of leg identity.
                if (same(train.source, sample.currentStation())) train.source = refreshedStation(sample.currentStation(), nativeStations);
                train.target = refreshedStation(sample.destination(), nativeStations);
                Endpoint source = resolve(train.source, resolver, refreshed);
                Endpoint target = resolve(train.target, resolver, refreshed);
                State state = sample.invalid() || sample.derailed() || !sample.graphAvailable() ? State.RECOVERING
                        : sample.waitingForChunks() ? State.WAITING_TERRAIN
                        : sample.paused() ? State.PAUSED
                        : sample.waitingForSignal() ? State.WAITING_RESOURCE : State.MOVING;
                OptionalLong eta = motion(train, sample, tick, state == State.MOVING);
                if (state == State.MOVING && eta.isEmpty()) state = State.ETA_UNKNOWN;
                // 'loaded' means a fresh authoritative native simulation read; it does not assert carriage chunks.
                sink.publish(new Observation(new Key(carrier, train.leg), LogisticsCarrier.Kind.LAND, sample.carrierName(),
                        source.displayName() + " → " + target.displayName(), source, target,
                        "RAIL_LEG",
                        state, true, tick, INTERVAL_TICKS, eta, eta.isPresent() ? tick : -1, train.geometry));
            }
        }
        private Endpoint resolve(Station station, EndpointResolver resolver, Map<Address, Endpoint> refreshed) {
            if (station == null || !station.located()) return Endpoint.unknown();
            Address address = new Address(station.dimensionId(), station.terminalPos());
            Endpoint result = refreshed.get(address);
            if (result != null) return result;
            Endpoint previous = endpoints.get(address);
            if (previous == null) previous = new Endpoint(station.dimensionId(), station.terminalPos(), "", station.displayName());
            result = resolver.resolve(station, previous);
            // A resolver cannot bind one exact native terminal to another address.
            if (result == null || !station.dimensionId().equals(result.dimensionId()) || station.terminalPos() != result.terminalPos()) result = previous;
            if (!endpoints.containsKey(address) && endpoints.size() >= MAX_STATION_ENDPOINTS) endpoints.remove(endpoints.keySet().iterator().next());
            endpoints.put(address, result);
            refreshed.put(address, result);
            return result;
        }
        private static OptionalLong motion(Tracked train, BoardSample sample, long tick, boolean moving) {
            double remaining = sample.remainingTrackBlocks(), speed = Math.abs(sample.speedBlocksPerTick());
            if (!moving || !Double.isFinite(remaining) || remaining < 0 || !Double.isFinite(speed) || speed <= 0) {
                train.clearMotion(); return OptionalLong.empty();
            }
            train.motion.removeIf(point -> tick - point.tick() > TownVoyageEta.MAX_SAMPLE_AGE_TICKS);
            if (train.motion.isEmpty()) { train.initialRemaining = remaining; train.previousRemaining = Double.NaN; }
            if (!train.motion.isEmpty() && (tick <= train.motion.getLast().tick() || remaining >= train.previousRemaining)) {
                if (remaining > train.previousRemaining) train.newGeometry(); else train.clearMotion();
                return OptionalLong.empty();
            }
            train.motion.add(new TownVoyageEta.ProgressSample(train.geometry, tick, train.initialRemaining - remaining));
            while (train.motion.size() > TownVoyageEta.MAX_PROGRESS_SAMPLES) train.motion.removeFirst();
            train.previousRemaining = remaining;
            OptionalDouble confirmedSpeed = TownVoyageEta.effectiveSpeed(train.motion, train.geometry, tick, false);
            if (confirmedSpeed.isEmpty()) return OptionalLong.empty();
            double ticks = Math.ceil(remaining / confirmedSpeed.getAsDouble());
            return Double.isFinite(ticks) && ticks > 0 && ticks <= MAX_ETA_TICKS ? OptionalLong.of((long) ticks) : OptionalLong.empty();
        }
        Set<UUID> trackedTrainIds() { return Set.copyOf(trains.keySet()); }
        void clear() { trains.clear(); endpoints.clear(); }
        private static Station refreshedStation(Station station, Map<StationIdentity, Station> nativeStations) {
            return station == null ? null : nativeStations.getOrDefault(station.identity(), station);
        }
        private static boolean same(Station a, Station b) { return a != null && b != null && a.identity().equals(b.identity()); }
    }

    /** Keeps a bounded tracked-first selection while the native registry is visited exactly once. */
    static final class BoardReadBuilder {
        private record Priority(boolean newTrain, UUID id) implements Comparable<Priority> {
            @Override public int compareTo(Priority other) {
                int priority = Boolean.compare(newTrain, other.newTrain);
                return priority != 0 ? priority : id.compareTo(other.id);
            }
        }
        private final Set<UUID> tracked;
        private final Set<UUID> missingTracked;
        private final int capacity;
        private final NavigableMap<Priority, BoardSample> selected = new TreeMap<>();
        private long count;
        BoardReadBuilder(Set<UUID> tracked) { this(tracked, MAX_BOARD_TRAINS); }
        BoardReadBuilder(Set<UUID> tracked, int capacity) {
            if (capacity < 1 || capacity > MAX_BOARD_TRAINS) throw new IllegalArgumentException("invalid_board_capacity");
            this.tracked = Set.copyOf(tracked); this.missingTracked = new HashSet<>(tracked); this.capacity = capacity;
        }
        boolean accepts(UUID trainId) {
            if (trainId == null) return false;
            observedNativeId(trainId);
            count++;
            return selected.size() < capacity || priority(trainId).compareTo(selected.lastKey()) < 0;
        }
        void observedNativeId(UUID trainId) { missingTracked.remove(trainId); }
        void add(BoardSample sample) {
            selected.put(priority(sample.trainId()), sample);
            if (selected.size() > capacity) selected.pollLastEntry();
        }
        private Priority priority(UUID id) { return new Priority(!tracked.contains(id), id); }
        Readout finish(List<RailTrainSnapshot> webTrains) {
            return new Readout(webTrains, new ArrayList<>(selected.values()), count <= capacity, missingTracked);
        }
    }

    static Set<UUID> trackedBoardTrainIds() { return boardTracker.trackedTrainIds(); }
    private static CarrierKey carrier(UUID train) { return new CarrierKey(CREATE_RAIL_PROVIDER, "global", train); }

    public static void tick(MinecraftServer server) {
        if (server == null || server.getTickCount() % INTERVAL_TICKS != 0 || !available()) {
            return;
        }
        if (boardServer != server) { clear(); boardServer = server; }
        ServerLevel overworld = server.overworld();
        Readout readout;
        try {
            readout = CreateTrainBridge.overworldTrains();
        } catch (LinkageError | RuntimeException incompatible) {
            available = false;
            LOGGER.warn("Create train logistics turned off: this Create version is not supported", incompatible);
            return;
        }
        RailTraceTracker.Result result = tracker.update(readout.webTrains());
        NationSavedData nations = NationSavedData.get(overworld);
        Set<String> shown = new HashSet<>();
        for (ShippingTraceService.ExternalShipment shipment : result.shipments()) {
            shown.add(shipment.traceId());
            ShippingTraceService.upsertExternalTrace(overworld, withNation(shipment, nations));
        }
        for (String traceId : result.removedTraceIds()) {
            ShippingTraceService.removeExternalTrace(overworld, traceId);
        }
        if (!primed) {
            // Rail traces saved before the restart whose train is gone or no longer scheduled.
            primed = true;
            for (var trace : ShippingTraceSavedData.get(overworld).getTraces()) {
                String id = trace.shippingOrderId();
                if (RailTraceTracker.isRailTrace(id) && !shown.contains(id)) {
                    ShippingTraceService.removeExternalTrace(overworld, id);
                }
            }
        }
        publishBoard(server, readout);
    }

    private static void publishBoard(MinecraftServer server, Readout readout) {
        TownVoyageService.existing(server).ifPresent(service -> {
            try {
                long now = Math.max(0, server.overworld().getGameTime());
                boardTracker.scan(readout.boardTrains(), readout.complete(), readout.confirmedMissingTrackedIds(), now, (station, previous) -> {
                    ResourceLocation dimension = ResourceLocation.tryParse(station.dimensionId());
                    ServerLevel actualLevel = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
                    if (actualLevel == null) return previous;
                    TownRecord town = TownService.getTownAt(actualLevel, BlockPos.of(station.terminalPos()));
                    Endpoint endpoint = new Endpoint(station.dimensionId(), station.terminalPos(), town == null ? "" : town.townId(), station.displayName());
                    service.observeEndpoint(endpoint, true);
                    return endpoint;
                }, new Sink() {
                    @Override public void publish(Observation observation) { service.publish(observation, now); }
                    @Override public void retireCurrent(CarrierKey carrier) {
                        service.currentKey(carrier).ifPresent(key -> service.retire(carrier, key.legId()));
                    }
                    @Override public void markUnloaded(CarrierKey carrier) { service.markUnloaded(carrier); }
                });
            } catch (RuntimeException invalidMetadata) {
                LOGGER.debug("Skipped invalid Create town arrivals metadata", invalidMetadata);
            }
        });
    }

    /** Server stopped: forget the legs so another world starts clean. */
    public static void clear() {
        tracker = new RailTraceTracker();
        boardTracker.clear();
        boardServer = null;
        primed = false;
    }

    private static boolean available() {
        if (available == null) {
            available = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        }
        return available;
    }

    /** Members of a nation see the trains of everyone in it, as with boats and carriages. */
    private static ShippingTraceService.ExternalShipment withNation(ShippingTraceService.ExternalShipment s, NationSavedData nations) {
        NationMemberRecord member = null;
        try {
            member = s.shipperUuid().isBlank() ? null : nations.getMember(UUID.fromString(s.shipperUuid()));
        } catch (IllegalArgumentException ignored) {
        }
        String nationId = member == null ? "" : member.nationId();
        return new ShippingTraceService.ExternalShipment(s.traceId(), s.shipperUuid(), nationId, s.transportMode(), s.status(),
                s.sourceName(), s.targetName(), s.vehicleName(), s.waypoints(), s.targetPointIndex(), s.progressRatio(),
                s.currentX(), s.currentZ(), s.currentSpeed(), s.cargo());
    }
}
