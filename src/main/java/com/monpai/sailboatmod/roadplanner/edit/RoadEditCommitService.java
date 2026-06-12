package com.monpai.sailboatmod.roadplanner.edit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RoadEditCommitService {
    public Result queueEdit(RoadEditableNetworkSavedData data,
                            RoadEditableRecord current,
                            List<RoadEditableSegment> proposedSegments,
                            List<RoadEditBlockPlacement> proposedPlacements,
                            RoadEditTaskService tasks,
                            long timestamp) {
        if (data == null || current == null || tasks == null) {
            return new Result(false, Optional.empty(),
                    new RoadEditDiff("", List.of(), List.of(), List.of(), List.of()));
        }
        RoadEditDiff diff = RoadEditDiffPlanner.plan(current, proposedPlacements);
        if (!diff.conflicts().isEmpty()) {
            return new Result(false, Optional.empty(), diff);
        }
        RoadEditableRecord editing = copyWith(current, proposedSegments, RoadEditableRecord.Status.EDITING, timestamp);
        RoadEditableRecord built = copyWith(current, proposedSegments, RoadEditableRecord.Status.BUILT, timestamp);
        data.putRoad(editing);
        if (!diff.hasWork()) {
            data.putRoad(built);
            return new Result(true, Optional.empty(), diff);
        }
        UUID jobId = tasks.submit(diff, built);
        return new Result(true, Optional.of(jobId), diff);
    }

    private static RoadEditableRecord copyWith(RoadEditableRecord current,
                                               List<RoadEditableSegment> segments,
                                               RoadEditableRecord.Status status,
                                               long timestamp) {
        return new RoadEditableRecord(
                current.roadId(),
                current.edgeId(),
                current.dimensionId(),
                current.ownerNationId(),
                current.ownerTownId(),
                current.creatorUuid(),
                current.creatorName(),
                current.sourceTownId(),
                current.targetTownId(),
                current.sourceTownName(),
                current.targetTownName(),
                current.width(),
                current.materialId(),
                status,
                current.legacyMigrated(),
                current.nodes(),
                segments == null ? List.of() : segments,
                current.createdAt(),
                Math.max(timestamp, current.updatedAt()));
    }

    public record Result(boolean success, Optional<UUID> jobId, RoadEditDiff diff) {
        public Result {
            jobId = jobId == null ? Optional.empty() : jobId;
            diff = diff == null ? new RoadEditDiff("", List.of(), List.of(), List.of(), List.of()) : diff;
        }
    }
}
