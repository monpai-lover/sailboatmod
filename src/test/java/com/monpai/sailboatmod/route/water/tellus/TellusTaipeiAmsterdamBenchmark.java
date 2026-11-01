package com.monpai.sailboatmod.route.water.tellus;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import com.monpai.sailboatmod.route.water.NbtChunkWaterReader;
import com.monpai.sailboatmod.route.water.WaterAutoRouteServiceBench;
import com.monpai.sailboatmod.route.water.WaterRouteResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in real-geography benchmark: Taipei → Amsterdam on a Tellus 1:100 world, using Tellus' own land mask file.
 * Run with {@code SAILBOAT_TELLUS_LANDMASK=/path/tellus_landmask.pmtiles ./gradlew test --tests '*TellusTaipeiAmsterdam*'}.
 * Optional: {@code SAILBOAT_TELLUS_SCALE} (default 100), {@code SAILBOAT_TELLUS_CHUNK_MICROS} (simulated per-chunk
 * prediction cost), {@code SAILBOAT_TELLUS_ROUTE_OUT} (CSV of the planned lon/lat route).
 */
class TellusTaipeiAmsterdamBenchmark {
    private static final int SEA_Y = 63;
    private static final long LIMIT_MILLIS = 10 * 60 * 1000L;

    @Test
    void taipeiToAmsterdamWithinTenMinutes() throws Exception {
        String file = System.getenv("SAILBOAT_TELLUS_LANDMASK");
        assumeTrue(file != null && Files.isRegularFile(Path.of(file)), "Tellus land mask not provided");
        double scale = Double.parseDouble(Optional.ofNullable(System.getenv("SAILBOAT_TELLUS_SCALE")).orElse("100"));
        long chunkMicros = Long.parseLong(Optional.ofNullable(System.getenv("SAILBOAT_TELLUS_CHUNK_MICROS")).orElse("0"));
        PmTilesLandMask mask = new PmTilesLandMask(Path.of(file), scale);
        long tileMillis = Long.parseLong(Optional.ofNullable(System.getenv("SAILBOAT_TELLUS_TILE_MILLIS")).orElse("0"));
        mask.simulateTileLatency(tileMillis);

        // Taipei Port (Bali, New Taipei) and IJmuiden (Amsterdam's North Sea entrance).
        BlockPos taipei = nearestHullWater(mask, 121.37D, 25.16D, scale);
        BlockPos amsterdam = nearestHullWater(mask, 4.55D, 52.465D, scale);
        System.out.printf("[bench] scale=1:%s zoom=%d taipei=%s amsterdam=%s straight=%d blocks%n", scale, mask.zoom(),
                taipei, amsterdam, Math.round(Math.sqrt(taipei.distSqr(amsterdam))));

        AtomicLong predictedChunks = new AtomicLong(), coarseSamples = new AtomicLong();
        TerrainPredictor predictor = new TerrainPredictor() {
            @Override public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
                predictedChunks.incrementAndGet();
                if (chunkMicros > 0) busyWait(chunkMicros);
                boolean[] water = new boolean[256];
                int[] surface = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    water[z * 16 + x] = !mask.isLand(cx * 16 + x, cz * 16 + z);
                    surface[z * 16 + x] = SEA_Y - 1; // top water block; boats/berths sit at SEA_Y
                }
                return Prediction.ready(new NbtChunkWaterReader.ChunkColumns(water, surface));
            }
            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
        TerrainSurfaceSampler surface = (x, z, bps) -> {
            coarseSamples.incrementAndGet();
            return Optional.of(mask.isLand(x, z, mask.zoomFor(bps))
                    ? new SurfaceSample(SEA_Y + 2, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0)
                    : new SurfaceSample(SEA_Y, Blocks.WATER.defaultBlockState(), true, 8));
        };

        // Production wraps this predictor itself: port areas use it, mid-route chunks read the land mask.
        long t0 = System.nanoTime();
        WaterRouteResult<List<BlockPos>> result = WaterAutoRouteServiceBench.plan(taipei, amsterdam, SEA_Y, predictor,
                new PredictionBudget(60_000L, System::currentTimeMillis), surface, LIMIT_MILLIS, scale);
        long millis = (System.nanoTime() - t0) / 1_000_000L;

