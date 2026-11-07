package com.monpai.sailboatmod.transport.board;

import com.monpai.sailboatmod.transport.LogisticsCarrier;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;

class TownVoyageSnapshotTest {
    private static final UUID CARRIER = UUID.fromString("59ea3ac9-5426-40bc-9297-b7fe6596f667");
    private static final UUID LEG = UUID.fromString("cf66b57c-9a47-4c33-be56-b0d175aa3420");
    static Endpoint endpoint(String dimension, long pos, String town, String name) { return new Endpoint(dimension, pos, town, name); }
    static Observation observation(Endpoint source, Endpoint target) {
        return observation(new Key(new CarrierKey("entity", "minecraft:overworld", CARRIER), LEG), source, target, "CRUISE", OptionalLong.of(2000));
    }
    static Observation observation(Key key, Endpoint source, Endpoint target, String phase, OptionalLong eta) {
        return new Observation(key, LogisticsCarrier.Kind.WATER, "Carrier", "Route", source, target,
                phase, State.MOVING, true, 100, 20, eta, 100, "geometry-1");
    }
    @Test void exactTownIdentityIncludesSourceOrTargetAndLocalOnlyOnce() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "Home");
        Endpoint away = endpoint("minecraft:overworld", 8, "town-b", "Away");
        assertEquals(Relation.OUTBOUND, forTown(observation(home, away), "town-a").orElseThrow().relation());
        assertEquals(Relation.INBOUND, forTown(observation(home, away), "town-b").orElseThrow().relation());
        assertEquals(Relation.LOCAL, forTown(observation(home, home), "town-a").orElseThrow().relation());
        assertTrue(forTown(observation(home, away), "town-c").isEmpty());
    }
    @Test void sameCoordinatesAndNameAcrossDimensionsDoNotGrantTownMembership() {
        Endpoint other = endpoint("minecraft:the_nether", 7, "other-town", "Home");
        assertTrue(forTown(observation(other, other), "town-a").isEmpty());
        assertTrue(forTown(observation(other, other), "OTHER-TOWN").isEmpty());
    }
    @Test void oneUnknownEndpointStillIncludesTheKnownMatchingTown() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "Home");
        assertEquals(Relation.INBOUND, forTown(observation(Endpoint.unknown(), home), "town-a").orElseThrow().relation());
        assertEquals(Relation.OUTBOUND, forTown(observation(home, Endpoint.unknown()), "town-a").orElseThrow().relation());
        assertTrue(forTown(observation(Endpoint.unknown(), Endpoint.unknown()), "").isEmpty());
    }
    @Test void projectionSanitizesNamesAndAllowsOnlyKnownPublicPhases() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "Home\n\u00a7cDock\u202e");
        var input = new Observation(observation(home, home).key(), LogisticsCarrier.Kind.AIR,
                "\n\u00a7a" + "机".repeat(100), "Route\r\n" + "r".repeat(150), home, home,
                "private_order_123", State.PAUSED, true, 100, 20, OptionalLong.of(4), 100, "g");
        var row = forTown(input, "town-a").orElseThrow();
        assertEquals(64, row.carrierName().codePointCount(0, row.carrierName().length()));
        assertEquals(96, row.routeName().length());
        assertEquals("Home Dock", row.sourceName());
        assertEquals("UNKNOWN", row.publicPhase());
        assertEquals(State.PAUSED, row.state());
        assertEquals(LogisticsCarrier.Kind.AIR, row.kind());
        assertFalse(row.toString().contains("private_order"));
    }
    @Test void publicRowHasOnlyExplicitPublicWhitelistFields() {
        assertEquals(List.of("rowId", "kind", "carrierName", "routeName", "sourceName", "targetName", "relation", "publicPhase", "state", "etaTicks"),
                Arrays.stream(PublicRow.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertEquals(Set.of("MOVING", "PAUSED", "FUEL_BLOCKED", "WAITING_TERRAIN", "WAITING_RESOURCE", "RECOVERING", "UNLOADED", "STALE", "ESTIMATING", "ETA_UNKNOWN", "DWELLING"),
                Arrays.stream(State.values()).map(Enum::name).collect(Collectors.toSet()));
    }
    @Test void dedupIdentityIncludesProviderScopeCarrierAndActualLeg() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "Home");
        Key base = observation(home, home).key();
        List<Key> identities = List.of(base, new Key(new CarrierKey("physical", "minecraft:overworld", CARRIER), LEG),
                new Key(new CarrierKey("entity", "minecraft:the_nether", CARRIER), LEG),
                new Key(new CarrierKey("entity", "minecraft:overworld", UUID.randomUUID()), LEG),
                new Key(base.carrier(), UUID.randomUUID()));
        assertEquals(5, identities.stream().map(k -> forTown(observation(k, home, home, "TAXI_IN", OptionalLong.empty()), "town-a").orElseThrow().rowId()).distinct().count());
        assertEquals(forTown(observation(home, home), "town-a"), forTown(observation(home, home), "town-a"));
    }
    @Test void etaMustBePositiveAndWithinSevenDays() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "Home");
        for (long invalid : new long[]{-1, 0, MAX_ETA_TICKS + 1, Long.MAX_VALUE}) {
            assertTrue(forTown(observation(observation(home, home).key(), home, home, "CRUISE", OptionalLong.of(invalid)), "town-a").orElseThrow().etaTicks().isEmpty());
        }
        assertEquals(MAX_ETA_TICKS, forTown(observation(observation(home, home).key(), home, home, "CRUISE", OptionalLong.of(MAX_ETA_TICKS)), "town-a").orElseThrow().etaTicks().orElseThrow());
    }
    @Test void identityAndEndpointMetadataAreStrictlyBounded() {
        assertThrows(IllegalArgumentException.class, () -> new CarrierKey("x".repeat(65), "world", CARRIER));
        assertThrows(IllegalArgumentException.class, () -> new CarrierKey("entity\nother", "world", CARRIER));
        assertThrows(IllegalArgumentException.class, () -> new Key(null, LEG));
        assertThrows(IllegalArgumentException.class, () -> endpoint("not a dimension", 7, "town-a", "Home"));
        assertThrows(IllegalArgumentException.class, () -> endpoint("minecraft:overworld", 7, "a".repeat(129), "Home"));
        assertThrows(IllegalArgumentException.class, () -> endpoint("", 7, "town-a", "Home"));
    }
    @Test void malformedSurrogateIdentitiesCannotCollapseToTheSameUtf8RowId() {
        for (String malformed : List.of("\uD800", "\uD801", "\uDC00", "a\uD800b", "\uDC00\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> new CarrierKey(malformed, "world", CARRIER));
            assertThrows(IllegalArgumentException.class, () -> new CarrierKey("entity", malformed, CARRIER));
            assertThrows(IllegalArgumentException.class, () -> endpoint("minecraft:overworld", 7, malformed, "Home"));
        }
        assertDoesNotThrow(() -> new CarrierKey("\uD83D\uDEA2", "world", CARRIER), "well-formed supplementary characters are distinct Unicode identities");
    }
    @Test void publicDisplayNamesNormalizeUnpairedSurrogatesAndPreserveValidSupplementaryCharacters() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "\uD800 Home \uD83D\uDEA2");
        var input = new Observation(observation(home, home).key(), LogisticsCarrier.Kind.WATER,
                "Carrier\uD801", "Route\uDC00", home, home, "CRUISE", State.MOVING,
                true, 100, 20, OptionalLong.of(10), 100, "g");
        var row = forTown(input, "town-a").orElseThrow();
        assertEquals("\uFFFD Home \uD83D\uDEA2", row.sourceName());
        assertEquals("Carrier\uFFFD", row.carrierName());
        assertEquals("Route\uFFFD", row.routeName());
    }
    @Test void boardViewCopiesItsPageAndEnforcesEightRows() {
        Endpoint home = endpoint("minecraft:overworld", 7, "town-a", "Home");
        PublicRow row = forTown(observation(home, home), "town-a").orElseThrow();
        List<PublicRow> rows = new ArrayList<>(List.of(row));
        BoardView board = new BoardView(1, 100, Filter.ALL, 0, 1, 0, false, rows);
        rows.clear();
        assertEquals(1, board.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> board.rows().clear());
        assertThrows(IllegalArgumentException.class, () -> new BoardView(1, 100, Filter.ALL, 0, 2, 0, false, Collections.nCopies(9, row)));
        assertThrows(IllegalArgumentException.class, () -> new BoardView(1, 100, Filter.ALL, 1, 1, 0, false, List.of()));
    }
}
