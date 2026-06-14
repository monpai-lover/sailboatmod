package com.monpai.sailboatmod.roadplanner.map;

import java.awt.Color;

/**
 * 地图取色的可读性调整:把过艳的方块 MapColor 往真实地图风格收一点。
 * 客户端实时瓦片与服务端快照瓦片共用这一份实现,保证两条取色管线视觉一致。
 */
public final class MapColorReadability {
    // 绿色系(草地/树叶)在地图上面积最大,荧光感最重,单独多降饱和。
    private static final float GREEN_HUE_MIN = 70.0F / 360.0F;
    private static final float GREEN_HUE_MAX = 160.0F / 360.0F;
    private static final float GREEN_SATURATION_SCALE = 0.82F;
    private static final float GENERAL_SATURATION_SCALE = 0.92F;

    private MapColorReadability() {
    }

    public static int adjust(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        float[] hsb = Color.RGBtoHSB(red, green, blue, null);
        float scale = (hsb[0] >= GREEN_HUE_MIN && hsb[0] <= GREEN_HUE_MAX)
                ? GREEN_SATURATION_SCALE
                : GENERAL_SATURATION_SCALE;
        hsb[1] = clamp01(hsb[1] * scale);
        int adjusted = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
        return (alpha << 24) | (adjusted & 0x00FFFFFF);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
