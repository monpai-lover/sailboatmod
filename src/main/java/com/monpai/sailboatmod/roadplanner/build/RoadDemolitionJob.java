package com.monpai.sailboatmod.roadplanner.build;

import java.util.List;
import java.util.UUID;

public record RoadDemolitionJob(UUID jobId,
                                UUID routeId,
                                List<UUID> edgeIds,
                                List<RoadRollbackEntry> restoreEntries,
                                List<Issue> issues,
                                Status status) {
    public RoadDemolitionJob {
        if (jobId == null || routeId == null) {
            throw new IllegalArgumentException("ids cannot be null");
        }
        edgeIds = edgeIds == null ? List.of() : List.copyOf(edgeIds);
        restoreEntries = restoreEntries == null ? List.of() : List.copyOf(restoreEntries);
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
    }

    public record Issue(String message, boolean blocking) {
    }

    public enum Status {
        PLANNED,
        RUNNING,
        COMPLETED,
        CANCELLED,
        BLOCKED
    }
}