        System.out.printf("[bench] result=%s reason=%s time=%dms predictedChunks=%d coarseSamples=%d%n",
                result.successful(), result.reason(), millis, predictedChunks.get(), coarseSamples.get());
        System.out.println("[bench] land mask: " + mask.tileStats() + " minZoom=" + mask.minZoom()
                + " simulatedDownloadWait=" + mask.simulatedWaitMillis() + "ms");
        if (result.successful()) {
            List<BlockPos> path = result.value();
            double length = 0;
            for (int i = 1; i < path.size(); i++) length += Math.sqrt(path.get(i - 1).distSqr(path.get(i)));
            long landHits = landHits(mask, path);
            System.out.printf("[bench] waypoints=%d length=%d blocks (%.0f km) landBlocksOnPath=%d %s%n",
                    path.size(), Math.round(length), length * scale / 1000.0D, landHits, shoreStats(mask, path));
            String out = System.getenv("SAILBOAT_TELLUS_ROUTE_OUT");
            if (out != null) {
                writeCsv(Path.of(out), path, scale);
                renderPng(mask, Path.of(out.replaceAll("\\.csv$", "") + ".png"), path, scale);
            }
            assertTrue(landHits == 0, "route crosses land");
        }
        String outMap = System.getenv("SAILBOAT_TELLUS_ROUTE_OUT");
        if (!result.successful() && outMap != null) renderPng(mask, Path.of(outMap.replaceAll("\\.csv$", "") + ".png"), List.of(), scale);
        assertTrue(result.successful(), "reason=" + result.reason());
        assertTrue(millis < LIMIT_MILLIS, "took " + millis + "ms");

