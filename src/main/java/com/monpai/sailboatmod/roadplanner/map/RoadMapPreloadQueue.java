package com.monpai.sailboatmod.roadplanner.map;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

public class RoadMapPreloadQueue {
    private final PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator
            .comparingInt((Entry entry) -> priorityRank(entry.priority()))
            .thenComparingLong(Entry::sequence));
    private UUID activeRequestId;
    private long sequence;

    public void replaceRequest(UUID requestId, List<RoadMapCorridorRegion> regions) {
        activeRequestId = requestId == null ? new UUID(0L, 0L) : requestId;
        queue.clear();
        if (regions == null) {
            return;
        }
        for (RoadMapCorridorRegion region : regions) {
            queue.add(new Entry(activeRequestId, region.region(), region.priority(), false, sequence++));
        }
    }

    public Optional<Entry> poll() {
        while (!queue.isEmpty()) {
            Entry entry = queue.poll();
            if (!entry.cancelled() && entry.requestId().equals(activeRequestId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private static int priorityRank(RoadMapRegionPriority priority) {
        return switch (priority) {
            case CURRENT -> 0;
            case MANUAL_ROUTE -> 1;
            case ROUGH_PATH -> 2;
            case DESTINATION -> 3;
            case NEIGHBOR -> 4;
        };
    }

    public record Entry(UUID requestId,
                        RoadMapRegion region,
                        RoadMapRegionPriority priority,
                        boolean cancelled,
                        long sequence) {
    }
}
