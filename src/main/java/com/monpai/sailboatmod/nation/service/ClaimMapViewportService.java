package com.monpai.sailboatmod.nation.service;

import java.util.ArrayList;
import java.util.List;

public class ClaimMapViewportService {
    @FunctionalInterface
    public interface TileLookup {
        int[] get(String dimensionId, int chunkX, int chunkZ);
    }

    public record ViewportRequest(String screenKind,
                                  String dimensionId,
                                  long revision,
                                  int radius,
                                  int centerChunkX,
                                  int centerChunkZ,
                                  int prefetchRadius) {
    }

    private final TileLookup tileLookup;
    private int pendingVisibleChunkCount;
    private int pendingPrefetchChunkCount;

    public ClaimMapViewportService(TileLookup tileLookup) {
        this.tileLookup = tileLookup;
    }

    public ClaimMapViewportSnapshot tryBuildSnapshot(ViewportRequest request, List<Integer> claimOverlayColors) {
        pendingVisibleChunkCount = 0;
        ArrayList<Integer> pixels = new ArrayList<>();
        for (int dz = -request.radius(); dz <= request.radius(); dz++) {
            for (int dx = -request.radius(); dx <= request.radius(); dx++) {
                int[] tile = tileLookup.get(request.dimensionId(), request.centerChunkX() + dx, request.centerChunkZ() + dz);
                if (tile == null) {
                    pendingVisibleChunkCount++;
                    continue;
                }
                for (int color : tile) {
                    pixels.add(color);
                }
            }
        }
        pendingPrefetchChunkCount = countMissingPrefetchChunks(request);
        if (pendingVisibleChunkCount > 0) {
            return null;
        }
        return new ClaimMapViewportSnapshot(
                request.dimensionId(),
                request.revision(),
                request.radius(),
                request.centerChunkX(),
                request.centerChunkZ(),
                pixels
        );
    }

    private int countMissingPrefetchChunks(ViewportRequest request) {
        int outer = request.radius() + Math.max(0, request.prefetchRadius());
        if (outer <= request.radius()) {
            return 0;
        }
        int missingCount = 0;
        for (int dz = -outer; dz <= outer; dz++) {
            for (int dx = -outer; dx <= outer; dx++) {
                if (Math.abs(dx) <= request.radius() && Math.abs(dz) <= request.radius()) {
                    continue;
                }
                int[] tile = tileLookup.get(request.dimensionId(), request.centerChunkX() + dx, request.centerChunkZ() + dz);
                if (tile == null) {
                    missingCount++;
                }
            }
        }
        return missingCount;
    }

    int pendingVisibleChunkCountForTest() {
        return pendingVisibleChunkCount;
    }

    int pendingPrefetchChunkCountForTest() {
        return pendingPrefetchChunkCount;
    }
}
