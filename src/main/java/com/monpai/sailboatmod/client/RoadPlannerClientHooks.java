package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.RoadPlannerTargetSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerClientHooks {
    public record TargetEntry(String townId, String townName, int distanceBlocks) {
    }

    public record PreviewState(String sourceTownName,
                               String targetTownName,
                               List<BlockPos> path,
                               boolean awaitingConfirmation) {
    }

    public record ProgressState(String roadId,
                                String sourceTownName,
                                String targetTownName,
                                BlockPos origin,
                                int progressPercent,
                                int activeWorkers) {
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
