package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.UUID;

public final class RoadBuildSavedData {
    private RoadBuildSavedData() {
    }

    public record Snapshot(List<JobSnapshot> jobs) {
        public Snapshot {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
        }

        public static Snapshot fromJobs(List<RoadBuildJob> jobs) {
            return new Snapshot(jobs.stream().map(JobSnapshot::from).toList());
        }

        public CompoundTag encode() {
            CompoundTag tag = new CompoundTag();
            ListTag jobTags = new ListTag();
            for (JobSnapshot job : jobs) {
                jobTags.add(job.encode());
            }
            tag.put("Jobs", jobTags);
            return tag;
        }

        public static Snapshot decode(CompoundTag tag) {
            ListTag jobTags = tag.getList("Jobs", Tag.TAG_COMPOUND);
            return new Snapshot(jobTags.stream()
                    .map(CompoundTag.class::cast)
                    .map(JobSnapshot::decode)
                    .toList());
        }
    }

    public record JobSnapshot(UUID jobId,
                              UUID routeId,
                              UUID edgeId,
                              int stepCount,
                              int completedSteps,
                              RoadBuildJob.Status status) {
        public static JobSnapshot from(RoadBuildJob job) {
            return new JobSnapshot(job.jobId(), job.routeId(), job.edgeId(), job.steps().size(), job.completedSteps(), job.status());
        }

        public CompoundTag encode() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("JobId", jobId);
            tag.putUUID("RouteId", routeId);
            tag.putUUID("EdgeId", edgeId);
            tag.putInt("StepCount", stepCount);
            tag.putInt("CompletedSteps", completedSteps);
            tag.putString("Status", status.name());
            return tag;
        }

        public static JobSnapshot decode(CompoundTag tag) {
            return new JobSnapshot(
                    tag.getUUID("JobId"),
                    tag.getUUID("RouteId"),
                    tag.getUUID("EdgeId"),
                    tag.getInt("StepCount"),
                    tag.getInt("CompletedSteps"),
                    RoadBuildJob.Status.valueOf(tag.getString("Status"))
            );
        }
    }
}
