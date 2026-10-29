package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Tellus 预测层瓦片:与现有方块瓦片同一 512 像素格线,层级 z 每像素 2^z 格,每像素在中心取样一点。
 * 配色与高度阴影沿用现有网页地图(MapBlockColors + RoadMapRenderStyle);取样失败的像素透明。
 */
public final class MarketWebTellusTileRenderer {
    public static final int TILE_PIXELS = 512;
    public static final int MAX_ZOOM = 11;
    private static final int FALLBACK_ARGB = 0xFF7F7F7F;

    /** argb 按行存(py*512+px);failures = 取样失败的像素数。 */
    public record Rendered(int[] argb, int failures) {
        /** 失败不超过一半才存档;否则下次请求重画(多半是 Tellus 数据还没下载好)。 */
        public boolean worthSaving() {
            return (long) failures * 2 <= argb.length;
        }
    }

    private MarketWebTellusTileRenderer() {
    }

    public static long blockMinX(int zoom, int tileX) {
        return ((long) tileX * TILE_PIXELS) << zoom;
    }

    public static long blockMinZ(int zoom, int tileY) {
        return ((long) tileY * TILE_PIXELS) << zoom;
    }

    /**
     * 瓦片是否与地球范围重叠:Web Mercator 下地球在两个轴上都是 ±π·R/worldScale 格。范围外(含会让 int 方块座标溢位的
     * 巨大座标)一律不画,避免任何人用随意座标排队渲染、塞满磁盘。
     */
    public static boolean overlapsEarth(int zoom, int tileX, int tileY, double worldScale) {
        if (!(worldScale > 0.0D) || zoom < 0 || zoom > MAX_ZOOM) {
            return false;
        }
        double half = Math.PI * 6378137.0D / worldScale;
        long size = (long) TILE_PIXELS << zoom;
        long minX = (long) tileX * size;
        long minZ = (long) tileY * size;
        return minX + size > -half && minX < half && minZ + size > -half && minZ < half;
    }

    public static Rendered render(TerrainSurfaceSampler sampler, int zoom, int tileX, int tileY) {
        int blocksPerPixel = 1 << zoom;
        long minX = blockMinX(zoom, tileX) + blocksPerPixel / 2;
        long minZ = blockMinZ(zoom, tileY) + blocksPerPixel / 2;
        // 远景每像素跨很多格,按「每 4 格」折算坡度,明暗与近景相当。
        int slopeDivisor = Math.max(1, blocksPerPixel / 4);
        int[] argb = new int[TILE_PIXELS * TILE_PIXELS];
        int[] northY = new int[TILE_PIXELS];
        java.util.Arrays.fill(northY, Integer.MIN_VALUE);
        int failures = 0;
        // 先取同一格线上的北侧一行,让瓦片顶边也与真正相邻的像素比较高度。
        for (int py = -1; py < TILE_PIXELS; py++) {
            int z = (int) (minZ + (long) py * blocksPerPixel);
            for (int px = 0; px < TILE_PIXELS; px++) {
                int x = (int) (minX + (long) px * blocksPerPixel);
                Optional<SurfaceSample> sample;
                try {
                    sample = sampler.sample(x, z, blocksPerPixel);
                } catch (RuntimeException e) {
                    sample = Optional.empty();
                }
                if (py < 0) {
                    northY[px] = sample.map(SurfaceSample::surfaceY).orElse(Integer.MIN_VALUE);
                    continue; // halo 不属于输出像素,失败不计入保存门槛
                }
                int index = py * TILE_PIXELS + px;
                if (sample.isEmpty()) {
                    failures++;
                    argb[index] = 0;
                    northY[px] = Integer.MIN_VALUE;
                    continue;
                }
                SurfaceSample s = sample.get();
                if (s.water()) {
                    argb[index] = RoadMapRenderStyle.styleWater(s.waterDepth());
                } else {
                    int delta = northY[px] == Integer.MIN_VALUE ? 0
                            : Math.round((s.surfaceY() - northY[px]) / (float) slopeDivisor);
                    argb[index] = RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.colorFor(s.surface(), FALLBACK_ARGB), delta);
                }
                northY[px] = s.surfaceY();
            }
        }
        return new Rendered(argb, failures);
    }

    public static byte[] png(Rendered rendered) {
        BufferedImage image = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, TILE_PIXELS, TILE_PIXELS, rendered.argb(), 0, TILE_PIXELS);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
