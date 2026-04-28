package com.monpai.sailboatmod.client.roadplanner;

public final class RoadPlannerMapPalette {
    private static final int TERRAIN_TARGET = 0xFFE8F0D8;
    private static final int WATER_TARGET = 0xFF7FCBFF;
    private static final int LOADING_TARGET = 0xFF5A5A5A;

    private RoadPlannerMapPalette() {
    }

    public static int softenTerrain(int argb) {
        return mixWith(argb, TERRAIN_TARGET, 0.42D);
    }

    public static int softenWater(int argb) {
        return mixWith(argb, WATER_TARGET, 0.58D);
    }

    public static int softenLoading(int argb) {
        return mixWith(argb, LOADING_TARGET, 0.62D);
    }

    static int mixWith(int argb, int targetArgb, double amount) {
        double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        int alpha = (argb >>> 24) & 0xFF;
        int red = mix((argb >>> 16) & 0xFF, (targetArgb >>> 16) & 0xFF, clamped);
        int green = mix((argb >>> 8) & 0xFF, (targetArgb >>> 8) & 0xFF, clamped);
        int blue = mix(argb & 0xFF, targetArgb & 0xFF, clamped);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int mix(int source, int target, double amount) {
        return (int) Math.round(source + (target - source) * amount);
    }
}
