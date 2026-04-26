package com.monpai.sailboatmod.roadplanner.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class RoadBuildQueueService {
    private final Map<UUID, RoadBuildJob> jobs = new LinkedHashMap<>();

    public RoadBuildJob enqueue(UUID routeId, UUID edgeId, List<RoadBuildStep> steps) {
        RoadBuildJob job = RoadBuildJob.create(UUID.randomUUID(), routeId, edgeId, steps);
        jobs.put(job.jobId(), job);
        return job;
    }

    public void tick(int maxSteps, Predicate<RoadBuildStep> executor) {
        if (maxSteps <= 0) {
            return;
        }
        for (RoadBuildJob job : List.copyOf(jobs.values())) {
            if (job.status() != RoadBuildJob.Status.QUEUED && job.status() != RoadBuildJob.Status.RUNNING) {
                continue;
            }
            RoadBuildJob updated = runJob(job, maxSteps, executor);
            jobs.put(job.jobId(), updated);
            return;
        }
    }

    public void cancel(UUID jobId) {
        job(jobId).ifPresent(job -> jobs.put(jobId, job.withProgress(job.completedSteps(), RoadBuildJob.Status.CANCELLED)));
    }

    public Optional<RoadBuildJob> job(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private RoadBuildJob runJob(RoadBuildJob job, int maxSteps, Predicate<RoadBuildStep> executor) {
        int completed = job.completedSteps();
        int remainingBudget = maxSteps;
        while (completed < job.steps().size() && remainingBudget > 0) {
            if (!executor.test(job.steps().get(completed))) {
                return job.withProgress(completed, RoadBuildJob.Status.FAILED);
            }
            completed++;
            remainingBudget--;
        }
        RoadBuildJob.Status status = completed >= job.steps().size() ? RoadBuildJob.Status.COMPLETED : RoadBuildJob.Status.RUNNING;
        return job.withProgress(completed, status);
    }
}
