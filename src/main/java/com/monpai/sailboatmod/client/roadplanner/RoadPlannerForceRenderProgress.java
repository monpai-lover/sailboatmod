package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerForceRenderProgress(String label, int completedChunks, int totalChunks) {
    public RoadPlannerForceRenderProgress {
        label = label == null ? "" : label;
        completedChunks = Math.max(0, completedChunks);
        totalChunks = Math.max(0, totalChunks);
        if (completedChunks > totalChunks && totalChunks > 0) {
            completedChunks = totalChunks;
        }
    }

    public int percent() {
        if (totalChunks <= 0) {
            return 0;
        }
        return (int) Math.round(completedChunks * 100.0D / totalChunks);
    }

    public boolean active() {
        return totalChunks > 0 && completedChunks < totalChunks;
    }
}
