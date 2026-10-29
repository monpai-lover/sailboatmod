package com.monpai.sailboatmod.roadplanner.map;

import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Common native-map palette and adjacent-north relief, for actual and predicted surfaces. */
public final class SharedMapTerrainRenderer {
    private SharedMapTerrainRenderer() { }

    public static int[] render(TerrainSurfaceSampler sampler, int startX, int startZ, int width, int height) {
        return render(sampler, startX, startZ, width, height, 1, () -> false);
    }

    private static int[] renderFine(TerrainSurfaceSampler sampler, int startX, int startZ, int width, int height,
                                    BooleanSupplier cancelled) {
        if (sampler == null || width < 1 || height < 1 || width > 256 || height > 256) {
            throw new IllegalArgumentException("Invalid shared-map raster bounds");
        }
        int[] pixels = new int[width * height];
        int[] north = new int[width];
        Arrays.fill(north, Integer.MIN_VALUE);
        for (int z = -1; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (cancelled(cancelled)) return pixels;
                long worldX = (long) startX + x, worldZ = (long) startZ + z;
                if (worldX < Integer.MIN_VALUE || worldX > Integer.MAX_VALUE || worldZ < Integer.MIN_VALUE || worldZ > Integer.MAX_VALUE) {
                    north[x] = Integer.MIN_VALUE;
                    continue;
                }
                Optional<SurfaceSample> sampled;
                try {
                    sampled = sampler.sample((int) worldX, (int) worldZ, 1);
                } catch (RuntimeException unavailable) {
                    sampled = Optional.empty();
                }
                if (sampled.isEmpty()) {
                    north[x] = Integer.MIN_VALUE;
                    continue;
                }
                SurfaceSample sample = sampled.get();
                if (z >= 0) {
                    int delta = north[x] == Integer.MIN_VALUE ? 0 : sample.surfaceY() - north[x];
                    pixels[z * width + x] = sample.water() ? RoadMapRenderStyle.styleWater(sample.waterDepth())
                            : RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.colorFor(sample.surface(), 0xFF7F7F7F), delta);
                }
                north[x] = sample.surfaceY();
            }
        }
        return pixels;
    }

    /** Spacing changes the pixel grid; each point keeps the native sampler's exact world-resolution contract. */
    public static int[] render(TerrainSurfaceSampler sampler, int startX, int startZ, int width, int height, int blocksPerPixel) {
        return render(sampler, startX, startZ, width, height, blocksPerPixel, () -> false);
    }

    /** Cancellation is checked between native calls; an in-flight synchronous Tellus download must return first. */
    public static int[] render(TerrainSurfaceSampler sampler, int startX, int startZ, int width, int height,
                               int blocksPerPixel, BooleanSupplier cancelled) {
        if (blocksPerPixel == 1) return renderFine(sampler, startX, startZ, width, height, cancelled);
        if (sampler == null || width < 1 || height < 1 || width > 256 || height > 256 || !SharedMapTileGrid.validBps(blocksPerPixel)) {
            throw new IllegalArgumentException("Invalid shared-map raster grid");
        }
        int[] pixels = new int[width * height];
        for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
            if (cancelled(cancelled)) return pixels;
            long worldX = startX + (long) x * blocksPerPixel + blocksPerPixel / 2;
            long worldZ = startZ + (long) z * blocksPerPixel + blocksPerPixel / 2;
            if (worldX < Integer.MIN_VALUE || worldX > Integer.MAX_VALUE || worldZ < Integer.MIN_VALUE || worldZ > Integer.MAX_VALUE) continue;
            Optional<SurfaceSample> sampled = sample(sampler, (int) worldX, (int) worldZ, blocksPerPixel);
            if (cancelled(cancelled)) return pixels;
            if (sampled.isEmpty()) continue;
            SurfaceSample point = sampled.get();
            Optional<SurfaceSample> north = worldZ == Integer.MIN_VALUE ? Optional.empty() : sample(sampler, (int) worldX, (int) worldZ - 1, blocksPerPixel);
            int delta = north.isEmpty() ? 0 : point.surfaceY() - north.get().surfaceY();
            pixels[z * width + x] = point.water() ? RoadMapRenderStyle.styleWater(point.waterDepth())
                    : RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.colorFor(point.surface(), 0xFF7F7F7F), delta);
        }
        return pixels;
    }

    private static boolean cancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    private static Optional<SurfaceSample> sample(TerrainSurfaceSampler sampler, int x, int z, int bps) {
        try { return sampler.sample(x, z, bps); }
        catch (RuntimeException unavailable) { return Optional.empty(); }
    }

    public static int[] mergePrediction(int[] previous, int[] update) {
        if (update == null) return previous == null ? new int[0] : Arrays.copyOf(previous, previous.length);
        int[] merged = previous != null && previous.length == update.length ? Arrays.copyOf(previous, previous.length) : new int[update.length];
        for (int i = 0; i < update.length; i++) if ((update[i] >>> 24) != 0) merged[i] = update[i];
        return merged;
    }

    public static boolean hasUnknownPixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) return true;
        for (int color : pixels) if ((color >>> 24) == 0) return true;
        return false;
    }
}
