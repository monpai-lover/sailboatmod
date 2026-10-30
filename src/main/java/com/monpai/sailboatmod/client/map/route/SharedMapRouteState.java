package com.monpai.sailboatmod.client.map.route;

import com.monpai.sailboatmod.map.route.MapRouteEntry;
import com.monpai.sailboatmod.map.route.MapRouteKind;
import com.monpai.sailboatmod.map.route.MapRouteSnapshot;
import com.monpai.sailboatmod.map.route.MapRouteViewport;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Per-screen display state. It never changes authoritative route geometry. */
public final class SharedMapRouteState {
    private static final long REQUEST_INTERVAL_MS = 200;
    private static final long REFRESH_INTERVAL_MS = 3_000;
    private final UUID sessionId;
    private final EnumSet<MapRouteKind> visible = EnumSet.allOf(MapRouteKind.class);
    private List<MapRouteEntry> routes = List.of();
    private String selectedId = "";
    private String worldId = "";
    private String dimensionId = "";
    private MapRouteViewport desiredViewport;
    private MapRouteViewport sentViewport;
    private long sequence;
    private long sentRequestId = -1;
    private long lastSentAt = Long.MIN_VALUE;
    private boolean truncated;
    private boolean inspectionEnabled;

    public SharedMapRouteState(UUID sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public UUID sessionId() { return sessionId; }
    public String worldId() { return worldId; }
    public String dimensionId() { return dimensionId; }
    public boolean inspectionEnabled() { return inspectionEnabled; }
    public void setInspectionEnabled(boolean enabled) { inspectionEnabled = enabled; }
    public boolean truncated() { return truncated; }
    public boolean visible(MapRouteKind kind) { return visible.contains(kind); }

    public void setVisible(MapRouteKind kind, boolean enabled) {
        if (enabled) visible.add(kind); else visible.remove(kind);
        if (selected().isEmpty()) selectedId = "";
    }

    public List<MapRouteEntry> visibleRoutes() {
        return routes.stream().filter(entry -> visible.contains(entry.kind())).toList();
    }

    public Optional<MapRouteEntry> selected() {
        return routes.stream().filter(entry -> entry.id().equals(selectedId) && visible.contains(entry.kind())).findFirst();
    }

    public void select(String id) {
        selectedId = id == null ? "" : id;
        if (selected().isEmpty()) selectedId = "";
    }

    public Optional<Request> request(String world, String dimension, MapRouteViewport viewport, long now) {
        String nextWorld = world == null ? "" : world;
        String nextDimension = dimension == null ? "" : dimension;
        if (!worldId.equals(nextWorld) || !dimensionId.equals(nextDimension)) {
            clearSnapshot();
            worldId = nextWorld;
            dimensionId = nextDimension;
            desiredViewport = null;
            sentViewport = null;
            sentRequestId = -1;
            lastSentAt = Long.MIN_VALUE;
        }
        desiredViewport = viewport;
        if (nextWorld.isBlank() || nextDimension.isBlank() || viewport == null) return Optional.empty();
        boolean first = sentViewport == null;
        long elapsed = first ? Long.MAX_VALUE : Math.max(0, now - lastSentAt);
        boolean changed = !viewport.equals(sentViewport);
        if (!first && (elapsed < REQUEST_INTERVAL_MS || (!changed && elapsed < REFRESH_INTERVAL_MS))) return Optional.empty();
        sentViewport = viewport;
        sentRequestId = ++sequence;
        lastSentAt = now;
        return Optional.of(new Request(sessionId, sentRequestId, worldId, dimensionId, viewport));
    }

    public boolean accept(UUID session, long requestId, String world, String dimension, MapRouteSnapshot snapshot) {
        if (!sessionId.equals(session) || requestId != sentRequestId || sentRequestId < 0
                || !worldId.equals(world) || !dimensionId.equals(dimension)
                || !Objects.equals(desiredViewport, sentViewport) || snapshot == null) return false;
        routes = List.copyOf(snapshot.routes());
        truncated = snapshot.truncated();
        if (selected().isEmpty()) selectedId = "";
        return true;
    }

    /** Invalidates replies even when this screen is kept alive across a logout. */
    public void clear() {
        clearSnapshot();
        worldId = "";
        dimensionId = "";
        desiredViewport = null;
        sentViewport = null;
        sentRequestId = -1;
        lastSentAt = Long.MIN_VALUE;
        inspectionEnabled = false;
    }

    private void clearSnapshot() {
        routes = List.of();
        selectedId = "";
        truncated = false;
    }

    public record Request(UUID sessionId, long requestId, String worldId, String dimensionId, MapRouteViewport viewport) { }
}
