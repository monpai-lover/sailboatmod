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
            return UNTOUCHED; // 缺数据不写黑:返回 UNTOUCHED,merge 时跳过保留磁盘旧像素(根除渲染产生的黑块/黑条)。
        }
        if (sample.water()) {
            return RoadMapRenderStyle.styleWater(sample.waterDepth());
        }
        // 北邻种子缺失(lastY==MIN_VALUE,北邻 chunk 未渲染)时:不用 reliefBaseY 兜底算 delta。
        // 兜底取的是自己南/西格高度,几乎总≠真北邻 → delta 非零 → 整行被错误提亮 → 水平浅线(条纹根因)。
        // 改为 delta=0(原色,无阴影):该格暂时不画高度阴影,但仍更新 lastY 让南边格恢复正确种子;
        // 等北邻 chunk 真正渲染到、本 region 重渲时,这格才补上正确阴影。线彻底消失。
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
