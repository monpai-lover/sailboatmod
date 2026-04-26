package com.monpai.sailboatmod.roadplanner.build;

import java.util.List;
import java.util.UUID;

public record RoadBuildJob(UUID jobId,
                           UUID routeId,
                           UUID edgeId,
                           List<RoadBuildStep> steps,
                           int completedSteps,
                           Status status) {
    public RoadBuildJob {
        if (jobId == null || routeId == null || edgeId == null) {
            throw new IllegalArgumentException("ids cannot be null");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (completedSteps < 0 || completedSteps > steps.size()) {
            throw new IllegalArgumentException("completedSteps out of range");
        }
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
    }

    public static RoadBuildJob create(UUID jobId, UUID routeId, UUID edgeId, List<RoadBuildStep> steps) {
        return new RoadBuildJob(jobId, routeId, edgeId, steps, 0, Status.QUEUED);
    }

    public double progress() {
        if (steps.isEmpty()) {
            return 1.0D;
        }
        return completedSteps / (double) steps.size();
    }

    public RoadBuildJob withProgress(int completedSteps, Status status) {
        return new RoadBuildJob(jobId, routeId, edgeId, steps, completedSteps, status);
    }

    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED,
        ROLLING_BACK,
        ROLLED_BACK,
        FAILED
    }
}
