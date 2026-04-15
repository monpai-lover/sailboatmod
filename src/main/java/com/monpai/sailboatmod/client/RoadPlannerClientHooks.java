package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.RoadPlannerOptionSelectionScreen;
import com.monpai.sailboatmod.client.screen.RoadPlannerTargetSelectionScreen;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RoadPlannerClientHooks {
    private static final long PLANNING_PROGRESS_TIMEOUT_MS = 5_000L;
    private static final long PLANNING_TERMINAL_HOLD_MS = 2_500L;
    private static final long PLANNING_SUCCESS_HOLD_MS = 600L;
    private static final long PLANNING_SMOOTH_MS = 250L;

    public record TargetEntry(String townId, String townName, int distanceBlocks) {
    }

    public record PreviewOption(String optionId, String label, int pathNodeCount, boolean bridgeBacked) {
        public PreviewOption {
            optionId = optionId == null ? "" : optionId;
            label = label == null ? "" : label;
            pathNodeCount = Math.max(0, pathNodeCount);
        }
    }

    public record PreviewGhostBlock(BlockPos pos, BlockState state) {
        public PreviewGhostBlock {
            pos = pos == null ? null : pos.immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }

    public record PreviewState(String sourceTownName,
                               String targetTownName,
                               List<PreviewGhostBlock> ghostBlocks,
                               List<BlockPos> pathNodes,
                               int pathNodeCount,
                               BlockPos startHighlightPos,
                               BlockPos endHighlightPos,
                               BlockPos focusPos,
                               boolean awaitingConfirmation,
                               List<PreviewOption> options,
                               String selectedOptionId) {
        public PreviewState {
            ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
            pathNodes = immutablePositions(pathNodes);
            pathNodeCount = Math.max(0, pathNodeCount);
            startHighlightPos = immutable(startHighlightPos);
            endHighlightPos = immutable(endHighlightPos);
            focusPos = immutable(focusPos);
            options = options == null ? List.of() : List.copyOf(options);
            selectedOptionId = selectedOptionId == null ? "" : selectedOptionId;
        }
    }

    public record ProgressState(String roadId,
                                String sourceTownName,
                                String targetTownName,
                                BlockPos focusPos,
                                int progressPercent,
                                int activeWorkers) {
        public ProgressState {
            focusPos = immutable(focusPos);
        }
    }

    public record PlanningProgressState(long requestId,
                                        String sourceTownName,
                                        String targetTownName,
                                        String stageKey,
                                        String stageLabel,
                                        int serverPercent,
                                        int displayPercent,
                                        int stagePercent,
                                        SyncManualRoadPlanningProgressPacket.Status status,
                                        long updatedAtMs,
                                        long animationStartedAtMs,
                                        long clearAfterMs) {
        public PlanningProgressState {
            sourceTownName = sourceTownName == null ? "" : sourceTownName;
            targetTownName = targetTownName == null ? "" : targetTownName;
            stageKey = stageKey == null ? "" : stageKey;
            stageLabel = stageLabel == null ? "" : stageLabel;
            serverPercent = clampPercent(serverPercent);
            displayPercent = clampPercent(displayPercent);
            stagePercent = clampPercent(stagePercent);
            status = status == null ? SyncManualRoadPlanningProgressPacket.Status.RUNNING : status;
        }
    }

    private static PreviewState previewState;
    private static List<ProgressState> activeProgress = List.of();
    private static long lastProgressSyncAtMs = 0L;
    private static PlanningProgressState planningProgressState;

    public static void openTargetSelection(boolean offhand, String sourceTownName, List<TargetEntry> entries, String selectedTownId) {
        Minecraft.getInstance().setScreen(new RoadPlannerTargetSelectionScreen(offhand, sourceTownName, entries, selectedTownId));
    }

    public static void openPreviewOptionSelection(String sourceTownName,
                                                  String targetTownName,
                                                  List<PreviewOption> options,
                                                  String selectedOptionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (options == null || options.size() < 2) {
            return;
        }
        if (minecraft.screen instanceof RoadPlannerOptionSelectionScreen) {
            return;
        }
        minecraft.setScreen(new RoadPlannerOptionSelectionScreen(sourceTownName, targetTownName, options, selectedOptionId));
    }

    public static void updatePreview(PreviewState preview) {
        previewState = preview;
        if (preview != null) {
            clearPlanningProgress();
        }
    }

    public static void clearPreview() {
        previewState = null;
    }

    public static PreviewState previewState() {
        return previewState;
    }

    public static void updatePlanningProgress(SyncManualRoadPlanningProgressPacket packet) {
        updatePlanningProgressAt(packet, System.currentTimeMillis());
    }

    public static PlanningProgressState activePlanningProgress() {
        return activePlanningProgressAt(System.currentTimeMillis());
    }

    public static void clearPlanningProgress() {
        planningProgressState = null;
    }

    public static void updateProgress(List<ProgressState> progress) {
        activeProgress = List.copyOf(progress);
        lastProgressSyncAtMs = System.currentTimeMillis();
    }

    public static List<ProgressState> activeProgress() {
        if ((System.currentTimeMillis() - lastProgressSyncAtMs) > 4000L) {
            activeProgress = List.of();
        }
        return new ArrayList<>(activeProgress);
    }

    public static void resetStateForTest() {
        previewState = null;
        activeProgress = List.of();
        lastProgressSyncAtMs = 0L;
        planningProgressState = null;
    }

    static void setLastProgressSyncAtMsForTest(long timestamp) {
        lastProgressSyncAtMs = timestamp;
    }

    public static void updatePlanningProgressForTest(SyncManualRoadPlanningProgressPacket packet, long nowMs) {
        updatePlanningProgressAt(packet, nowMs);
    }

    public static PlanningProgressState activePlanningProgressForTest(long nowMs) {
        return activePlanningProgressAt(nowMs);
    }

    private RoadPlannerClientHooks() {
    }

    private static void updatePlanningProgressAt(SyncManualRoadPlanningProgressPacket packet, long nowMs) {
        if (packet == null || packet.requestId() < 0L || packet.stageKey().isBlank()) {
            return;
        }
        PlanningProgressState existing = planningProgressState;
        if (existing != null && packet.requestId() < existing.requestId()) {
            return;
        }
        if (existing != null
                && packet.requestId() == existing.requestId()
                && packet.overallPercent() < existing.serverPercent()) {
            return;
        }
        int startPercent = packet.status() == SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ? existingDisplayPercent(existing, nowMs, packet.requestId())
                : packet.overallPercent();
        long clearAfterMs = packet.status() == SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ? Long.MAX_VALUE
                : nowMs + (packet.status() == SyncManualRoadPlanningProgressPacket.Status.SUCCESS
                ? PLANNING_SUCCESS_HOLD_MS
                : PLANNING_TERMINAL_HOLD_MS);
        planningProgressState = new PlanningProgressState(
                packet.requestId(),
                packet.sourceTownName(),
                packet.targetTownName(),
                packet.stageKey(),
                packet.stageLabel(),
                packet.overallPercent(),
                startPercent,
                packet.stagePercent(),
                packet.status(),
                nowMs,
                nowMs,
                clearAfterMs
        );
    }

    private static PlanningProgressState activePlanningProgressAt(long nowMs) {
        PlanningProgressState state = planningProgressState;
        if (state == null) {
            return null;
        }
        if (state.status() == SyncManualRoadPlanningProgressPacket.Status.RUNNING
                && (nowMs - state.updatedAtMs()) > PLANNING_PROGRESS_TIMEOUT_MS) {
            planningProgressState = null;
            return null;
        }
        if (state.status() != SyncManualRoadPlanningProgressPacket.Status.RUNNING
                && nowMs > state.clearAfterMs()) {
            planningProgressState = null;
            return null;
        }
        int displayPercent = state.status() == SyncManualRoadPlanningProgressPacket.Status.RUNNING
                ? interpolateDisplayPercent(state, nowMs)
                : state.serverPercent();
        return new PlanningProgressState(
                state.requestId(),
                state.sourceTownName(),
                state.targetTownName(),
                state.stageKey(),
                state.stageLabel(),
                state.serverPercent(),
                displayPercent,
                state.stagePercent(),
                state.status(),
                state.updatedAtMs(),
                state.animationStartedAtMs(),
                state.clearAfterMs()
        );
    }

    private static int existingDisplayPercent(PlanningProgressState state, long nowMs, long requestId) {
        if (state == null || state.requestId() != requestId) {
            return 0;
        }
        return interpolateDisplayPercent(state, nowMs);
    }

    private static int interpolateDisplayPercent(PlanningProgressState state, long nowMs) {
        if (state == null) {
            return 0;
        }
        int target = clampPercent(state.serverPercent());
        int start = clampPercent(state.displayPercent());
        if (target <= start) {
            return target;
        }
        double elapsed = Math.max(0L, nowMs - state.animationStartedAtMs());
        double alpha = Math.min(1.0D, elapsed / (double) PLANNING_SMOOTH_MS);
        int interpolated = start + (int) Math.floor((target - start) * alpha);
        return Math.min(target, interpolated);
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copied = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (pos != null) {
                copied.add(pos.immutable());
            }
        }
        return copied.isEmpty() ? List.of() : List.copyOf(copied);
    }

    private static BlockPos immutable(BlockPos pos) {
        return pos == null ? null : pos.immutable();
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
