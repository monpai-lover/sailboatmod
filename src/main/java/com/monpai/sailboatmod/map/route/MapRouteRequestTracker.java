package com.monpai.sailboatmod.map.route;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread owner generations; each accepted view invalidates every previous completion. */
final class MapRouteRequestTracker {
    private final Map<UUID, Generation> current = new LinkedHashMap<>();
    private long sequence;

    record Generation(UUID sessionId, long requestId, String worldId, String dimensionId, long sequence, long touched) { }

    Generation accept(UUID owner, UUID session, long request, String world, String dimension, long tick) {
        Generation previous = current.get(owner);
        if (request < 0 || previous != null && previous.sessionId().equals(session) && request <= previous.requestId()) return null;
        Generation next = new Generation(session, request, world, dimension, ++sequence, tick);
        current.put(owner, next);
        return next;
    }

    boolean current(UUID owner, Generation generation, String dimension) {
        return current.get(owner) == generation && generation.dimensionId().equals(dimension);
    }

    void forget(UUID owner) { current.remove(owner); }
    void clear() { current.clear(); }
}
