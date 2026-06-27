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
            // 与 color() 一致:底行【陆地和水面】都作为南邻 region 顶行的种子(水面用水面 Y,原版地图水也参与高度比较)。
            // 只有真缺数据格留 MIN_VALUE。这样南邻 region 顶行种子连续,无 region 边界横线。
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
        // squaremap / 原版地图风格:高度阴影 = 本格与【正北格】真实高度差,且种子(lastY)必须【沿列连续传递】、
        // 永不在中途断成 MIN_VALUE。横线的真正根因是"种子断点":某些格画平(delta=0)、紧邻的格有阴影 → 同一行
        // 出现明暗不一致 → 横线。只要种子连续,相邻行高度差平滑,就不会有线。
        if (sample == null || isUnavailableSample(sample)) {
            // 缺数据格:无 Y 可用,返回 UNTOUCHED 保留磁盘旧像素。【保持 lastY 不变】(不重置 MIN_VALUE):
            // 让缺口南侧的格仍用"缺口以北最近的已知高度"做种子 → 连续、不画平、不产生不一致横线。
            return UNTOUCHED;
        }
        if (sample.water()) {
            // 水面格:按 waterDepth 着色。但【仍把 lastY 更新为水面 Y】(原版地图水也参与北邻高度比较):
            // 这样水南岸的陆地格 delta = 陆地高度 - 水面高度,是真实落差、连续 → 无跨水横线。
            lastY[localX] = sample.surfaceY();
            return RoadMapRenderStyle.styleWater(sample.waterDepth());
        }
        // 仅当从没有过任何北邻种子(lastY==MIN_VALUE:本列从 region 顶行起就没拿到 seam,整列开头)时画平(delta=0)。
        // 这是一致 fallback,只出现在 region 顶边一行、且整片一致,不形成可见线。其余情况种子都连续。
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
