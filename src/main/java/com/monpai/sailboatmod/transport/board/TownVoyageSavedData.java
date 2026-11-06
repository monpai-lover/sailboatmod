package com.monpai.sailboatmod.transport.board;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;

/** Board-owned last-known active metadata; never a transport or order authority. */
public final class TownVoyageSavedData extends SavedData {
    public static final String DATA_NAME = "sailboatmod_town_voyages";
    public static final Factory<TownVoyageSavedData> FACTORY = new Factory<>(TownVoyageSavedData::new, TownVoyageSavedData::load);
    private static final int VERSION = 1, MAX_OBSERVATIONS = 4096, MAX_ENDPOINTS = 8192;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> OBSERVATION_KEYS = Set.of("ProviderId", "ScopeId", "CarrierId", "LegId", "Kind",
            "CarrierName", "RouteName", "Source", "Target", "Phase", "ObservedTick", "HeartbeatTicks", "GeometryId");
    private static final Set<String> ENDPOINT_KEYS = Set.of("DimensionId", "TerminalPos", "TownId", "DisplayName");
    private static final Set<String> REMOVED_ENDPOINT_KEYS = Set.of("DimensionId", "TerminalPos", "TownId", "DisplayName", "Removed");
    private List<Observation> observations = List.of();
    private List<Endpoint> endpoints = List.of();
    private List<Endpoint> unavailableEndpoints = List.of();
    private Set<EndpointKey> unavailableLocations = Set.of();
    private record EndpointKey(String dimensionId, long terminalPos) { }
    public TownVoyageSavedData() { }
    /** Decode board metadata independently; a broken entry never prevents valid neighbors loading. */
    public static TownVoyageSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        TownVoyageSavedData data = new TownVoyageSavedData();
        if (tag == null || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION) return data;
        Map<CarrierKey, Observation> rows = new LinkedHashMap<>();
        Map<EndpointKey, Endpoint> places = new LinkedHashMap<>();
        Set<EndpointKey> removed = new HashSet<>();
        int skipped = 0;
        ListTag encodedRows = tag.getList("Observations", Tag.TAG_COMPOUND);
        int rowLimit = Math.min(encodedRows.size(), MAX_OBSERVATIONS);
        for (int i = 0; i < rowLimit; i++) {
            try {
                Observation row = readObservation(encodedRows.getCompound(i));
                rows.put(row.key().carrier(), row);
            } catch (IllegalArgumentException ignored) { skipped++; }
        }
        ListTag encodedEndpoints = tag.getList("Endpoints", Tag.TAG_COMPOUND);
        int endpointLimit = Math.min(encodedEndpoints.size(), MAX_ENDPOINTS);
        for (int i = 0; i < endpointLimit; i++) {
            try {
                CompoundTag metadata = encodedEndpoints.getCompound(i);
                Endpoint endpoint = readEndpoint(metadata, true);
                places.put(key(endpoint), endpoint);
                if (metadata.getBoolean("Removed")) removed.add(key(endpoint));
                else removed.remove(key(endpoint));
            } catch (IllegalArgumentException ignored) { skipped++; }
        }
        data.observations = List.copyOf(rows.values());
        data.endpoints = List.copyOf(places.values());
        data.unavailableLocations = Set.copyOf(removed);
        data.unavailableEndpoints = data.endpoints.stream().filter(endpoint -> removed.contains(key(endpoint))).toList();
        if (skipped > 0 || encodedRows.size() > MAX_OBSERVATIONS || encodedEndpoints.size() > MAX_ENDPOINTS)
            LOGGER.warn("Town arrivals metadata skipped {} invalid rows; bounded input to {} observations and {} endpoints",
                    skipped, MAX_OBSERVATIONS, MAX_ENDPOINTS);
        return data;
    }
    public List<Observation> observations() { return observations; }
    public List<Endpoint> endpoints() { return endpoints; }
    public List<Endpoint> unavailableEndpoints() { return unavailableEndpoints; }
    /** Snapshot replacement stores only one current entry per carrier and exact endpoint location. */
    public void replace(List<Observation> observations, List<Endpoint> endpoints) {
        replace(observations, endpoints, List.of());
    }
    /** Removed endpoint locations remain known after restart; unload alone never creates this marker. */
    public void replace(List<Observation> observations, List<Endpoint> endpoints, List<Endpoint> unavailableEndpoints) {
        Map<CarrierKey, Observation> rows = new LinkedHashMap<>();
        Map<EndpointKey, Endpoint> places = new LinkedHashMap<>();
        if (observations != null) for (int i = 0, limit = Math.min(observations.size(), MAX_OBSERVATIONS); i < limit; i++) {
            Observation row = observations.get(i);
            if (row != null) rows.put(row.key().carrier(), persisted(row));
        }
        if (endpoints != null) for (int i = 0, limit = Math.min(endpoints.size(), MAX_ENDPOINTS); i < limit; i++) {
            Endpoint endpoint = endpoints.get(i);
            if (endpoint != null) places.put(key(endpoint), endpoint);
        }
        List<Observation> nextRows = List.copyOf(rows.values());
        List<Endpoint> nextPlaces = List.copyOf(places.values());
        Set<EndpointKey> removed = new HashSet<>();
        if (unavailableEndpoints != null) for (int i = 0, limit = Math.min(unavailableEndpoints.size(), MAX_ENDPOINTS); i < limit; i++) {
            Endpoint endpoint = unavailableEndpoints.get(i);
            if (endpoint != null && places.containsKey(key(endpoint))) removed.add(key(endpoint));
        }
        List<Endpoint> nextUnavailable = nextPlaces.stream().filter(endpoint -> removed.contains(key(endpoint))).toList();
        if (!nextRows.equals(this.observations) || !nextPlaces.equals(this.endpoints) || !nextUnavailable.equals(this.unavailableEndpoints)) {
            this.observations = nextRows; this.endpoints = nextPlaces;
            this.unavailableEndpoints = nextUnavailable; this.unavailableLocations = Set.copyOf(removed); setDirty();
        }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt("Version", VERSION);
        ListTag rows = new ListTag();
        for (Observation observation : observations) rows.add(writeObservation(observation));
        tag.put("Observations", rows);
        ListTag places = new ListTag();
        for (Endpoint endpoint : endpoints) {
            CompoundTag metadata = writeEndpoint(endpoint);
            if (unavailableLocations.contains(key(endpoint))) metadata.putBoolean("Removed", true);
            places.add(metadata);
        }
        tag.put("Endpoints", places);
        return tag;
    }
    private static Observation persisted(Observation observation) {
        return new Observation(observation.key(), observation.kind(), observation.carrierName(), observation.routeName(),
                observation.source(), observation.target(), observation.phase(), State.UNLOADED, false,
                observation.observedTick(), observation.heartbeatTicks(), OptionalLong.empty(), -1, observation.geometryId());
    }
    private static CompoundTag writeObservation(Observation row) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ProviderId", row.key().carrier().providerId());
        tag.putString("ScopeId", row.key().carrier().scopeId());
        tag.putUUID("CarrierId", row.key().carrier().carrierId()); tag.putUUID("LegId", row.key().legId());
        tag.putString("Kind", row.kind().name()); tag.putString("CarrierName", row.carrierName());
        tag.putString("RouteName", row.routeName()); tag.put("Source", writeEndpoint(row.source()));
        tag.put("Target", writeEndpoint(row.target())); tag.putString("Phase", row.phase());
        tag.putLong("ObservedTick", row.observedTick()); tag.putInt("HeartbeatTicks", row.heartbeatTicks());
        tag.putString("GeometryId", row.geometryId()); return tag;
    }
    private static Observation readObservation(CompoundTag tag) {
        if (!tag.getAllKeys().equals(OBSERVATION_KEYS) || !tag.hasUUID("CarrierId") || !tag.hasUUID("LegId")
                || !tag.contains("Source", Tag.TAG_COMPOUND) || !tag.contains("Target", Tag.TAG_COMPOUND)
                || !tag.contains("ObservedTick", Tag.TAG_LONG) || !tag.contains("HeartbeatTicks", Tag.TAG_INT)) throw invalid();
        CarrierKey carrier = new CarrierKey(string(tag, "ProviderId", 64), string(tag, "ScopeId", 256), tag.getUUID("CarrierId"));
        return new Observation(new Key(carrier, tag.getUUID("LegId")), LogisticsCarrier.Kind.valueOf(string(tag, "Kind", 16)),
                display(tag, "CarrierName", 64), display(tag, "RouteName", 96), readEndpoint(tag.getCompound("Source")),
                readEndpoint(tag.getCompound("Target")), string(tag, "Phase", 32), State.UNLOADED, false,
                tag.getLong("ObservedTick"), tag.getInt("HeartbeatTicks"), OptionalLong.empty(), -1, string(tag, "GeometryId", 256));
    }
    private static CompoundTag writeEndpoint(Endpoint endpoint) {
        CompoundTag tag = new CompoundTag(); tag.putString("DimensionId", endpoint.dimensionId());
        tag.putLong("TerminalPos", endpoint.terminalPos()); tag.putString("TownId", endpoint.townId());
        tag.putString("DisplayName", endpoint.displayName()); return tag;
    }
    private static Endpoint readEndpoint(CompoundTag tag) { return readEndpoint(tag, false); }
    private static Endpoint readEndpoint(CompoundTag tag, boolean metadata) {
        boolean hasRemoved = tag.contains("Removed");
        if (!(tag.getAllKeys().equals(ENDPOINT_KEYS) || metadata && tag.getAllKeys().equals(REMOVED_ENDPOINT_KEYS))
                || !tag.contains("TerminalPos", Tag.TAG_LONG) || hasRemoved && (!tag.contains("Removed", Tag.TAG_BYTE)
                || tag.getByte("Removed") != 0 && tag.getByte("Removed") != 1)) throw invalid();
        return new Endpoint(string(tag, "DimensionId", 256), tag.getLong("TerminalPos"), string(tag, "TownId", 128),
                display(tag, "DisplayName", 64));
    }
    private static String string(CompoundTag tag, String name, int maxCharacters) {
        if (!tag.contains(name, Tag.TAG_STRING)) throw invalid();
        String value = tag.getString(name);
        if (value.length() > maxCharacters) throw invalid();
        return value;
    }
    private static String display(CompoundTag tag, String name, int maxCodePoints) {
        if (!tag.contains(name, Tag.TAG_STRING)) throw invalid();
        String value = tag.getString(name);
        if (value.length() > maxCodePoints * 2 || value.codePointCount(0, value.length()) > maxCodePoints) throw invalid();
        return value;
    }
    private static EndpointKey key(Endpoint endpoint) { return new EndpointKey(endpoint.dimensionId(), endpoint.terminalPos()); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_town_voyage_saved_metadata"); }
}
