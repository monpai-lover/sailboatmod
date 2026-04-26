package com.monpai.sailboatmod.roadplanner.map;

public class RoadMapColorizer {
    public int color(RoadMapColumnSample sample) {
        double factor = sample.water() ? waterFactor(sample.waterDepth()) : reliefFactor(sample.surfaceY() - sample.reliefBaseY());
        return scaleArgb(sample.baseArgb(), factor);
    }

    private double waterFactor(int waterDepth) {
        if (waterDepth > 6) {
            return 0.55D;
        }
        if (waterDepth > 3) {
            return 0.7D;
        }
        return 0.9D;
    }

    private double reliefFactor(int relativeHeight) {
        if (relativeHeight > 2) {
            return 1.25D;
        }
        if (relativeHeight > 0) {
            return 1.08D;
        }
        if (relativeHeight > -2) {
            return 0.88D;
        }
        return 0.68D;
    }

    private int scaleArgb(int argb, double factor) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = clamp((int) Math.round(((argb >>> 16) & 0xFF) * factor));
        int green = clamp((int) Math.round(((argb >>> 8) & 0xFF) * factor));
        int blue = clamp((int) Math.round((argb & 0xFF) * factor));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