        // ---- Route reuse: Taipei -> Singapore reuses the stored Taipei -> Amsterdam geometry. ----
        BlockPos singapore = nearestHullWater(mask, 103.84D, 1.20D, scale);
        long r0 = System.nanoTime();
        WaterRouteResult<List<BlockPos>> reused = WaterAutoRouteServiceBench.planReusing(result.value(), taipei, amsterdam,
                taipei, singapore, SEA_Y, predictor, surface, LIMIT_MILLIS, scale);
        long reuseMillis = (System.nanoTime() - r0) / 1_000_000L;
        long f0 = System.nanoTime();
        WaterRouteResult<List<BlockPos>> fresh = WaterAutoRouteServiceBench.plan(taipei, singapore, SEA_Y, predictor,
                new PredictionBudget(60_000L, System::currentTimeMillis), surface, LIMIT_MILLIS, scale);
        long freshMillis = (System.nanoTime() - f0) / 1_000_000L;
        System.out.printf("[bench] singapore=%s reuse: %s %dms %s | fresh: %s %dms %s%n", singapore,
                reused == null ? "not-applicable" : reused.successful() ? "ok" : reused.reason(), reuseMillis,
                reused != null && reused.successful() ? describe(mask, reused.value(), scale) : "",
                fresh.successful() ? "ok" : fresh.reason(), freshMillis, fresh.successful() ? describe(mask, fresh.value(), scale) : "");
        String outReuse = System.getenv("SAILBOAT_TELLUS_ROUTE_OUT");
        if (outReuse != null && reused != null && reused.successful())
            renderPng(mask, Path.of(outReuse.replaceAll("\\.csv$", "") + "-singapore-reuse.png"), reused.value(), scale);
        assertTrue(reused != null && reused.successful(), "reuse failed: " + (reused == null ? "n/a" : reused.reason()));
        assertTrue(fresh.successful(), "fresh Singapore route failed: " + fresh.reason());
    }

    private static String describe(PmTilesLandMask mask, List<BlockPos> path, double scale) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += Math.sqrt(path.get(i - 1).distSqr(path.get(i)));
        return String.format("waypoints=%d length=%d land=%d %s", path.size(), Math.round(length), landHits(mask, path), shoreStats(mask, path));
    }

    /** Share of the route (sampled every 4 blocks) lying within 8 / 24 blocks of land, excluding the 1024-block port ends. */
    static String shoreStats(PmTilesLandMask mask, List<BlockPos> path) {
        long total = 0, near8 = 0, near24 = 0;
        BlockPos first = path.getFirst(), last = path.getLast();
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int n = Math.max(1, (int) Math.ceil(Math.sqrt(a.distSqr(b)) / 4));
            for (int s = 0; s < n; s++) {
                double t = s / (double) n;
                int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t), z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                if (Math.hypot(x - first.getX(), z - first.getZ()) < 1024 || Math.hypot(x - last.getX(), z - last.getZ()) < 1024) continue;
                total++;
                int d = landWithin(mask, x, z, 24);
                if (d <= 8) near8++;
                if (d <= 24) near24++;
            }
        }
        return String.format("nearShore<=8:%.1f%% <=24:%.1f%%", total == 0 ? 0 : 100.0 * near8 / total, total == 0 ? 0 : 100.0 * near24 / total);
    }

    private static int landWithin(PmTilesLandMask mask, int x, int z, int radius) {
        for (int r = 0; r <= radius; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                if (mask.isLand(x + dx, z - r) || mask.isLand(x + dx, z + r)) return r;
            }
            for (int dz = -r; dz <= r; dz += 2) {
                if (mask.isLand(x - r, z + dz) || mask.isLand(x + r, z + dz)) return r;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static void busyWait(long micros) {
        long end = System.nanoTime() + micros * 1000L;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    /** Every block centre along every leg must be water (the boat hull is checked by the planner itself). */
    private static long landHits(PmTilesLandMask mask, List<BlockPos> path) {
        long hits = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int n = (int) Math.ceil(Math.sqrt(a.distSqr(b)));
            for (int s = 0; s <= n; s++) {
                double t = n == 0 ? 0 : s / (double) n;
                int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
                int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                if (mask.isLand(x, z)) hits++;
            }
        }
        return hits;
    }

    private static BlockPos nearestHullWater(PmTilesLandMask mask, double lon, double lat, double scale) {
        int cx = (int) Math.round(PmTilesLandMask.blockX(lon, scale)), cz = (int) Math.round(PmTilesLandMask.blockZ(lat, scale));
        for (int r = 0; r < 2000; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = cx + dx, z = cz + dz;
                boolean ok = true;
                for (int ox = -2; ox <= 2 && ok; ox++) for (int oz = -2; oz <= 2 && ok; oz++) ok = !mask.isLand(x + ox, z + oz);
                if (ok) return new BlockPos(x, SEA_Y, z);
            }
        }
        throw new IllegalStateException("no water near " + lon + "," + lat);
    }

    /** Equirectangular overview, lon -20..150, lat -45..65: land grey, sea blue, route red. */
    static void renderPng(PmTilesLandMask mask, Path out, List<BlockPos> path, double scale) throws IOException {
        int w = 1700, h = 1100;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < h; py++) for (int px = 0; px < w; px++) {
            double lon = -20 + 170.0 * px / w, lat = 65 - 110.0 * py / h;
            boolean land = mask.isLand((int) PmTilesLandMask.blockX(lon, scale), (int) PmTilesLandMask.blockZ(lat, scale));
            img.setRGB(px, py, land ? 0xB8B0A0 : 0x2E5C8A);
        }
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(java.awt.Color.RED);
        g.setStroke(new java.awt.BasicStroke(2f));
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            g.drawLine(px(a, scale, w), py(a, scale, h), px(b, scale, w), py(b, scale, h));
        }
        g.dispose();
        Files.createDirectories(out.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    private static int px(BlockPos p, double scale, int w) { return (int) Math.round((PmTilesLandMask.lon(p.getX(), scale) + 20) / 170.0 * w); }
    private static int py(BlockPos p, double scale, int h) { return (int) Math.round((65 - PmTilesLandMask.lat(p.getZ(), scale)) / 110.0 * h); }

    private static void writeCsv(Path out, List<BlockPos> path, double scale) throws IOException {
        StringBuilder sb = new StringBuilder("x,z,lon,lat\n");
        for (BlockPos p : path) {
            sb.append(p.getX()).append(',').append(p.getZ()).append(',')
                    .append(PmTilesLandMask.lon(p.getX(), scale)).append(',').append(PmTilesLandMask.lat(p.getZ(), scale)).append('\n');
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, sb.toString());
    }
}
