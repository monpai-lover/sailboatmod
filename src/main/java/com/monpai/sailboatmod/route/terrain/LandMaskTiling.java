package com.monpai.sailboatmod.route.terrain;

/**
 * Tellus 陆地遮罩(Web-Mercator 瓦片金字塔)的解析度换算。Tellus 的投影对 x 与 z 都是「坐标 × worldScale」的函数,
 * 所以用 {@code (x / k, z / k, worldScale × k)} 取样落在同一地点,却让 Tellus 选出粗 k 倍的瓦片层级:
 * 远航全局搜索用粗层级(瓦片少、下载少),开阔航段检查用原生层级。
 */
public final class LandMaskTiling {
    public static final double EQUATOR_METERS = 4.0075017E7D;

    private LandMaskTiling() {
    }

    /** 取样间距 blocksPerSample 时可接受的资料像素边长(格):间距的一半,至少 1(= 原生解析度)。 */
    public static int coarsening(int blocksPerSample) {
        return Math.max(1, blocksPerSample / 2);
    }

    /** 与 Tellus selectZoom 相同的(未夹限)层级:round(log2(赤道 / (256 × 每像素米数)))。 */
    public static int zoomFor(double worldScale, int coarsening) {
        double metersPerPixel = worldScale * Math.max(1, coarsening);
        return (int) Math.round(Math.log(EQUATOR_METERS / (256.0D * metersPerPixel)) / Math.log(2.0D));
    }

    /** 该层级一张瓦片覆盖的方块边长(Mercator 世界里与纬度无关)。 */
    public static double tileBlocks(double worldScale, int zoom) {
        return EQUATOR_METERS / worldScale / Math.pow(2.0D, zoom);
    }
}
