package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Isolated production-path benchmark; never writes to the optional saved-world fixture. */
public final class MapRenderThroughputBenchmark {
    private static final String WORLD = MarketWebMapConstants.OVERWORLD;
    private static final int SIZE = 512;
    private static volatile long sink;
    private final Path output;
    private final Path golden;
    private final int regionX;
    private final int regionZ;
    private final int[] base;
    private final MarketWebMapChunkSnapshot[] snapshots = syntheticSnapshots();
    private final com.sun.management.ThreadMXBean allocations =
            ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;
    private final java.lang.management.ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private int sequence;

    private MapRenderThroughputBenchmark(Path output, Path fixture, int regionX, int regionZ) throws Exception {
        this.output = output.toAbsolutePath().normalize();
        this.regionX = regionX;
        this.regionZ = regionZ;
        if (fixture != null && this.output.startsWith(fixture.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Benchmark output must be outside the read-only fixture cache");
        }
        Files.createDirectories(this.output);
        golden = Files.createTempDirectory(this.output, "golden-");
        MarketWebMapTileCache goldenCache = new MarketWebMapTileCache(golden);
        if (fixture == null) {
            base = syntheticBase();
        } else {
            MarketWebMapTileCache source = new MarketWebMapTileCache(fixture);
            byte[] first = source.readSquareTile(WORLD, 0, regionX, regionZ).orElseThrow(() ->
                    new IllegalArgumentException("Fixture has no base PNG at requested region coordinates"));
            for (int zoom = 0; zoom <= 4; zoom++) {
                int scale = 1 << zoom;
                byte[] bytes = source.readSquareTile(WORLD, zoom,
                        Math.floorDiv(regionX, scale), Math.floorDiv(regionZ, scale)).orElse(null);
                if (bytes != null) {
                    Path target = tile(golden, zoom);
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                }
            }
            sink ^= first.length;
            base = goldenCache.readSquareTilePixels(WORLD, 0, regionX, regionZ);
        }
        image(base, false).writeDirty(goldenCache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 0);
        if (allocations != null && allocations.isThreadAllocatedMemorySupported()) allocations.setThreadAllocatedMemoryEnabled(true);
        if (cpu.isThreadCpuTimeSupported()) cpu.setThreadCpuTimeEnabled(true);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) options.put(args[i], args[i + 1]);
        Path output = Path.of(options.getOrDefault("--output", "audit/render-speed-20261104/results"));
        Path fixture = options.containsKey("--fixture-cache") ? Path.of(options.get("--fixture-cache")) : null;
        int warmups = Integer.parseInt(options.getOrDefault("--warmup", "1"));
        int iterations = Integer.parseInt(options.getOrDefault("--iterations", "3"));
        int regionX = Integer.parseInt(options.getOrDefault("--region-x", "0"));
        int regionZ = Integer.parseInt(options.getOrDefault("--region-z", "0"));
        long deadline = System.nanoTime() + Long.parseLong(options.getOrDefault("--max-ms", "30000")) * 1_000_000L;
        var benchmark = new MapRenderThroughputBenchmark(output, fixture, regionX, regionZ);
        System.out.println("# java=" + System.getProperty("java.version") + " ImageIO.cache=" + javax.imageio.ImageIO.getUseCache()
                + " fixture=" + (fixture == null ? "synthetic mixed terrain" : fixture.toAbsolutePath()));
        System.out.println("scenario,sample,wall_ms,cpu_ms,allocated_bytes,gc_count,png_files,png_bytes,pixel_sha256,changed");
        for (String scenario : List.of("raster_1024_chunks", "full_region_first_write", "incremental_one_chunk",
                "unchanged_full_region", "unchanged_one_chunk", "repair_four_zoom_levels", "read_decode_base", "read_decode_five_tiles",
                "warm_unchanged_full_region", "warm_unchanged_one_chunk", "warm_incremental_one_chunk", "sequence_32_partial")) {
            for (int iteration = -warmups; iteration < iterations; iteration++) {
                if (System.nanoTime() >= deadline) { System.out.println("# stopped at --max-ms between operations"); return; }
                benchmark.measure(scenario, iteration, iteration >= 0);
            }
        }
        System.out.println("# witness_sink=" + sink + " output=" + output.toAbsolutePath());
    }

