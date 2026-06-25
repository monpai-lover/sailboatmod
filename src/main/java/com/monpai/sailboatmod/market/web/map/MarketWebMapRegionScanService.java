package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
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
        return scanRegionFiles(regionDir, List.of(new RegionCenter(centerRegionX, centerRegionZ)), maxRegions);
    }

    static RegionCenter centerRegionForTest(List<BlockPos> playerPositions, BlockPos spawnPos) {
        return centerRegion(playerPositions, spawnPos);
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
        List<RegionCenter> centers = centerRegions(level);
        if (centers.isEmpty()) {
            centers = List.of(new RegionCenter(centerRegionX, centerRegionZ));
        }
        return scanRegionFiles(regionDir, centers, maxRegions);
    }

    private static List<RegionFile> scanRegionFiles(Path regionDir, List<RegionCenter> centers, int maxRegions) {
        int limit = Math.max(0, maxRegions);
        if (limit == 0 || regionDir == null || !Files.isDirectory(regionDir)) {
            return List.of();
        }
        List<RegionCenter> safeCenters = centers == null || centers.isEmpty() ? List.of(new RegionCenter(0, 0)) : List.copyOf(centers);
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
                .comparingLong((RegionFile region) -> region.distanceSquared(safeCenters))
                .thenComparingInt(RegionFile::regionX)
                .thenComparingInt(RegionFile::regionZ));
        if (regions.size() <= limit) {
            return regions;
        }
        return List.copyOf(regions.subList(0, limit));
    }

    /**
     * Level-free 列出某 region 目录下所有有存盘 chunk 的 region(按坐标排序,不依赖玩家/spawn)。
     * 纯磁盘文件头解析,可在后台线程安全调用——黑块修复用它判定"该 region 是否本应有地形"。
     */
    static List<RegionFile> scanRegionFilesByDir(Path regionDir, int maxRegions) {
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
                .comparingInt(RegionFile::regionX)
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
        List<Integer> localChunks = readChunkLocations(file);
        if (localChunks.isEmpty()) {
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
                    Integer.parseInt(body.substring(split + 1)),
                    localChunks);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<RegionCenter> centerRegions(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        List<ServerPlayer> players = level.players();
        if (players == null || players.isEmpty()) {
            return List.of(centerRegion(List.of(), level.getSharedSpawnPos()));
        }
        List<BlockPos> positions = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            if (player != null) {
                positions.add(player.blockPosition());
            }
        }
        if (positions.isEmpty()) {
            return List.of(centerRegion(List.of(), level.getSharedSpawnPos()));
        }
        List<RegionCenter> centers = new ArrayList<>(positions.size());
        for (BlockPos position : positions) {
            centers.add(centerRegion(List.of(position), level.getSharedSpawnPos()));
        }
        return List.copyOf(centers);
    }

    private static RegionCenter centerRegion(List<BlockPos> playerPositions, BlockPos spawnPos) {
        BlockPos selected = null;
        if (playerPositions != null) {
            for (BlockPos playerPosition : playerPositions) {
                if (playerPosition != null) {
                    selected = playerPosition;
                    break;
                }
            }
        }
        if (selected == null) {
            selected = spawnPos == null ? BlockPos.ZERO : spawnPos;
        }
        return new RegionCenter(
                Math.floorDiv(selected.getX() >> 4, CHUNKS_PER_REGION_AXIS),
                Math.floorDiv(selected.getZ() >> 4, CHUNKS_PER_REGION_AXIS));
    }

    private static List<Integer> readChunkLocations(Path file) {
        byte[] header;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) < 8192) {
                return List.of();
            }
            header = new byte[4096];
            try (InputStream input = Files.newInputStream(file)) {
                int read = input.read(header);
                if (read < 4096) {
                    return List.of();
                }
            }
        } catch (IOException exception) {
            return List.of();
        }
        int headerLength = header.length;
        List<Integer> localChunks = new ArrayList<>();
        for (int local = 0; local < CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS; local++) {
            int offset = local * 4;
            if (offset + 3 >= headerLength) {
                break;
            }
            int sectorOffset = ((header[offset] & 0xFF) << 16)
                    | ((header[offset + 1] & 0xFF) << 8)
                    | (header[offset + 2] & 0xFF);
            int sectorCount = header[offset + 3] & 0xFF;
            if (sectorOffset > 0 && sectorCount > 0) {
                localChunks.add(local);
            }
        }
        return List.copyOf(localChunks);
    }

    @FunctionalInterface
    interface ChunkLoadedPredicate {
        boolean isLoaded(int chunkX, int chunkZ);
    }

    public record RegionCenter(int regionX, int regionZ) {
    }

    public record RegionFile(int regionX, int regionZ, List<Integer> localChunks) {
        public RegionFile {
            localChunks = localChunks == null ? List.of() : List.copyOf(localChunks);
        }

        private long distanceSquared(List<RegionCenter> centers) {
            long best = Long.MAX_VALUE;
            for (RegionCenter center : centers) {
                long dx = (long) regionX - center.regionX();
                long dz = (long) regionZ - center.regionZ();
                best = Math.min(best, dx * dx + dz * dz);
            }
            return best == Long.MAX_VALUE ? 0 : best;
        }
    }
}
