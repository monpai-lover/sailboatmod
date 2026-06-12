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
        return queueEdit(data, current, current == null ? List.of() : current.nodes(),
                proposedSegments, proposedPlacements, tasks, timestamp);
    }

    public Result queueEdit(RoadEditableNetworkSavedData data,
                            RoadEditableRecord current,
                            List<RoadEditableNode> proposedNodes,
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
        List<RoadEditableNode> safeNodes = proposedNodes == null || proposedNodes.isEmpty() ? current.nodes() : proposedNodes;
        RoadEditableRecord editing = copyWith(current, safeNodes, proposedSegments, RoadEditableRecord.Status.EDITING, timestamp);
        RoadEditableRecord built = copyWith(current, safeNodes, proposedSegments, RoadEditableRecord.Status.BUILT, timestamp);
        data.putRoad(editing);
        if (!diff.hasWork()) {
            data.putRoad(built);
            return new Result(true, Optional.empty(), diff, Optional.of(built));
        }
        UUID jobId = tasks.submit(diff, built);
        return new Result(true, Optional.of(jobId), diff);
    }

    private static RoadEditableRecord copyWith(RoadEditableRecord current,
                                               List<RoadEditableNode> nodes,
                                               List<RoadEditableSegment> segments,
                                               RoadEditableRecord.Status status,
                                               long timestamp) {
        RoadEditableSegment firstSegment = segments == null || segments.isEmpty() ? null : segments.get(0);
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
                firstSegment == null ? current.width() : firstSegment.width(),
                firstSegment == null || firstSegment.materialId().isBlank() ? current.materialId() : firstSegment.materialId(),
                status,
                current.legacyMigrated(),
                nodes == null ? current.nodes() : nodes,
                segments == null ? List.of() : segments,
                current.createdAt(),
                Math.max(timestamp, current.updatedAt()));
    }

    public record Result(boolean success,
                         Optional<UUID> jobId,
                         RoadEditDiff diff,
                         Optional<RoadEditableRecord> completedRecord) {
        public Result(boolean success, Optional<UUID> jobId, RoadEditDiff diff) {
            this(success, jobId, diff, Optional.empty());
        }

        public Result {
            jobId = jobId == null ? Optional.empty() : jobId;
            diff = diff == null ? new RoadEditDiff("", List.of(), List.of(), List.of(), List.of()) : diff;
            completedRecord = completedRecord == null ? Optional.empty() : completedRecord;
        }
    }
}
