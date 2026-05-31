package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerAutoMergeRouteSyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RoadPlannerAutoMergeState(Status status,
                                        UUID requestId,
                                        RoadPlannerMergeSelection selection,
                                        List<BlockPos> displayPath,
                                        List<RoadPlannerSharedRoadSpan> sharedSpans,
                                        List<String> roadIds,
                                        String message) {
    public RoadPlannerAutoMergeState {
        status = status == null ? Status.IDLE : status;
        requestId = requestId == null ? new UUID(0L, 0L) : requestId;
        selection = selection == null ? RoadPlannerMergeSelection.none() : selection;
        displayPath = displayPath == null ? List.of() : displayPath.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
        sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .toList();
        roadIds = roadIds == null ? List.of() : roadIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .toList();
        message = message == null ? "" : message.trim();
    }

    public static RoadPlannerAutoMergeState idle() {
        return new RoadPlannerAutoMergeState(Status.IDLE, new UUID(0L, 0L), RoadPlannerMergeSelection.none(),
                List.of(), List.of(), List.of(), "");
    }

    public static RoadPlannerAutoMergeState pending(UUID requestId) {
        return new RoadPlannerAutoMergeState(Status.PENDING, requestId, RoadPlannerMergeSelection.none(),
                List.of(), List.of(), List.of(), "");
    }

    public static RoadPlannerAutoMergeState fromSync(RoadPlannerAutoMergeRouteSyncPacket packet) {
        if (packet == null) {
            return idle();
        }
        if (packet.status() == RoadPlannerAutoMergeRouteSyncPacket.Status.FOUND && packet.mergeSelection().present()) {
            return new RoadPlannerAutoMergeState(Status.FOUND, packet.requestId(), packet.mergeSelection(),
                    packet.displayPath(), packet.sharedSpans(), packet.roadIds(), packet.message());
        }
        return new RoadPlannerAutoMergeState(Status.NOT_FOUND, packet.requestId(), RoadPlannerMergeSelection.none(),
                List.of(), List.of(), List.of(), packet.message());
    }

    public RoadPlannerAutoMergeState manualFallback() {
        return new RoadPlannerAutoMergeState(Status.MANUAL, requestId, RoadPlannerMergeSelection.none(),
                List.of(), List.of(), List.of(), message);
    }

    public boolean found() {
        return status == Status.FOUND && selection.present();
    }

    public boolean pending() {
        return status == Status.PENDING;
    }

    public enum Status {
        IDLE,
        PENDING,
        FOUND,
        NOT_FOUND,
        MANUAL
    }
}
