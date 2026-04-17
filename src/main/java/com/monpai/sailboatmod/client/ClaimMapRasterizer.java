package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;

import java.util.Arrays;
import java.util.List;

public final class ClaimMapRasterizer {
    public static final int DEFAULT_COLOR = 0xFF33414A;

    private ClaimMapRasterizer() {
    }

    public static int[] rasterize(int radius,
                                  List<Integer> terrainColors,
                                  List<NationOverviewClaim> claims,
                                  int centerChunkX,
                                  int centerChunkZ) {
        int chunkDiameter = Math.max(1, radius * 2 + 1);
        int chunkCount = chunkDiameter * chunkDiameter;
        int sub = detectSubCellsPerChunk(terrainColors == null ? 0 : terrainColors.size(), chunkCount);
        int cellsPerChunk = sub * sub;
        int[] pixels = new int[chunkCount * cellsPerChunk];
        Arrays.fill(pixels, DEFAULT_COLOR);

        if (terrainColors != null) {
            int limit = Math.min(terrainColors.size(), pixels.length);
            for (int i = 0; i < limit; i++) {
                pixels[i] = normalizeColor(terrainColors.get(i));
            }
        }

        if (claims != null) {
            for (NationOverviewClaim claim : claims) {
                if (claim == null) {
                    continue;
                }
                int localX = claim.chunkX() - centerChunkX + radius;
                int localZ = claim.chunkZ() - centerChunkZ + radius;
                if (localX < 0 || localZ < 0 || localX >= chunkDiameter || localZ >= chunkDiameter) {
                    continue;
                }
                int chunkIndex = localZ * chunkDiameter + localX;
                int start = chunkIndex * cellsPerChunk;
                Arrays.fill(pixels, start, start + cellsPerChunk, normalizeColor(claim.primaryColorRgb()));
            }
        }

        return pixels;
    }

    static int detectSubCellsPerChunk(int terrainColorCount, int chunkCount) {
        if (terrainColorCount <= 0 || chunkCount <= 0 || terrainColorCount % chunkCount != 0) {
            return 1;
        }
        int cellsPerChunk = terrainColorCount / chunkCount;
        int root = (int) Math.round(Math.sqrt(cellsPerChunk));
        return root >= 1 && root * root == cellsPerChunk ? root : 1;
    }

    static int normalizeColor(Integer color) {
        return color == null ? DEFAULT_COLOR : 0xFF000000 | (color & 0x00FFFFFF);
    }
}
