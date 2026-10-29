package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;

import java.util.Arrays;

/**
 * Chunk colorizer used by the web map region renderer.
 *
 * <p>Terrain brightness compares adjacent north/south samples. The first row is seeded from the
 * immediate northern chunk; unavailable samples break the comparison instead of creating a slope
 * across an unknown gap.</p>
 */
public final class MarketWebMapRegionRenderer {
    private static final int UNKNOWN_ARGB = 0xFF2A2A2A;
    /** 与 MarketWebMapRegionImage.UNTOUCHED 一致:缺数据像素返回此值,mergeTouchedPixels 会跳过它、保留磁盘旧值,绝不写黑。 */
    private static final int UNTOUCHED = Integer.MIN_VALUE;

    public int[] renderChunk(MarketWebMapChunkSnapshot snapshot, int[] lastY) {
        int[] pixels = new int[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        // 缺数据(null sample)默认 UNTOUCHED:不写黑、merge 时跳过保留旧像素。整块缺数据 → 整块 UNTOUCHED → 不污染底图。
        Arrays.fill(pixels, UNTOUCHED);
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
            int index = z * MarketWebMapConstants.CHUNK_SIZE + x;
            RoadMapColumnSample sample = index < snapshot.samples().length ? snapshot.samples()[index] : null;
            // 接缝只记录真正的最南行;缺数据时不能拿更北的高度代替。
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
            // 保留旧像素,但断开高度比较:下一个有效格的正北高度已经未知。
            lastY[localX] = Integer.MIN_VALUE;
            return UNTOUCHED;
        }
        if (sample.water()) {
            // 水面格:按 waterDepth 着色。但【仍把 lastY 更新为水面 Y】(原版地图水也参与北邻高度比较):
            // 这样水南岸的陆地格 delta = 陆地高度 - 水面高度,是真实落差、连续 → 无跨水横线。
            lastY[localX] = sample.surfaceY();
            return RoadMapRenderStyle.styleWater(sample.waterDepth());
        }
        // 没有紧邻的北格时按平地着色,不把远处的高度误当成一个方块内的落差。
        boolean seedMissing = lastY[localX] == Integer.MIN_VALUE;
        int delta = seedMissing ? 0 : sample.surfaceY() - lastY[localX];
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
