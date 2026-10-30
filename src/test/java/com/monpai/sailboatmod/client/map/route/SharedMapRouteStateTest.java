package com.monpai.sailboatmod.client.map.route;

import com.monpai.sailboatmod.map.route.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapRouteStateTest {
    private static final MapRouteViewport VIEW = new MapRouteViewport(-100, -100, 100, 100, 1);

    @Test
    void categoriesFilterImmediatelyAndClearAHiddenSelection() {
        SharedMapRouteState state = ready();
        var request = state.request("world", "minecraft:overworld", VIEW, 0).orElseThrow();
        assertTrue(state.accept(request.sessionId(), request.requestId(), request.worldId(), request.dimensionId(),
                new MapRouteSnapshot(List.of(route("road", MapRouteKind.ROAD), route("sea", MapRouteKind.SEA)), false)));
        state.select("sea");
        assertEquals("sea", state.selected().orElseThrow().id());
        state.setVisible(MapRouteKind.SEA, false);
        assertEquals(List.of("road"), state.visibleRoutes().stream().map(MapRouteEntry::id).toList());
        assertTrue(state.selected().isEmpty());
        state.setVisible(MapRouteKind.SEA, true);
        assertEquals(2, state.visibleRoutes().size());
    }

    @Test
    void selectionRetainsStableIdentityButClearsWhenRouteDisappears() {
        SharedMapRouteState state = ready();
        var first = state.request("world", "minecraft:overworld", VIEW, 0).orElseThrow();
        state.accept(first.sessionId(), first.requestId(), first.worldId(), first.dimensionId(), new MapRouteSnapshot(List.of(route("a", MapRouteKind.LAND)), false));
        state.select("a");
        var refresh = state.request("world", "minecraft:overworld", VIEW, 5_000).orElseThrow();
        state.accept(refresh.sessionId(), refresh.requestId(), refresh.worldId(), refresh.dimensionId(), new MapRouteSnapshot(List.of(route("a", MapRouteKind.LAND)), true));
        assertEquals("a", state.selected().orElseThrow().id());
        assertTrue(state.truncated());
        var next = state.request("world", "minecraft:overworld", VIEW, 10_000).orElseThrow();
        state.accept(next.sessionId(), next.requestId(), next.worldId(), next.dimensionId(), new MapRouteSnapshot(List.of(), false));
        assertTrue(state.selected().isEmpty());
    }

    @Test
    void throttleRejectsOldGenerationWhileTheLatestViewportWaits() {
        SharedMapRouteState state = ready();
        var first = state.request("world", "minecraft:overworld", VIEW, 0).orElseThrow();
        var moved = new MapRouteViewport(-50, -100, 150, 100, 1);
        assertTrue(state.request("world", "minecraft:overworld", moved, 30).isEmpty());
        assertFalse(state.accept(first.sessionId(), first.requestId(), first.worldId(), first.dimensionId(), new MapRouteSnapshot(List.of(route("old", MapRouteKind.ROAD)), false)));
        var latest = state.request("world", "minecraft:overworld", moved, 250).orElseThrow();
        assertTrue(latest.requestId() > first.requestId());
        assertTrue(state.accept(latest.sessionId(), latest.requestId(), latest.worldId(), latest.dimensionId(), new MapRouteSnapshot(List.of(route("new", MapRouteKind.ROAD)), false)));
        assertFalse(state.accept(first.sessionId(), first.requestId(), first.worldId(), first.dimensionId(), new MapRouteSnapshot(List.of(route("old", MapRouteKind.ROAD)), false)));
        assertEquals("new", state.visibleRoutes().getFirst().id());
    }

    @Test
    void dimensionAndWorldChangesClearRoutesSelectionAndRejectOldReplies() {
        SharedMapRouteState state = ready();
        var old = state.request("world", "minecraft:overworld", VIEW, 0).orElseThrow();
        state.accept(old.sessionId(), old.requestId(), old.worldId(), old.dimensionId(), new MapRouteSnapshot(List.of(route("a", MapRouteKind.ROAD)), true));
        state.select("a");
        var next = state.request("world", "minecraft:the_nether", VIEW, 10).orElseThrow();
        assertTrue(state.visibleRoutes().isEmpty());
        assertTrue(state.selected().isEmpty());
        assertFalse(state.truncated());
        assertFalse(state.accept(old.sessionId(), old.requestId(), old.worldId(), old.dimensionId(), new MapRouteSnapshot(List.of(route("a", MapRouteKind.ROAD)), false)));
        assertFalse(state.accept(UUID.randomUUID(), next.requestId(), next.worldId(), next.dimensionId(), new MapRouteSnapshot(List.of(), false)));
        state.request("other_world", "minecraft:the_nether", VIEW, 20).orElseThrow();
        assertFalse(state.accept(next.sessionId(), next.requestId(), next.worldId(), next.dimensionId(), new MapRouteSnapshot(List.of(), false)));
    }

    @Test
    void inspectionStartsOffAndClearInvalidatesInFlightReplies() {
        SharedMapRouteState state = ready();
        assertFalse(state.inspectionEnabled());
        state.setInspectionEnabled(true);
        assertTrue(state.inspectionEnabled());
        var request = state.request("world", "minecraft:overworld", VIEW, 0).orElseThrow();
        assertTrue(state.inspectionEnabled(), "the first terrain context must retain the explicit setting");
        state.clear();
        assertFalse(state.accept(request.sessionId(), request.requestId(), request.worldId(), request.dimensionId(), new MapRouteSnapshot(List.of(route("a", MapRouteKind.ROAD)), false)));
    }

    private static SharedMapRouteState ready() { return new SharedMapRouteState(UUID.randomUUID()); }

    private static MapRouteEntry route(String id, MapRouteKind kind) {
        return new MapRouteEntry(id, kind, id, "A", "B", 20, List.of(List.of(new MapRoutePoint(-10, 0), new MapRoutePoint(10, 0))));
    }
}
