package com.monpai.sailboatmod.roadplanner.map;

/**
 * Shared map color styling for both the client road planner minimap and the web map.
 *
 * <p>The base block color still comes from {@link MapBlockColors}; this class only applies the
 * older map-style brightness bands and the readability softening pass.
 */
public final class RoadMapRenderStyle {
    private static final int LOADING_TARGET = 0xFF5A5A5A;

    private static final double BRIGHTNESS_HIGH = 1.00D;
    private static final double BRIGHTNESS_NORMAL = 220.0D / 255.0D;
    private static final double BRIGHTNESS_LOW = 180.0D / 255.0D;
    private static final double BRIGHTNESS_LOWEST = 135.0D / 255.0D;

    private RoadMapRenderStyle() {
    }

    public static int styleTerrainByDelta(int baseArgb, int delta) {
        return softenTerrain(applyBrightness(baseArgb, terrainBrightness(delta)));
    }

    public static int styleWater(int waterDepth) {
        return softenWater(applyBrightness(MapBlockColors.waterArgb(), waterBrightness(waterDepth)));
    }

    public static int softenTerrain(int argb) {
        return argb;
    }

    public static int softenWater(int argb) {
        return argb;
    }

    public static int softenLoading(int argb) {
        return mixWith(argb, LOADING_TARGET, 0.62D);
    }

    private static double terrainBrightness(int delta) {
        if (delta > 2) {
            return BRIGHTNESS_HIGH;
        }
        if (delta > 0) {
            return BRIGHTNESS_NORMAL;
        }
        if (delta > -2) {
            return BRIGHTNESS_LOW;
        }
        return BRIGHTNESS_LOWEST;
    }

    private static double waterBrightness(int waterDepth) {
        if (waterDepth > 6) {
            return BRIGHTNESS_LOWEST;
        }
        if (waterDepth > 3) {
            return BRIGHTNESS_LOW;
        }
        return BRIGHTNESS_NORMAL;
    }

    private static int applyBrightness(int argb, double factor) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = scale((argb >>> 16) & 0xFF, factor);
        int green = scale((argb >>> 8) & 0xFF, factor);
        int blue = scale(argb & 0xFF, factor);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int mixWith(int argb, int targetArgb, double amount) {
        double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        int alpha = (argb >>> 24) & 0xFF;
        int red = mix((argb >>> 16) & 0xFF, (targetArgb >>> 16) & 0xFF, clamped);
        int green = mix((argb >>> 8) & 0xFF, (targetArgb >>> 8) & 0xFF, clamped);
        int blue = mix(argb & 0xFF, targetArgb & 0xFF, clamped);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int scale(int value, double factor) {
        return clampByte((int) Math.round(value * factor));
    }

    private static int mix(int source, int target, double amount) {
        return clampByte((int) Math.round(source + (target - source) * amount));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
