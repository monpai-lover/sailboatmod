package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;

import java.util.Arrays;

/**
 * Chunk colorizer used by the web map region renderer.
 *
 * <p>Unlike the old per-chunk merge path, terrain brightness is based on a continuous lastY column
 * that can be seeded from the chunk north of the current render column. That mirrors squaremap's
 * seam reduction model while keeping Sailboat's existing {@code MapBlockColors} base palette.</p>
 */
public final class MarketWebMapRegionRenderer {
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;

    public int[] renderChunk(MarketWebMapChunkSnapshot snapshot, int[] lastY) {
        int[] pixels = new int[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        if (snapshot == null || snapshot.samples() == null) {
            return pixels;
        }
        int[] safeLastY = ensureLastY(lastY);
        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNK_SIZE; localX++) {
                int index = localZ * MarketWebMapConstants.CHUNK_SIZE + localX;
                RoadMapColumnSample sample = index < snapshot.samples().length ? snapshot.samples()[index] : null;
                pixels[index] = color(sample, safeLastY, localX);
            }
        }
        return pixels;
    }

    public int[] lastYFromBottomRow(MarketWebMapChunkSnapshot snapshot) {
        int[] lastY = unknownLastY();
        if (snapshot == null || snapshot.samples() == null) {
            return lastY;
        }
        int z = MarketWebMapConstants.CHUNK_SIZE - 1;
        for (int x = 0; x < MarketWebMapConstants.CHUNK_SIZE; x++) {
            RoadMapColumnSample sample = snapshot.samples()[z * MarketWebMapConstants.CHUNK_SIZE + x];
            if (sample != null && !isUnavailableSample(sample)) {
                lastY[x] = sample.surfaceY();
            }
        }
        return lastY;
    }

    public int[] unknownLastY() {
        int[] values = new int[MarketWebMapConstants.CHUNK_SIZE];
        Arrays.fill(values, Integer.MIN_VALUE);
        return values;
    }

    private int color(RoadMapColumnSample sample, int[] lastY, int localX) {
        if (sample == null || isUnavailableSample(sample)) {
            return 0x00000000;
        }
        if (sample.water()) {
            return RoadMapRenderStyle.styleWater(sample.waterDepth());
        }
        int previousY = lastY[localX] == Integer.MIN_VALUE ? sample.reliefBaseY() : lastY[localX];
        int delta = sample.surfaceY() - previousY;
        lastY[localX] = sample.surfaceY();
        return RoadMapRenderStyle.styleTerrainByDelta(sample.baseArgb(), delta);
    }

    private int[] ensureLastY(int[] lastY) {
        if (lastY == null || lastY.length < MarketWebMapConstants.CHUNK_SIZE) {
            return unknownLastY();
        }
        return lastY;
    }

    private static boolean isUnavailableSample(RoadMapColumnSample sample) {
        return sample.baseArgb() == UNKNOWN_ARGB
                && sample.surfaceY() == 0
                && sample.reliefBaseY() == 0
                && !sample.water()
                && sample.waterDepth() == 0;
    }
}
