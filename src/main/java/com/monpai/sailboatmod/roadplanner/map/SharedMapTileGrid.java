package com.monpai.sailboatmod.roadplanner.map;

/** Shared terrain tiles always contain 256 pixels; their world span depends on this explicit sampling grid. */
public final class SharedMapTileGrid {
    public static final int PIXELS = 256;
    public static final int MAX_BLOCKS_PER_PIXEL = 1 << 22;
    private SharedMapTileGrid() { }
    public static boolean validBps(int bps) { return bps > 0 && bps <= MAX_BLOCKS_PER_PIXEL && (bps & (bps - 1)) == 0; }
    public static long span(int bps) {
        if (!validBps(bps)) throw new IllegalArgumentException("Invalid map sampling grid");
        return (long) PIXELS * bps;
    }
    public static boolean validTile(int x, int z, int bps) {
        if (!validBps(bps)) return false;
        long span = span(bps), minX = x * span, minZ = z * span;
        return minX >= Integer.MIN_VALUE && minZ >= Integer.MIN_VALUE
                && minX + span - 1 <= Integer.MAX_VALUE && minZ + span - 1 <= Integer.MAX_VALUE;
    }
    public static int tileAt(int block, int bps) { return (int) Math.floorDiv((long) block, span(bps)); }
    public static int pixelAt(int block, int bps) { return (int) (Math.floorMod((long) block, span(bps)) / bps); }
    public static int sample(int tile, int pixel, int bps) {
        if (pixel < 0 || pixel >= PIXELS) throw new IllegalArgumentException("Invalid map pixel");
        long point = tile * span(bps) + (long) pixel * bps + bps / 2;
        if (point < Integer.MIN_VALUE || point > Integer.MAX_VALUE) throw new IllegalArgumentException("Map sample outside integer coordinates");
        return (int) point;
    }
    public static int forScale(double scale, int width, int height) {
        if (!(scale > 0) || !Double.isFinite(scale)) return 1;
        int bps = 1;
        while (bps < MAX_BLOCKS_PER_PIXEL) {
            double size = span(bps) * scale;
            double tiles = (Math.ceil(Math.max(1, width) / size) + 4) * (Math.ceil(Math.max(1, height) / size) + 4);
            if (size >= 64 && tiles <= 128) break;
            bps *= 2;
        }
        return bps;
    }
    public static int[] pixelsInChunk(int tileX, int tileZ, int bps, int chunkX, int chunkZ) {
        if (!validTile(tileX, tileZ, bps)) return new int[0];
        long firstX = tileX * span(bps) + bps / 2, firstZ = tileZ * span(bps) + bps / 2;
        long minX = Math.max(0, -Math.floorDiv(-(chunkX * 16L - firstX), bps));
        long minZ = Math.max(0, -Math.floorDiv(-(chunkZ * 16L - firstZ), bps));
        long maxX = Math.min(255, Math.floorDiv(chunkX * 16L + 15 - firstX, bps));
        long maxZ = Math.min(255, Math.floorDiv(chunkZ * 16L + 15 - firstZ, bps));
        if (minX > maxX || minZ > maxZ) return new int[0];
        int[] result = new int[(int) ((maxX - minX + 1) * (maxZ - minZ + 1))];
        int index = 0;
        for (int z = (int) minZ; z <= maxZ; z++) for (int x = (int) minX; x <= maxX; x++) result[index++] = z * PIXELS + x;
        return result;
    }
}
