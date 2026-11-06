package com.monpai.sailboatmod.transport.board;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Immutable inputs and the deliberately small public arrivals-board projection. */
public final class TownVoyageSnapshot {
    public static final long MAX_ETA_TICKS = 12_096_000;
    public static final int MAX_ROWS = 8;
    public enum State { MOVING, PAUSED, FUEL_BLOCKED, WAITING_TERRAIN, WAITING_RESOURCE, RECOVERING, UNLOADED, STALE, ESTIMATING, ETA_UNKNOWN }
    public enum Relation { INBOUND, OUTBOUND, LOCAL }
    public enum Filter { ALL, WATER, LAND, AIR }
    private static final Set<String> PUBLIC_PHASES = Set.of("UNKNOWN", "MOVING", "PAUSED", "SAILING", "DRIVING",
            "TAXI_OUT", "HOLD_SHORT", "LINE_UP", "TAKEOFF", "CLIMB", "CRUISE", "APPROACH", "LANDING_ROLL", "TAXI_IN", "STAND_STABILIZING");
    public record CarrierKey(String providerId, String scopeId, UUID carrierId) {
        public CarrierKey {
            providerId = identity(providerId, 64, false);
            scopeId = identity(scopeId, 256, false);
            if (carrierId == null) throw invalid();
        }
    }
    public record Key(CarrierKey carrier, UUID legId) {
        public Key { if (carrier == null || legId == null) throw invalid(); }
    }
    public record Endpoint(String dimensionId, long terminalPos, String townId, String displayName) {
        public Endpoint {
            dimensionId = identity(dimensionId, 256, true);
            townId = identity(townId, 128, true);
            if (dimensionId.isEmpty() ? terminalPos != 0 || !townId.isEmpty()
                    : !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) throw invalid();
            displayName = display(displayName, 64);
        }
        public static Endpoint unknown() { return new Endpoint("", 0, "", "Unknown"); }
    }
    public record Observation(Key key, LogisticsCarrier.Kind kind, String carrierName, String routeName,
                              Endpoint source, Endpoint target, String phase, State state, boolean loaded,
                              long observedTick, int heartbeatTicks, OptionalLong etaTicks,
                              long etaObservedTick, String geometryId) {
        public Observation {
            if (key == null || kind == null || state == null || observedTick < 0 || heartbeatTicks <= 0 || heartbeatTicks > 1200 || etaObservedTick < -1) throw invalid();
            carrierName = display(carrierName, 64);
            routeName = display(routeName, 96);
            source = source == null ? Endpoint.unknown() : source;
            target = target == null ? Endpoint.unknown() : target;
            phase = publicPhase(phase);
            etaTicks = boundedEta(etaTicks);
            geometryId = identity(geometryId, 256, true);
        }
    }
    public record PublicRow(String rowId, LogisticsCarrier.Kind kind, String carrierName, String routeName,
                            String sourceName, String targetName, Relation relation, String publicPhase,
                            State state, OptionalLong etaTicks) {
        public PublicRow {
            rowId = identity(rowId, 128, false);
            if (kind == null || relation == null || state == null) throw invalid();
            carrierName = display(carrierName, 64);
            routeName = display(routeName, 96);
            sourceName = display(sourceName, 64);
            targetName = display(targetName, 64);
            publicPhase = TownVoyageSnapshot.publicPhase(publicPhase);
            etaTicks = state == State.MOVING ? boundedEta(etaTicks) : OptionalLong.empty();
        }
        /** Elapsed gameplay ticks consume a prediction, but cannot prove an arrival. */
        public PublicRow afterTicks(long elapsed) {
            if (elapsed <= 0 || etaTicks.isEmpty()) return this;
            long remaining = etaTicks.getAsLong() - elapsed;
            return new PublicRow(rowId, kind, carrierName, routeName, sourceName, targetName, relation, publicPhase,
                    remaining > 0 ? state : State.ETA_UNKNOWN,
                    remaining > 0 ? OptionalLong.of(remaining) : OptionalLong.empty());
        }
    }
    public record BoardView(long revision, long observedTick, Filter filter, int page, int totalPages,
                            int overflow, boolean syncing, List<PublicRow> rows) {
        public BoardView {
            if (revision < 0 || observedTick < 0 || filter == null || totalPages < 1 || page < 0 || page >= totalPages || overflow < 0 || rows == null || rows.size() > MAX_ROWS) throw invalid();
            rows = List.copyOf(rows);
        }
    }
    private TownVoyageSnapshot() { }
    public static Optional<PublicRow> forTown(Observation observation, String townId) {
        if (observation == null || townId == null || townId.isEmpty()) return Optional.empty();
        boolean source = townId.equals(observation.source().townId());
        boolean target = townId.equals(observation.target().townId());
        if (!source && !target) return Optional.empty();
        CarrierKey carrier = observation.key().carrier();
        String identity = carrier.providerId() + '\0' + carrier.scopeId() + '\0' + carrier.carrierId() + '\0' + observation.key().legId();
        return Optional.of(new PublicRow(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                observation.kind(), observation.carrierName(), observation.routeName(), observation.source().displayName(),
                observation.target().displayName(), source && target ? Relation.LOCAL : source ? Relation.OUTBOUND : Relation.INBOUND,
                observation.phase(), observation.state(), observation.etaTicks()));
    }
    static String identity(String value, int max, boolean emptyAllowed) {
        if (value == null || value.length() > max || !emptyAllowed && value.isBlank()
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.SURROGATE)) throw invalid();
        return value;
    }
    private static String display(String value, int max) {
        if (value == null || value.isBlank()) return "Unknown";
        StringBuilder result = new StringBuilder();
        boolean formatting = false, space = false;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.getType(codePoint) == Character.SURROGATE) codePoint = 0xFFFD;
            if (formatting) { formatting = false; continue; }
            if (codePoint == 0x00a7) { formatting = true; continue; }
            if (Character.getType(codePoint) == Character.FORMAT) continue;
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) { space = !result.isEmpty(); continue; }
            if (space && count < max) { result.append(' '); count++; }
            space = false;
            if (count >= max) break;
            result.appendCodePoint(codePoint); count++;
        }
        return result.isEmpty() ? "Unknown" : result.toString().strip();
    }
    private static String publicPhase(String phase) { return PUBLIC_PHASES.contains(Objects.requireNonNullElse(phase, "")) ? phase : "UNKNOWN"; }
    static OptionalLong boundedEta(OptionalLong eta) { return eta != null && eta.isPresent() && eta.getAsLong() > 0 && eta.getAsLong() <= MAX_ETA_TICKS ? eta : OptionalLong.empty(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_town_voyage_metadata"); }
}
