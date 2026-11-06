package com.monpai.sailboatmod.transport.board;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;

class TownVoyageSavedDataTest {
    static Endpoint home() { return new Endpoint("minecraft:overworld", 7, "town-a", "Home"); }
    static Endpoint away() { return new Endpoint("minecraft:the_nether", 7, "town-b", "Away"); }
    static Observation observation(int id) {
        return new Observation(new Key(new CarrierKey("sailboatmod", "minecraft:overworld", new UUID(0, id)), new UUID(1, id)),
                LogisticsCarrier.Kind.AIR, "Aircraft " + id, "Home - Away", home(), away(), "CRUISE", State.MOVING,
                true, 100, 20, OptionalLong.of(900), 100, "geometry-" + id);
    }
    static CompoundTag saved() {
        var data = new TownVoyageSavedData(); data.replace(List.of(observation(1), observation(2)), List.of(home(), away()));
        return data.save(new CompoundTag(), null);
    }
    @Test void roundTripKeepsCurrentPublicMetadataButNeverRestoresLiveEtaOrLoadedState() {
        CompoundTag tag = saved(); CompoundTag before = tag.copy();
        var restored = TownVoyageSavedData.load(tag, null);
        assertEquals(2, restored.observations().size());
        Observation row = restored.observations().getFirst();
        assertEquals(observation(1).key(), row.key());
        assertEquals(home(), row.source()); assertEquals(away(), row.target());
        assertEquals("Aircraft 1", row.carrierName()); assertEquals("Home - Away", row.routeName());
        assertEquals("CRUISE", row.phase()); assertEquals(100, row.observedTick());
        assertFalse(row.loaded()); assertEquals(State.UNLOADED, row.state());
        assertTrue(row.etaTicks().isEmpty()); assertEquals(-1, row.etaObservedTick());
        assertEquals(List.of(home(), away()), restored.endpoints());
        assertEquals(before, tag);
        assertThrows(UnsupportedOperationException.class, () -> restored.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> restored.endpoints().clear());
    }
    @Test void schemaContainsOnlyCurrentBoardMetadataWithoutEtaNavigationOrPrivateData() {
        CompoundTag tag = saved();
        assertEquals(Set.of("Version", "Observations", "Endpoints"), tag.getAllKeys());
        CompoundTag row = tag.getList("Observations", Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(Set.of("ProviderId", "ScopeId", "CarrierId", "LegId", "Kind", "CarrierName", "RouteName",
                "Source", "Target", "Phase", "ObservedTick", "HeartbeatTicks", "GeometryId"), row.getAllKeys());
        assertEquals(Set.of("DimensionId", "TerminalPos", "TownId", "DisplayName"), row.getCompound("Source").getAllKeys());
        assertEquals(Set.of("DimensionId", "TerminalPos", "TownId", "DisplayName"), tag.getList("Endpoints", Tag.TAG_COMPOUND).getCompound(0).getAllKeys());
        for (String forbidden : List.of("Eta", "Cargo", "Order", "Money", "Owner", "Fuel", "Path", "Native"))
            assertFalse(tag.toString().contains(forbidden));
    }
    @Test void malformedRowsAreSkippedIndividuallyWithoutLosingOtherRowsOrMutatingInput() {
        List<CompoundTag> damaged = new ArrayList<>();
        CompoundTag uuid = saved(); first(uuid).putString("LegId", "invalid"); damaged.add(uuid);
        CompoundTag name = saved(); first(name).putString("CarrierName", "n".repeat(65)); damaged.add(name);
        CompoundTag route = saved(); first(route).putString("RouteName", "n".repeat(97)); damaged.add(route);
        CompoundTag town = saved(); first(town).getCompound("Source").putString("TownId", "t".repeat(129)); damaged.add(town);
        CompoundTag dimension = saved(); first(dimension).getCompound("Target").putString("DimensionId", "not a dimension"); damaged.add(dimension);
        CompoundTag tick = saved(); first(tick).putString("ObservedTick", "100"); damaged.add(tick);
        CompoundTag missing = saved(); first(missing).remove("CarrierName"); damaged.add(missing);
        CompoundTag heartbeat = saved(); first(heartbeat).putInt("HeartbeatTicks", 0); damaged.add(heartbeat);
        CompoundTag kind = saved(); first(kind).putString("Kind", "SECRET_KIND"); damaged.add(kind);
        CompoundTag extra = saved(); first(extra).putString("Cargo", "private"); damaged.add(extra);
        for (CompoundTag tag : damaged) {
            CompoundTag before = tag.copy(); var restored = TownVoyageSavedData.load(tag, null);
            assertEquals(List.of(observation(2).key()), restored.observations().stream().map(Observation::key).toList());
            assertEquals(List.of(home(), away()), restored.endpoints()); assertEquals(before, tag);
        }
    }
    @Test void unknownVersionAndWrongListTypesFailClosedWithoutThrowing() {
        var version = saved(); version.putInt("Version", 99);
        var wrongVersionType = saved(); wrongVersionType.putString("Version", "1");
        for (var tag : List.of(version, wrongVersionType, new CompoundTag())) {
            var restored = TownVoyageSavedData.load(tag, null);
            assertTrue(restored.observations().isEmpty()); assertTrue(restored.endpoints().isEmpty());
        }
        var wrongRows = saved(); wrongRows.putString("Observations", "wrong");
        assertTrue(TownVoyageSavedData.load(wrongRows, null).observations().isEmpty());
        assertEquals(2, TownVoyageSavedData.load(wrongRows, null).endpoints().size());
        var wrongEndpoint = saved(); wrongEndpoint.putString("Endpoints", "wrong");
        assertEquals(2, TownVoyageSavedData.load(wrongEndpoint, null).observations().size());
        assertTrue(TownVoyageSavedData.load(wrongEndpoint, null).endpoints().isEmpty());
    }
    @Test void malformedEndpointDoesNotRemoveOtherEndpointOrActiveRows() {
        var tag = saved(); tag.getList("Endpoints", Tag.TAG_COMPOUND).getCompound(0).putString("TerminalPos", "7");
        var restored = TownVoyageSavedData.load(tag, null);
        assertEquals(List.of(away()), restored.endpoints()); assertEquals(2, restored.observations().size());
    }
    @Test void replaceKeepsOnlyOneCurrentLegPerCarrierAndExactDimensionEndpoint() {
        Observation initial = observation(1);
        Observation next = new Observation(new Key(initial.key().carrier(), UUID.randomUUID()), initial.kind(), "Returning", "Return",
                away(), home(), "TAXI_OUT", State.MOVING, true, 110, 20, OptionalLong.of(200), 110, "return-geometry");
        var data = new TownVoyageSavedData(); data.replace(Arrays.asList(initial, null, next), Arrays.asList(home(), null, away()));
        assertEquals(List.of(next.key()), data.observations().stream().map(Observation::key).toList());
        assertEquals(List.of(home(), away()), data.endpoints());
        data.replace(List.of(), List.of()); assertTrue(data.observations().isEmpty()); assertTrue(data.endpoints().isEmpty());
        assertTrue(data.isDirty());
    }
    @Test void boundsLimitReadAndWriteWorkAndKeepFirstValidBoundedEntries() {
        List<Observation> rows = new ArrayList<>(); for (int i=0; i<4100; i++) rows.add(observation(i));
        List<Endpoint> endpoints = new ArrayList<>(); for (int i=0; i<8200; i++) endpoints.add(new Endpoint("minecraft:overworld", i, "town-a", "Dock " + i));
        var data = new TownVoyageSavedData(); data.replace(rows, endpoints);
        assertEquals(4096, data.observations().size()); assertEquals(8192, data.endpoints().size());
        var tag = data.save(new CompoundTag(), null);
        var rowList = tag.getList("Observations", Tag.TAG_COMPOUND); rowList.add(rowList.getCompound(0).copy());
        var endpointList = tag.getList("Endpoints", Tag.TAG_COMPOUND); endpointList.add(newEndpointTag(9000));
        var restored = TownVoyageSavedData.load(tag, null);
        assertEquals(4096, restored.observations().size()); assertEquals(8192, restored.endpoints().size());
    }
    @Test void removedEndpointMetadataRoundTripsAsBoundedExactLocationTombstone() {
        var data = new TownVoyageSavedData();
        var untracked = new Endpoint("minecraft:overworld", 999, "town-a", "Not tracked");
        data.replace(List.of(observation(1)), List.of(home(), away()), List.of(away(), untracked));
        assertEquals(List.of(away()), data.unavailableEndpoints());
        CompoundTag tag = data.save(new CompoundTag(), null);
        assertFalse(tag.getList("Endpoints", Tag.TAG_COMPOUND).getCompound(0).contains("Removed"));
        assertTrue(tag.getList("Endpoints", Tag.TAG_COMPOUND).getCompound(1).getBoolean("Removed"));
        var restored = TownVoyageSavedData.load(tag, null);
        assertEquals(List.of(home(), away()), restored.endpoints());
        assertEquals(List.of(away()), restored.unavailableEndpoints());
        assertFalse(restored.observations().getFirst().loaded());
        assertThrows(UnsupportedOperationException.class, () -> restored.unavailableEndpoints().clear());
        CompoundTag damaged = tag.copy();
        damaged.getList("Endpoints", Tag.TAG_COMPOUND).getCompound(1).putString("Removed", "true");
        assertEquals(List.of(home()), TownVoyageSavedData.load(damaged, null).endpoints());
        restored.replace(restored.observations(), restored.endpoints());
        assertTrue(restored.unavailableEndpoints().isEmpty());
    }
    private static CompoundTag first(CompoundTag tag) { return tag.getList("Observations", Tag.TAG_COMPOUND).getCompound(0); }
    private static CompoundTag newEndpointTag(long position) {
        var tag = new CompoundTag(); tag.putString("DimensionId", "minecraft:overworld"); tag.putLong("TerminalPos", position);
        tag.putString("TownId", "town-a"); tag.putString("DisplayName", "Dock"); return tag;
    }
}
