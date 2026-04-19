package com.monpai.sailboatmod.nation.service;

import java.util.List;

public record ClaimMapViewportSnapshot(String dimensionId,
                                       long revision,
                                       int radius,
                                       int centerChunkX,
                                       int centerChunkZ,
                                       List<Integer> pixels,
                                       boolean complete) {
    public ClaimMapViewportSnapshot {
        dimensionId = dimensionId == null ? "" : dimensionId;
        pixels = pixels == null ? List.of() : List.copyOf(pixels);
    }

    public ClaimMapViewportSnapshot(String dimensionId,
                                    long revision,
                                    int radius,
                                    int centerChunkX,
                                    int centerChunkZ,
                                    List<Integer> pixels) {
        this(dimensionId, revision, radius, centerChunkX, centerChunkZ, pixels, true);
    }
}
