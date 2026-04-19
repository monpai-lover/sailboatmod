package com.monpai.sailboatmod.nation.service;

import java.util.ArrayList;
import java.util.List;

public class ClaimMapViewportService {
    private static final int DEFAULT_TERRAIN_COLOR = 0xFF33414A;

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
        ClaimPreviewTerrainService.enqueueViewportWork(
                request.dimensionId(),
                request.centerChunkX(),
                request.centerChunkZ(),
                request.radius(),
                request.prefetchRadius(),
                request.revision(),
                request.screenKind()
        );
        pendingVisibleChunkCount = 0;
        ArrayList<Integer> pixels = new ArrayList<>();
        int colorsPerTile = Math.max(1, ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB);
        for (int dz = -request.radius(); dz <= request.radius(); dz++) {
            for (int dx = -request.radius(); dx <= request.radius(); dx++) {
                int chunkX = request.centerChunkX() + dx;
                int chunkZ = request.centerChunkZ() + dz;
                int[] tile = tileLookup.get(request.dimensionId(), chunkX, chunkZ);
                if (tile == null) {
                    pendingVisibleChunkCount++;
                    appendTilePixels(pixels, null, colorsPerTile);
                    continue;
                }
                ClaimPreviewTerrainService.trackViewportDependency(request.dimensionId(), chunkX, chunkZ, request.screenKind());
                appendTilePixels(pixels, tile, colorsPerTile);
            }
        }
        pendingPrefetchChunkCount = countMissingPrefetchChunks(request);
        ClaimMapViewportSnapshot snapshot = new ClaimMapViewportSnapshot(
                request.dimensionId(),
                request.revision(),
                request.radius(),
                request.centerChunkX(),
                request.centerChunkZ(),
                pixels,
                pendingVisibleChunkCount == 0
        );
        return snapshot;
    }

    private static void appendTilePixels(List<Integer> pixels, int[] tile, int colorsPerTile) {
        if (pixels == null || colorsPerTile <= 0) {
            return;
        }
        if (tile == null || tile.length == 0) {
            for (int i = 0; i < colorsPerTile; i++) {
                pixels.add(DEFAULT_TERRAIN_COLOR);
            }
            return;
        }
        int lastColor = tile[Math.max(0, tile.length - 1)];
        for (int i = 0; i < colorsPerTile; i++) {
            pixels.add(i < tile.length ? tile[i] : lastColor);
        }
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

    static String viewportKeyForTest(String screenKey, String dimensionId, int centerChunkX, int centerChunkZ, long revision) {
        return viewportKey(screenKey, dimensionId, centerChunkX, centerChunkZ, revision);
    }

    static String viewportKey(ViewportRequest request) {
        return viewportKey(
                request.screenKind(),
                request.dimensionId(),
                request.centerChunkX(),
                request.centerChunkZ(),
                request.revision()
        );
    }

    static String viewportKey(String screenKey, String dimensionId, int centerChunkX, int centerChunkZ, long revision) {
        return (screenKey == null ? "" : screenKey)
                + "|"
                + (dimensionId == null ? "" : dimensionId)
                + "|"
                + centerChunkX
                + "|"
                + centerChunkZ
                + "|"
                + revision;
    }
}
