package com.monpai.sailboatmod.roadplanner.map;

public final class RoadMapLodPyramid {
    private RoadMapLodPyramid() {
    }

    public static int[] deriveDisplayPixels(int[] sourcePixels, int sourceWidth, int sourceHeight, MapLod lod) {
        if (sourcePixels == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return new int[0];
        }
        int expected = sourceWidth * sourceHeight;
        if (sourcePixels.length < expected) {
            return new int[0];
        }
        MapLod safeLod = lod == null ? MapLod.LOD_1 : lod;
        int blockSize = Math.max(1, safeLod.blocksPerPixel());
        int[] derived = new int[expected];
        for (int y = 0; y < sourceHeight; y++) {
            int sourceY = Math.min(sourceHeight - 1, y / blockSize);
            for (int x = 0; x < sourceWidth; x++) {
                int sourceX = Math.min(sourceWidth - 1, x / blockSize);
                derived[y * sourceWidth + x] = sourcePixels[sourceY * sourceWidth + sourceX];
            }
        }
        return derived;
    }

    public static boolean[] deriveDisplayMask(boolean[] sourceMask, int sourceWidth, int sourceHeight, MapLod lod) {
        if (sourceMask == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return new boolean[0];
        }
        int expected = sourceWidth * sourceHeight;
        if (sourceMask.length < expected) {
            return new boolean[0];
        }
        MapLod safeLod = lod == null ? MapLod.LOD_1 : lod;
        int blockSize = Math.max(1, safeLod.blocksPerPixel());
        boolean[] derived = new boolean[expected];
        for (int y = 0; y < sourceHeight; y++) {
            int sourceY = Math.min(sourceHeight - 1, y / blockSize);
            for (int x = 0; x < sourceWidth; x++) {
                int sourceX = Math.min(sourceWidth - 1, x / blockSize);
                derived[y * sourceWidth + x] = sourceMask[sourceY * sourceWidth + sourceX];
            }
        }
        return derived;
    }
}
