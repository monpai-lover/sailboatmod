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
            // 与 color() 一致:只有【陆地】底行格才作为南邻 region 顶行的种子。水面 / 缺数据格留 MIN_VALUE,
            // 否则南邻 region 顶行跨水算 delta → region 边界一条横线。
            if (sample != null && !isUnavailableSample(sample) && !sample.water()) {
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
            // 缺数据不写黑:返回 UNTOUCHED 保留磁盘旧像素。但必须把该列种子置 MIN_VALUE:否则跨过这段缺口后,
            // 下一个陆地格会拿【缺口以北那个远处陆地格】的旧高度算 delta → 高度差大时整行被错误提亮/压暗 →
            // 较长随机横线(实测残留条纹根因)。置 MIN_VALUE 让缺口南侧第一个陆地格画平(delta=0),不产生假阶差。
            lastY[localX] = Integer.MIN_VALUE;
            return UNTOUCHED;
        }
        if (sample.water()) {
            // 同理:水面格也置种子 MIN_VALUE。水体不规则、水另一侧地形高度突变,若沿用水面以北的陆地高度算
            // 跨水 delta → 一条横线。水面本身按 waterDepth 着色,与高度阴影无关,断开种子最干净。
            lastY[localX] = Integer.MIN_VALUE;
            return RoadMapRenderStyle.styleWater(sample.waterDepth());
        }
        // 北邻种子缺失(lastY==MIN_VALUE:北邻 chunk 未渲染,或上方是水/缺口)时,不算 delta、画平(delta=0),
        // 仍更新 lastY 让更南的格恢复正确种子。等北邻真正渲染到、本 region 重渲时这格补上正确阴影。
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
