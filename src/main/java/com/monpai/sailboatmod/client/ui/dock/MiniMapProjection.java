package com.monpai.sailboatmod.client.ui.dock;

/**
 * Maps block offsets around a dock to minimap pixels and back, with the same rounding as the previous screens so a
 * zone dragged on the new map produces the same {@code SetDockZonePacket} offsets.
 */
public final class MiniMapProjection {
    private MiniMapProjection() {
    }

    /** Pixel column (or row) for a block offset in {@code [-radius, radius]}, clamped to the map. */
    public static int toPixel(int offset, int size, int radius) {
        return clamp((int) Math.round((offset + radius) / (double) (radius * 2) * (size - 1)), 0, size - 1);
    }

    /** Block offset for a pixel column (or row). */
    public static int toOffset(int pixel, int size, int radius) {
        double t = pixel / (double) (size - 1);
        return (int) Math.round(t * radius * 2 - radius);
    }

    /** Offset sampled for terrain colour at a pixel (old screens' sampling formula). */
    public static int sampleOffset(int pixel, int size, int radius) {
        return (int) Math.round((pixel / (double) (size - 1) * 2.0D - 1.0D) * radius);
    }

    /**
     * Zone in block offsets {@code {minX, maxX, minZ, maxZ}} for a drag between two pixels, in either direction.
     */
    public static int[] zoneFromDrag(int startPx, int startPz, int endPx, int endPz, int width, int height, int radius) {
        int minPx = Math.min(startPx, endPx);
        int maxPx = Math.max(startPx, endPx);
        int minPz = Math.min(startPz, endPz);
        int maxPz = Math.max(startPz, endPz);
        return new int[] {
                toOffset(minPx, width, radius), toOffset(maxPx, width, radius),
                toOffset(minPz, height, radius), toOffset(maxPz, height, radius)
        };
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
