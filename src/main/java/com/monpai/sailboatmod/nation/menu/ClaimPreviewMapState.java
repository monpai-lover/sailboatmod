package com.monpai.sailboatmod.nation.menu;

import java.util.List;

public record ClaimPreviewMapState(long revision,
                                   int radius,
                                   int centerChunkX,
                                   int centerChunkZ,
                                   boolean loading,
                                   boolean ready,
                                   List<Integer> colors,
                                   int visibleReadyChunkCount,
                                   int visibleChunkCount,
                                   int prefetchReadyChunkCount,
                                   int prefetchChunkCount) {
    public ClaimPreviewMapState {
        radius = Math.max(0, radius);
        colors = colors == null ? List.of() : colors.stream()
                .map(color -> 0xFF000000 | (color & 0x00FFFFFF))
                .toList();
        visibleReadyChunkCount = Math.max(0, visibleReadyChunkCount);
        visibleChunkCount = Math.max(0, visibleChunkCount);
        prefetchReadyChunkCount = Math.max(0, prefetchReadyChunkCount);
        prefetchChunkCount = Math.max(0, prefetchChunkCount);
        if (visibleReadyChunkCount > visibleChunkCount) {
            visibleReadyChunkCount = visibleChunkCount;
        }
        if (prefetchReadyChunkCount > prefetchChunkCount) {
            prefetchReadyChunkCount = prefetchChunkCount;
        }
    }

    public static ClaimPreviewMapState empty() {
        return new ClaimPreviewMapState(0L, 0, 0, 0, false, false, List.of(), 0, 0, 0, 0);
    }

    public static ClaimPreviewMapState loading(long revision, int radius, int centerChunkX, int centerChunkZ) {
        return new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, true, false, List.of(), 0, 0, 0, 0);
    }

    public static ClaimPreviewMapState ready(long revision, int radius, int centerChunkX, int centerChunkZ, List<Integer> colors) {
        return new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, false, true, colors, 0, 0, 0, 0);
    }

    public static ClaimPreviewMapState loading(long revision,
                                               int radius,
                                               int centerChunkX,
                                               int centerChunkZ,
                                               int visibleReadyChunkCount,
                                               int visibleChunkCount,
                                               int prefetchReadyChunkCount,
                                               int prefetchChunkCount) {
        return new ClaimPreviewMapState(
                revision,
                radius,
                centerChunkX,
                centerChunkZ,
                true,
                false,
                List.of(),
                visibleReadyChunkCount,
                visibleChunkCount,
                prefetchReadyChunkCount,
                prefetchChunkCount
        );
    }

    public static ClaimPreviewMapState ready(long revision,
                                             int radius,
                                             int centerChunkX,
                                             int centerChunkZ,
                                             List<Integer> colors,
                                             int visibleReadyChunkCount,
                                             int visibleChunkCount,
                                             int prefetchReadyChunkCount,
                                             int prefetchChunkCount) {
        return new ClaimPreviewMapState(
                revision,
                radius,
                centerChunkX,
                centerChunkZ,
                false,
                true,
                colors,
                visibleReadyChunkCount,
                visibleChunkCount,
                prefetchReadyChunkCount,
                prefetchChunkCount
        );
    }

    public ClaimPreviewMapState(long revision,
                                int radius,
                                int centerChunkX,
                                int centerChunkZ,
                                boolean loading,
                                boolean ready,
                                List<Integer> colors) {
        this(revision, radius, centerChunkX, centerChunkZ, loading, ready, colors, 0, 0, 0, 0);
    }

    public boolean hasPendingProgress() {
        return (visibleChunkCount > 0 && visibleReadyChunkCount < visibleChunkCount)
                || (prefetchChunkCount > 0 && prefetchReadyChunkCount < prefetchChunkCount);
    }
}