    private void measure(String scenario, int iteration, boolean report) throws Exception {
        String operation = scenario.startsWith("warm_") ? scenario.substring(5) : scenario;
        Path run = Files.createTempDirectory(output, (++sequence) + "-" + scenario + "-");
        MarketWebMapTileCache cache = new MarketWebMapTileCache(run);
        if (!scenario.equals("full_region_first_write") && !scenario.equals("raster_1024_chunks")) copyGolden(run);
        if (scenario.equals("repair_four_zoom_levels")) for (int zoom = 1; zoom <= 4; zoom++) Files.deleteIfExists(tile(run, zoom));
        int[] expected = base.clone();
        boolean partial = operation.equals("incremental_one_chunk") || operation.equals("unchanged_one_chunk");
        if (operation.equals("incremental_one_chunk")) {
            for (int z = 7 * 16; z < 8 * 16; z++) for (int x = 13 * 16; x < 14 * 16; x++) expected[z * SIZE + x] ^= 0x00010101;
        }
        MarketWebMapRegionImage image = image(expected, partial);
        if (scenario.startsWith("warm_")) for (int zoom=0;zoom<=4;zoom++)
            cache.readSquareTilePixels(WORLD, zoom, Math.floorDiv(regionX,1<<zoom),Math.floorDiv(regionZ,1<<zoom));
        List<MarketWebMapRegionImage> sequence = new ArrayList<>();
        if (operation.equals("sequence_32_partial")) {
            for (int step=0;step<32;step++) {
                var batch = new MarketWebMapRegionImage(WORLD,regionX,regionZ);
                int[] chunk = new int[256];
                for (int row=0;row<16;row++) for(int col=0;col<16;col++) {
                    int index=(step*16+row)*SIZE+col;
                    expected[index] ^= 0x00010101;
                    chunk[row*16+col] = expected[index];
                }
                batch.putChunkPixels(regionX*32,regionZ*32+step,chunk);sequence.add(batch);
            }
        }
        long priorRevision = cache.changeJournal().revision();
        long beforeGc = gcCount(), beforeAlloc = allocated(), beforeCpu = cpuTime(), start = System.nanoTime();
        boolean changed = false;
        switch (operation) {
            case "raster_1024_chunks" -> raster();
            case "read_decode_base" -> sink ^= Arrays.hashCode(cache.readSquareTilePixels(WORLD, 0, regionX, regionZ));
            case "read_decode_five_tiles" -> {
                for (int zoom = 0; zoom <= 4; zoom++) sink ^= Arrays.hashCode(cache.readSquareTilePixels(WORLD, zoom,
                        Math.floorDiv(regionX, 1 << zoom), Math.floorDiv(regionZ, 1 << zoom)));
            }
            case "sequence_32_partial" -> {
                for(var batch:sequence) changed |= batch.writeDirty(cache,MarketWebMapTileQuality.SERVER_REGION_SCAN,iteration);
            }
            default -> changed = image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, iteration);
        }
        long wall = System.nanoTime() - start, usedCpu = cpuTime() - beforeCpu, usedAlloc = allocated() - beforeAlloc, gc = gcCount() - beforeGc;
        // Verification is outside the timed operation. Hash decoded pixels, since valid encoder
        // improvements may change PNG bytes while preserving every color and coordinate.
        String hash = "-";
        if (!scenario.equals("raster_1024_chunks")) {
            int[] actual = cache.readSquareTilePixels(WORLD, 0, regionX, regionZ);
            if (!Arrays.equals(expected, actual)) throw new AssertionError("Base pixels changed unexpectedly in " + scenario);
            if (operation.startsWith("unchanged") && cache.changeJournal().revision() != priorRevision) {
                throw new AssertionError("An unchanged operation emitted a tile change");
            }
            hash = pixelHash(cache);
        }
        long count = 0, bytes = 0;
        try (var paths = Files.walk(run)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".png")).toList()) { count++; bytes += Files.size(file); }
        }
        if (report) System.out.printf(Locale.ROOT, "%s,%d,%.3f,%.3f,%d,%d,%d,%d,%s,%s%n", scenario, iteration,
                wall / 1_000_000D, usedCpu / 1_000_000D, usedAlloc, gc, count, bytes, hash, changed);
    }

    private void raster() {
        MarketWebMapRegionRenderer renderer = new MarketWebMapRegionRenderer();
        MarketWebMapRegionImage assembled = new MarketWebMapRegionImage(WORLD, 0, 0);
        for (int x = 0; x < 32; x++) {
            int[] lastY = renderer.unknownLastY();
            for (int z = 0; z < 32; z++) {
                int[] pixels = renderer.renderChunk(snapshots[z * 32 + x], lastY);
                assembled.putChunkPixels(x, z, pixels);
                sink += pixels[(z + x) & 255];
            }
        }
    }

    private MarketWebMapRegionImage image(int[] pixels, boolean partial) {
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(WORLD, regionX, regionZ);
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            if (partial && (x != 13 || z != 7)) continue;
            int[] chunk = new int[256];
            for (int row = 0; row < 16; row++) System.arraycopy(pixels, (z * 16 + row) * SIZE + x * 16, chunk, row * 16, 16);
            image.putChunkPixels(regionX * 32 + x, regionZ * 32 + z, chunk);
        }
        return image;
    }

    private int[] syntheticBase() {
        MarketWebMapRegionRenderer renderer = new MarketWebMapRegionRenderer();
        int[] result = new int[SIZE * SIZE];
        for (int x = 0; x < 32; x++) {
            int[] lastY = renderer.unknownLastY();
            for (int z = 0; z < 32; z++) {
                int[] chunk = renderer.renderChunk(snapshots[z * 32 + x], lastY);
                for (int row = 0; row < 16; row++) System.arraycopy(chunk, row * 16, result, (z * 16 + row) * SIZE + x * 16, 16);
            }
        }
        return result;
    }

    private static MarketWebMapChunkSnapshot[] syntheticSnapshots() {
        int[] palette = {0xFF7EA44D, 0xFF4A7A38, 0xFF888889, 0xFF8E6842, 0xFFDDCF8A};
        MarketWebMapChunkSnapshot[] result = new MarketWebMapChunkSnapshot[1024];
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            RoadMapColumnSample[] samples = new RoadMapColumnSample[256];
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                int wx = x * 16 + dx, wz = z * 16 + dz, height = 64 + (wx / 7 + wz / 11) % 23;
                boolean water = (wx / 43 + wz / 29) % 5 == 0;
                samples[dz * 16 + dx] = new RoadMapColumnSample(wx, height, wz,
                        palette[(wx / 13 + wz / 17) % palette.length], water, 1 + (wx + wz) % 9, height);
            }
            result[z * 32 + x] = new MarketWebMapChunkSnapshot(WORLD, x, z, samples);
        }
        return result;
    }

    private Path tile(Path root, int zoom) {
        return root.resolve("overworld/square/" + zoom).resolve(Math.floorDiv(regionX, 1 << zoom) + "_" + Math.floorDiv(regionZ, 1 << zoom) + ".png");
    }

    private void copyGolden(Path target) throws Exception {
        for (int zoom = 0; zoom <= 4; zoom++) {
            Path destination = tile(target, zoom);
            if (Files.isRegularFile(tile(golden, zoom))) {
                Files.createDirectories(destination.getParent());
                Files.copy(tile(golden, zoom), destination);
            }
        }
    }

    private String pixelHash(MarketWebMapTileCache cache) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer words = ByteBuffer.allocate(512 * 512 * 4);
        for (int zoom = 0; zoom <= 4; zoom++) {
            words.clear();
            for (int value : cache.readSquareTilePixels(WORLD, zoom,
                    Math.floorDiv(regionX, 1 << zoom), Math.floorDiv(regionZ, 1 << zoom))) words.putInt(value);
            digest.update(words.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private long allocated() { return allocations != null && allocations.isThreadAllocatedMemoryEnabled() ? allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1; }
    private long cpuTime() { return cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : 0; }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum(); }
}
