package com.monpai.sailboatmod.roadplanner.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts how many preview jobs hold each chunk. A chunk ticket is shared by everyone who adds the same one,
 * so without counting, the first job to finish would unload chunks another player's job is still waiting for.
 * Main thread only.
 */
public final class RoadChunkTicketRefs {
    private final Map<Long, Integer> holders = new HashMap<>();

    /** True for the first holder. Finite-lived tickets must still be refreshed when another holder joins. */
    public boolean acquire(long chunkKey) {
        return holders.merge(chunkKey, 1, Integer::sum) == 1;
    }

    /** True when the last holder let go: the caller removes the ticket. */
    public boolean release(long chunkKey) {
        Integer count = holders.get(chunkKey);
        if (count == null) {
            return false;
        }
        if (count <= 1) {
            holders.remove(chunkKey);
            return true;
        }
        holders.put(chunkKey, count - 1);
        return false;
    }

    public int size() {
        return holders.size();
    }

    public void clear() {
        holders.clear();
    }
}
