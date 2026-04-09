package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.RoadPlannerTargetSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RoadPlannerClientHooks {
    public record TargetEntry(String townId, String townName, int distanceBlocks) {
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
                               BlockPos startHighlightPos,
                               BlockPos endHighlightPos,
                               BlockPos focusPos,
                               boolean awaitingConfirmation) {
        public PreviewState {
            ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
            startHighlightPos = startHighlightPos == null ? null : startHighlightPos.immutable();
            endHighlightPos = endHighlightPos == null ? null : endHighlightPos.immutable();
            focusPos = focusPos == null ? null : focusPos.immutable();
        }
    }

    public record ProgressState(String roadId,
                                String sourceTownName,
                                String targetTownName,
                                BlockPos focusPos,
                                int progressPercent,
                                int activeWorkers) {
        public ProgressState {
            focusPos = focusPos == null ? null : focusPos.immutable();
        }
    }

    private static PreviewState previewState;
    private static List<ProgressState> activeProgress = List.of();
    private static long lastProgressSyncAtMs = 0L;

    public static void openTargetSelection(boolean offhand, String sourceTownName, List<TargetEntry> entries, String selectedTownId) {
        Minecraft.getInstance().setScreen(new RoadPlannerTargetSelectionScreen(offhand, sourceTownName, entries, selectedTownId));
    }

    public static void updatePreview(PreviewState preview) {
        previewState = preview;
    }

    public static void clearPreview() {
        previewState = null;
    }

    public static PreviewState previewState() {
        return previewState;
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

    private RoadPlannerClientHooks() {
    }
}
