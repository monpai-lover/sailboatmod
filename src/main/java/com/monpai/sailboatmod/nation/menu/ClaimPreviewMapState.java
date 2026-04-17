package com.monpai.sailboatmod.nation.menu;

import java.util.List;

public record ClaimPreviewMapState(long revision,
                                   int radius,
                                   int centerChunkX,
                                   int centerChunkZ,
                                   boolean loading,
                                   boolean ready,
                                   List<Integer> colors) {
    public ClaimPreviewMapState {
        radius = Math.max(0, radius);
        colors = colors == null ? List.of() : colors.stream()
                .map(color -> 0xFF000000 | (color & 0x00FFFFFF))
                .toList();
    }

    public static ClaimPreviewMapState empty() {
        return new ClaimPreviewMapState(0L, 0, 0, 0, false, false, List.of());
    }

    public static ClaimPreviewMapState loading(long revision, int radius, int centerChunkX, int centerChunkZ) {
        return new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, true, false, List.of());
    }

    public static ClaimPreviewMapState ready(long revision, int radius, int centerChunkX, int centerChunkZ, List<Integer> colors) {
        return new ClaimPreviewMapState(revision, radius, centerChunkX, centerChunkZ, false, true, colors);
    }
}
