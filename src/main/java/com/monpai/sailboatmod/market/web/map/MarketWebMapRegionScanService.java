package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MarketWebMapRegionScanService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNKS_PER_REGION_AXIS = 32;

    private final MarketWebMapRenderQueue queue;

    public MarketWebMapRegionScanService(MarketWebMapRenderQueue queue) {
        this.queue = queue;
    }

    public int enqueueRepairScan(ServerLevel level, int maxRegions, long nowMillis) {
        if (level == null || queue == null || !MarketWebMapConstants.OVERWORLD.equals(level.dimension().location().toString())) {
            return 0;
        }
        int queued = 0;
        for (RegionFile region : scanRegionFiles(level, maxRegions)) {
            queued += enqueueLoadedRegionChunksForTest(
                    queue,
                    MarketWebMapConstants.OVERWORLD,
                    region.regionX(),
                    region.regionZ(),
                    nowMillis,
                    (chunkX, chunkZ) -> level.getChunkSource().getChunk(chunkX, chunkZ, false) != null);
        }
        return queued;
    }

    public List<RegionFile> scanRegions(ServerLevel level, int maxRegions) {
        if (level == null || !MarketWebMapConstants.OVERWORLD.equals(level.dimension().location().toString())) {
            return List.of();
        }
        return scanRegionFiles(level, maxRegions);
    }

    public int queueSize() {
        return queue == null ? 0 : queue.size();
    }

    static List<RegionFile> scanRegionFilesForTest(Path regionDir, int centerRegionX, int centerRegionZ, int maxRegions) {
        return scanRegionFiles(regionDir, centerRegionX, centerRegionZ, maxRegions);
    }

    static int enqueueLoadedRegionChunksForTest(MarketWebMapRenderQueue queue,
                                                String dimensionId,
                                                int regionX,
                                                int regionZ,
                                                long nowMillis,
                                                ChunkLoadedPredicate loadedPredicate) {
        if (queue == null || loadedPredicate == null || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return 0;
        }
        int queued = 0;
        int baseChunkX = regionX * CHUNKS_PER_REGION_AXIS;
        int baseChunkZ = regionZ * CHUNKS_PER_REGION_AXIS;
        for (int localZ = 0; localZ < CHUNKS_PER_REGION_AXIS; localZ++) {
            for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                if (loadedPredicate.isLoaded(chunkX, chunkZ)
                        && queue.enqueue(dimensionId, chunkX, chunkZ, nowMillis)) {
                    queued++;
                }
            }
        }
        return queued;
    }

    private static List<RegionFile> scanRegionFiles(ServerLevel level, int maxRegions) {
        Path regionDir = level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("region")
                .normalize();
        int centerRegionX = 0;
        int centerRegionZ = 0;
        if (level.getSharedSpawnPos() != null) {
            centerRegionX = Math.floorDiv(level.getSharedSpawnPos().getX() >> 4, CHUNKS_PER_REGION_AXIS);
            centerRegionZ = Math.floorDiv(level.getSharedSpawnPos().getZ() >> 4, CHUNKS_PER_REGION_AXIS);
        }
        return scanRegionFiles(regionDir, centerRegionX, centerRegionZ, maxRegions);
    }

    private static List<RegionFile> scanRegionFiles(Path regionDir, int centerRegionX, int centerRegionZ, int maxRegions) {
        int limit = Math.max(0, maxRegions);
        if (limit == 0 || regionDir == null || !Files.isDirectory(regionDir)) {
            return List.of();
        }
        List<RegionFile> regions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path file : stream) {
                RegionFile region = parseRegionFile(file);
                if (region != null) {
                    regions.add(region);
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to scan market web map region files under {}", regionDir, exception);
            return List.of();
        }
        regions.sort(Comparator
                .comparingLong((RegionFile region) -> region.distanceSquared(centerRegionX, centerRegionZ))
                .thenComparingInt(RegionFile::regionX)
                .thenComparingInt(RegionFile::regionZ));
        if (regions.size() <= limit) {
            return regions;
        }
        return List.copyOf(regions.subList(0, limit));
    }

    private static RegionFile parseRegionFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String name = file.getFileName().toString();
        if (!name.startsWith("r.") || !name.endsWith(".mca")) {
            return null;
        }
        String body = name.substring(2, name.length() - 4);
        int split = body.indexOf('.');
        if (split <= 0 || split >= body.length() - 1) {
            return null;
        }
        try {
            return new RegionFile(
                    Integer.parseInt(body.substring(0, split)),
                    Integer.parseInt(body.substring(split + 1)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @FunctionalInterface
    interface ChunkLoadedPredicate {
        boolean isLoaded(int chunkX, int chunkZ);
    }

    public record RegionFile(int regionX, int regionZ) {
        private long distanceSquared(int centerRegionX, int centerRegionZ) {
            long dx = (long) regionX - centerRegionX;
            long dz = (long) regionZ - centerRegionZ;
            return dx * dx + dz * dz;
        }
    }
}
