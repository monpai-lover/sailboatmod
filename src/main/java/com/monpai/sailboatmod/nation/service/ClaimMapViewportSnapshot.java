package com.monpai.sailboatmod.nation.service;

import java.util.List;

public record ClaimMapViewportSnapshot(String dimensionId,
                                       long revision,
                                       int radius,
                                       int centerChunkX,
                                       int centerChunkZ,
                                       List<Integer> pixels,
                                       boolean complete,
                                       int visibleReadyChunkCount,
                                       int visibleChunkCount,
                                       int prefetchReadyChunkCount,
                                       int prefetchChunkCount) {
    public ClaimMapViewportSnapshot {
        dimensionId = dimensionId == null ? "" : dimensionId;
        pixels = pixels == null ? List.of() : List.copyOf(pixels);
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

    public ClaimMapViewportSnapshot(String dimensionId,
                                    long revision,
                                    int radius,
                                    int centerChunkX,
                                    int centerChunkZ,
                                    List<Integer> pixels) {
        this(dimensionId, revision, radius, centerChunkX, centerChunkZ, pixels, true, 0, 0, 0, 0);
    }

    public ClaimMapViewportSnapshot(String dimensionId,
                                    long revision,
                                    int radius,
                                    int centerChunkX,
                                    int centerChunkZ,
                                    List<Integer> pixels,
                                    boolean complete) {
        this(dimensionId, revision, radius, centerChunkX, centerChunkZ, pixels, complete, 0, 0, 0, 0);
    }
}
