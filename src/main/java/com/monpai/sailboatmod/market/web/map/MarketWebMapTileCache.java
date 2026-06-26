package com.monpai.sailboatmod.market.web.map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class MarketWebMapTileCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final int RENDER_VERSION = 5;
    private static final int METADATA_VERSION = 2;
    /**
     * 运行时瓦片写入计数:每成功写一张瓦片 +1。给前端 snapshot 的 renderVersion 用,让 ?v= 缓存破坏参数
     * 随实际渲染变化,渲染完网页能拉到新瓦片(RENDER_VERSION 是编译期常量,不随渲染变,单用它网页永远显示旧图)。
     */
    private static final java.util.concurrent.atomic.AtomicLong TILE_WRITE_EPOCH =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public static long tileWriteEpoch() {
        return TILE_WRITE_EPOCH.get();
    }

    private final Path root;

    public MarketWebMapTileCache(Path root) {
        this.root = root == null ? Path.of(".").toAbsolutePath().normalize() : root.toAbsolutePath().normalize();
    }

    public static MarketWebMapTileCache forServer(MinecraftServer server) {
        Path root = server == null
                ? Path.of(MarketWebMapConstants.CACHE_DATA_DIR)
                : server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MarketWebMapConstants.CACHE_DATA_DIR);
        return new MarketWebMapTileCache(root);
    }

    public Optional<byte[]> readPng(String dimensionId, int tileX, int tileZ) {
        Path path = tilePath(dimensionId, tileX, tileZ);
        if (path == null || !path.startsWith(root) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException exception) {
            LOGGER.warn("Failed to read market web map tile {}", path, exception);
            return Optional.empty();
        }
    }

    public Optional<byte[]> readSquareTile(String dimensionId, int zoom, int tileX, int tileZ) {
        Path path = squareTilePath(dimensionId, zoom, tileX, tileZ);
        if (path == null || !path.startsWith(root) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (IOException exception) {
            LOGGER.warn("Failed to read market web square map tile {}", path, exception);
            return Optional.empty();
        }
    }

    public Optional<MarketWebMapTileMetadata> readMetadata(String dimensionId, int tileX, int tileZ) {
        Path pngPath = tilePath(dimensionId, tileX, tileZ);
        if (pngPath == null || !pngPath.startsWith(root)) {
            return Optional.empty();
        }
        Path metadataPath = metadataPath(dimensionId, tileX, tileZ);
        if (metadataPath != null && Files.isRegularFile(metadataPath)) {
            MarketWebMapTileMetadata parsed = readMetadataFile(metadataPath, dimensionId, tileX, tileZ);
            if (parsed != null) {
                return Optional.of(parsed);
            }
        }
        if (Files.isRegularFile(pngPath)) {
            return Optional.of(MarketWebMapTileMetadata.filled(
                    dimensionId,
                    tileX,
                    tileZ,
                    0L,
                    MarketWebMapTileQuality.LEGACY_UNKNOWN));
        }
        return Optional.of(MarketWebMapTileMetadata.filled(
                dimensionId,
                tileX,
                tileZ,
                0L,
                MarketWebMapTileQuality.UNKNOWN));
    }

    public boolean writePng(String dimensionId, int tileX, int tileZ, byte[] pngBytes) {
        Path path = tilePath(dimensionId, tileX, tileZ);
        if (path == null || !path.startsWith(root) || !isValidPngBytes(pngBytes)) {
            return false;
        }
        try {
            Files.createDirectories(path.getParent());
            if (isSamePng(path, pngBytes)) {
                return true;
            }
            Files.write(path, pngBytes);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to write market web map tile {}", path, exception);
            return false;
        }
    }

    public boolean writePng(String dimensionId,
                            int tileX,
                            int tileZ,
                            byte[] pngBytes,
                            MarketWebMapTileQuality quality,
                            long nowMillis) {
        if (!isValidPngBytes(pngBytes)) {
            return false;
        }
        int[] incoming = decodeArgb(pngBytes).orElse(null);
        if (incoming == null) {
            return false;
        }
        return mergeFullTile(dimensionId, tileX, tileZ, incoming, quality, nowMillis, defaultRendererVersion(quality));
    }

    public boolean writeArgbTile(String dimensionId, int tileX, int tileZ, int pixelWidth, int pixelHeight, int[] argbPixels) {
        byte[] pngBytes = encodePng(pixelWidth, pixelHeight, argbPixels);
        return pngBytes.length > 0 && writePng(dimensionId, tileX, tileZ, pngBytes);
    }

    public boolean writeArgbTile(String dimensionId,
                                 int tileX,
                                 int tileZ,
                                 int pixelWidth,
                                 int pixelHeight,
                                 int[] argbPixels,
                                 MarketWebMapTileQuality quality,
                                 long nowMillis) {
        if (pixelWidth != MarketWebMapConstants.TILE_SIZE
                || pixelHeight != MarketWebMapConstants.TILE_SIZE
                || argbPixels == null
                || argbPixels.length != MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return false;
        }
        return mergeFullTile(dimensionId, tileX, tileZ, Arrays.copyOf(argbPixels, argbPixels.length), quality, nowMillis, defaultRendererVersion(quality));
    }

    public boolean mergeChunkArgb(String dimensionId,
                                  int chunkX,
                                  int chunkZ,
                                  int[] chunkPixels,
                                  MarketWebMapTileQuality quality,
                                  long nowMillis) {
        return mergeChunkArgb(dimensionId, chunkX, chunkZ, chunkPixels, quality, nowMillis, defaultRendererVersion(quality));
    }

    public boolean mergeChunkArgb(String dimensionId,
                                  int chunkX,
                                  int chunkZ,
                                  int[] chunkPixels,
                                  MarketWebMapTileQuality quality,
                                  long nowMillis,
                                  int rendererVersion) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || chunkPixels == null
                || chunkPixels.length != MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE) {
            return false;
        }
        int tileX = Math.floorDiv(chunkX, MarketWebMapConstants.CHUNKS_PER_TILE_AXIS);
        int tileZ = Math.floorDiv(chunkZ, MarketWebMapConstants.CHUNKS_PER_TILE_AXIS);
        int localChunkX = Math.floorMod(chunkX, MarketWebMapConstants.CHUNKS_PER_TILE_AXIS);
        int localChunkZ = Math.floorMod(chunkZ, MarketWebMapConstants.CHUNKS_PER_TILE_AXIS);
        MarketWebMapTileMetadata metadata = readMetadata(dimensionId, tileX, tileZ).orElseGet(() ->
                MarketWebMapTileMetadata.filled(dimensionId, tileX, tileZ, nowMillis, MarketWebMapTileQuality.UNKNOWN));
        MarketWebMapTileQuality incoming = quality == null ? MarketWebMapTileQuality.UNKNOWN : quality;
        if (!canOverwriteChunk(incoming, metadata.sourceAt(localChunkX, localChunkZ),
                Math.max(0, rendererVersion), metadata.rendererVersionAt(localChunkX, localChunkZ))) {
            return true;
        }
        int[] tilePixels = readCurrentTilePixels(dimensionId, tileX, tileZ);
        int offsetX = localChunkX * MarketWebMapConstants.CHUNK_SIZE;
        int offsetZ = localChunkZ * MarketWebMapConstants.CHUNK_SIZE;
        for (int z = 0; z < MarketWebMapConstants.CHUNK_SIZE; z++) {
            int srcOffset = z * MarketWebMapConstants.CHUNK_SIZE;
            int dstOffset = (offsetZ + z) * MarketWebMapConstants.TILE_SIZE + offsetX;
            System.arraycopy(chunkPixels, srcOffset, tilePixels, dstOffset, MarketWebMapConstants.CHUNK_SIZE);
        }
        MarketWebMapTileMetadata updated = metadata.withSource(localChunkX, localChunkZ, incoming, nowMillis, Math.max(0, rendererVersion));
        return writeTileAndMetadata(dimensionId, tileX, tileZ, tilePixels, updated);
    }

    public boolean mergeChunkPyramid(String dimensionId,
                                     int chunkX,
                                     int chunkZ,
                                     int[] chunkPixels,
                                     MarketWebMapTileQuality quality,
                                     long nowMillis) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || chunkPixels == null
                || chunkPixels.length != MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE) {
            return false;
        }
        MarketWebMapTileQuality incoming = quality == null ? MarketWebMapTileQuality.UNKNOWN : quality;
        if (incoming.priority() < MarketWebMapTileQuality.SERVER_LOADED_CHUNK.priority()) {
            return true;
        }
        return new MarketWebMapPyramidWriter().mergeChunk(this, dimensionId, chunkX, chunkZ, chunkPixels);
    }

    public int repairLegacyMetadata() {
        Path dir = root.resolve("overworld").resolve("lod_1").normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return 0;
        }
        int repaired = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : stream) {
                TileName tile = parseTileName(png);
                if (tile == null || Files.isRegularFile(metadataPath(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ))) {
                    continue;
                }
                MarketWebMapTileMetadata metadata = MarketWebMapTileMetadata.filled(
                        MarketWebMapConstants.OVERWORLD,
                        tile.tileX,
                        tile.tileZ,
                        System.currentTimeMillis(),
                        MarketWebMapTileQuality.LEGACY_UNKNOWN);
                if (writeMetadata(metadataPath(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ), metadata)) {
                    repaired++;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to repair market web map legacy metadata under {}", dir, exception);
        }
        return repaired;
    }

    public int clearLegacyPngs() {
        Path dir = root.resolve("overworld").resolve("lod_1").normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return 0;
        }
        int cleared = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : stream) {
                TileName tile = parseTileName(png);
                if (tile == null || Files.isRegularFile(metadataPath(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ))) {
                    continue;
                }
                Files.deleteIfExists(png);
                cleared++;
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear market web map legacy PNGs under {}", dir, exception);
        }
        return cleared;
    }

    public int clearClientUploadChunks() {
        Path dir = root.resolve("overworld").resolve("lod_1").normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return 0;
        }
        int cleared = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : stream) {
                TileName tile = parseTileName(png);
                if (tile == null) {
                    continue;
                }
                MarketWebMapTileMetadata metadata = readMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ).orElse(null);
                if (metadata == null) {
                    continue;
                }
                MarketWebMapTileQuality[] sources = metadata.copySources();
                int[] rendererVersions = metadata.copyRendererVersions();
                boolean hasClientUpload = false;
                boolean hasPreservedSource = false;
                int[] pixels = readCurrentTilePixels(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ);
                for (int localZ = 0; localZ < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localZ++) {
                    for (int localX = 0; localX < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localX++) {
                        int sourceIndex = localZ * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS + localX;
                        MarketWebMapTileQuality source = sources[sourceIndex] == null ? MarketWebMapTileQuality.UNKNOWN : sources[sourceIndex];
                        if (source == MarketWebMapTileQuality.CLIENT_UPLOAD) {
                            clearChunkRegion(pixels, localX, localZ);
                            sources[sourceIndex] = MarketWebMapTileQuality.UNKNOWN;
                            rendererVersions[sourceIndex] = 0;
                            hasClientUpload = true;
                        } else if (source != MarketWebMapTileQuality.UNKNOWN) {
                            hasPreservedSource = true;
                        }
                    }
                }
                if (!hasClientUpload) {
                    continue;
                }
                if (hasPreservedSource) {
                    MarketWebMapTileMetadata updated = new MarketWebMapTileMetadata(
                            MarketWebMapConstants.OVERWORLD,
                            tile.tileX,
                            tile.tileZ,
                            System.currentTimeMillis(),
                            sources,
                            rendererVersions);
                    if (writeTileAndMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ, pixels, updated)) {
                        cleared++;
                    }
                } else if (deleteTileAndMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ)) {
                    cleared++;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear market web map client upload chunks under {}", dir, exception);
        }
        return cleared;
    }

    public int clearStaleServerChunks() {
        Path dir = root.resolve("overworld").resolve("lod_1").normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return 0;
        }
        int cleared = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : stream) {
                TileName tile = parseTileName(png);
                if (tile == null) {
                    continue;
                }
                MarketWebMapTileMetadata metadata = readMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ).orElse(null);
                if (metadata == null) {
                    continue;
                }
                MarketWebMapTileQuality[] sources = metadata.copySources();
                int[] rendererVersions = metadata.copyRendererVersions();
                boolean hasStaleServer = false;
                boolean hasPreservedSource = false;
                int[] pixels = readCurrentTilePixels(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ);
                for (int localZ = 0; localZ < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localZ++) {
                    for (int localX = 0; localX < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localX++) {
                        int sourceIndex = localZ * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS + localX;
                        MarketWebMapTileQuality source = sources[sourceIndex] == null ? MarketWebMapTileQuality.UNKNOWN : sources[sourceIndex];
                        if (isStaleServerSource(source, rendererVersions[sourceIndex])) {
                            clearChunkRegion(pixels, localX, localZ);
                            sources[sourceIndex] = MarketWebMapTileQuality.UNKNOWN;
                            rendererVersions[sourceIndex] = 0;
                            hasStaleServer = true;
                        } else if (source != MarketWebMapTileQuality.UNKNOWN) {
                            hasPreservedSource = true;
                        }
                    }
                }
                if (!hasStaleServer) {
                    continue;
                }
                if (hasPreservedSource) {
                    MarketWebMapTileMetadata updated = new MarketWebMapTileMetadata(
                            MarketWebMapConstants.OVERWORLD,
                            tile.tileX,
                            tile.tileZ,
                            System.currentTimeMillis(),
                            sources,
                            rendererVersions);
                    if (writeTileAndMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ, pixels, updated)) {
                        cleared++;
                    }
                } else if (deleteTileAndMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ)) {
                    cleared++;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear stale market web map server chunks under {}", dir, exception);
        }
        return cleared;
    }

    public int clearAllTiles() {
        Path dir = root.resolve("overworld").resolve("lod_1").normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return clearSquareTiles();
        }
        int cleared = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : stream) {
                TileName tile = parseTileName(png);
                if (tile == null) {
                    continue;
                }
                if (deleteTileAndMetadata(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ)) {
                    cleared++;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear market web map tile cache under {}", dir, exception);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path metadata : stream) {
                TileName tile = parseTileName(metadata);
                if (tile == null) {
                    continue;
                }
                Path png = tilePath(MarketWebMapConstants.OVERWORLD, tile.tileX, tile.tileZ);
                if (png == null || Files.exists(png)) {
                    continue;
                }
                Files.deleteIfExists(metadata);
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear orphan market web map tile metadata under {}", dir, exception);
        }
        return cleared + clearSquareTiles();
    }

    public static byte[] encodePng(int pixelWidth, int pixelHeight, int[] argbPixels) {
        if (pixelWidth != MarketWebMapConstants.TILE_SIZE
                || pixelHeight != MarketWebMapConstants.TILE_SIZE
                || argbPixels == null
                || argbPixels.length != MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return new byte[0];
        }
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixelWidth, pixelHeight, argbPixels, 0, pixelWidth);
        return writePngBytes(image);
    }

    /**
     * @deprecated 客户端上传路径已停用（网页地图统一由服务端取色生成）。此方法内部的红蓝(R↔B)字节序交换
     * 曾导致蓝色水面被翻成红色的"红水"瓦片。保留仅为兼容历史调用，请勿在新代码中使用。
     */
    @Deprecated
    public static byte[] encodeNativeImagePng(int pixelWidth, int pixelHeight, int[] nativePixels) {
        if (pixelWidth != MarketWebMapConstants.TILE_SIZE
                || pixelHeight != MarketWebMapConstants.TILE_SIZE
                || nativePixels == null
                || nativePixels.length != MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return new byte[0];
        }
        int[] argbPixels = new int[nativePixels.length];
        for (int i = 0; i < nativePixels.length; i++) {
            argbPixels[i] = nativeImageRgbaToArgb(nativePixels[i]);
        }
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixelWidth, pixelHeight, argbPixels, 0, pixelWidth);
        return writePngBytes(image);
    }

    private boolean mergeFullTile(String dimensionId,
                                  int tileX,
                                  int tileZ,
                                  int[] incomingPixels,
                                  MarketWebMapTileQuality quality,
                                  long nowMillis,
                                  int rendererVersion) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return false;
        }
        MarketWebMapTileQuality incoming = quality == null ? MarketWebMapTileQuality.UNKNOWN : quality;
        MarketWebMapTileMetadata metadata = readMetadata(dimensionId, tileX, tileZ).orElseGet(() ->
                MarketWebMapTileMetadata.filled(dimensionId, tileX, tileZ, nowMillis, MarketWebMapTileQuality.UNKNOWN));
        int[] tilePixels = readCurrentTilePixels(dimensionId, tileX, tileZ);
        MarketWebMapTileQuality[] sources = metadata.copySources();
        int[] rendererVersions = metadata.copyRendererVersions();
        boolean changed = false;
        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localX++) {
                int sourceIndex = localZ * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS + localX;
                if (!canOverwriteChunk(incoming, sources[sourceIndex], Math.max(0, rendererVersion), rendererVersions[sourceIndex])) {
                    continue;
                }
                copyChunkRegion(incomingPixels, tilePixels, localX, localZ);
                sources[sourceIndex] = incoming;
                rendererVersions[sourceIndex] = Math.max(0, rendererVersion);
                changed = true;
            }
        }
        if (!changed) {
            return true;
        }
        MarketWebMapTileMetadata updated = new MarketWebMapTileMetadata(dimensionId, tileX, tileZ, nowMillis, sources, rendererVersions);
        return writeTileAndMetadata(dimensionId, tileX, tileZ, tilePixels, updated);
    }

    private void copyChunkRegion(int[] source, int[] target, int localChunkX, int localChunkZ) {
        int offsetX = localChunkX * MarketWebMapConstants.CHUNK_SIZE;
        int offsetZ = localChunkZ * MarketWebMapConstants.CHUNK_SIZE;
        for (int z = 0; z < MarketWebMapConstants.CHUNK_SIZE; z++) {
            int srcOffset = (offsetZ + z) * MarketWebMapConstants.TILE_SIZE + offsetX;
            int dstOffset = srcOffset;
            System.arraycopy(source, srcOffset, target, dstOffset, MarketWebMapConstants.CHUNK_SIZE);
        }
    }

    private void clearChunkRegion(int[] target, int localChunkX, int localChunkZ) {
        if (target == null || target.length != MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE) {
            return;
        }
        int offsetX = localChunkX * MarketWebMapConstants.CHUNK_SIZE;
        int offsetZ = localChunkZ * MarketWebMapConstants.CHUNK_SIZE;
        for (int z = 0; z < MarketWebMapConstants.CHUNK_SIZE; z++) {
            int dstOffset = (offsetZ + z) * MarketWebMapConstants.TILE_SIZE + offsetX;
            Arrays.fill(target, dstOffset, dstOffset + MarketWebMapConstants.CHUNK_SIZE, 0x00000000);
        }
    }

    private int[] readCurrentTilePixels(String dimensionId, int tileX, int tileZ) {
        return readPng(dimensionId, tileX, tileZ)
                .flatMap(MarketWebMapTileCache::decodeArgb)
                .orElseGet(() -> new int[MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE]);
    }

    int[] readSquareTilePixels(String dimensionId, int zoom, int tileX, int tileZ) {
        return readSquareTile(dimensionId, zoom, tileX, tileZ)
                .flatMap(MarketWebMapTileCache::decodeSquareArgb)
                .orElseGet(() -> new int[MarketWebMapTileCoordinate.BASE_TILE_SIZE * MarketWebMapTileCoordinate.BASE_TILE_SIZE]);
    }

    /** 方形瓦片所在目录:root/overworld/square/&lt;zoom&gt;。zoom 越界返回 null。供黑块修复枚举磁盘瓦片用。 */
    Path squareTileDir(int zoom) {
        if (zoom < 0 || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return null;
        }
        return root.resolve("overworld").resolve("square").resolve(Integer.toString(zoom)).normalize();
    }

    /** 列出指定 zoom 下磁盘上实际存在的所有方形瓦片坐标 [tileX, tileZ]。复用 parseTileName。纯磁盘读,可后台线程调用。 */
    List<int[]> listSquareTileCoords(int zoom) {
        Path dir = squareTileDir(zoom);
        if (dir == null || !dir.startsWith(root) || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<int[]> coords = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : stream) {
                TileName tile = parseTileName(png);
                if (tile != null) {
                    coords.add(new int[]{tile.tileX(), tile.tileZ()});
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to list market web square map tiles under {}", dir, exception);
        }
        return List.copyOf(coords);
    }

    // === 接缝底行种子持久化 ============================================================================
    // 跨 region 的 height-shading 需要北邻 region 最底行的 surfaceY 作种子(lastY),否则 region 顶边出现
    // 亮度断层水平线。旧逻辑只用易失内存 LRU 缓存,渲染乱序/淘汰后种子拿不到 → 退化 → 细线。这里把每个
    // region 的底行(32 个 chunk 列 × 16 个 x 的 surfaceY = int[32][16])落盘,seedLastY 内存未命中时从磁盘读回。

    private static final int SEAM_MAGIC = 0x53454D31; // "SEM1"
    private static final int SEAM_COLS = MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS; // 32
    private static final int SEAM_ROW_LEN = MarketWebMapConstants.CHUNK_SIZE;             // 16

    private Path seamPath(int regionX, int regionZ) {
        return root.resolve("overworld").resolve("seam")
                .resolve(regionX + "_" + regionZ + ".bin").normalize();
    }

    /** 落盘一个 region 的底行种子。rows 为 int[32][] (每列 16 个 surfaceY),null 列写成全 Integer.MIN_VALUE。后台 IO 安全。 */
    void writeBottomRows(int regionX, int regionZ, int[][] rows) {
        Path path = seamPath(regionX, regionZ);
        if (path == null || !path.startsWith(root) || rows == null || rows.length != SEAM_COLS) {
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8 + SEAM_COLS * SEAM_ROW_LEN * 4);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(SEAM_MAGIC);
            out.writeInt(SEAM_COLS);
            for (int col = 0; col < SEAM_COLS; col++) {
                int[] row = rows[col];
                for (int i = 0; i < SEAM_ROW_LEN; i++) {
                    out.writeInt(row != null && i < row.length ? row[i] : Integer.MIN_VALUE);
                }
            }
            Files.createDirectories(path.getParent());
            writeBytesAtomically(path, buffer.toByteArray());
        } catch (IOException exception) {
            LOGGER.warn("Failed to write market web map seam rows for region {},{}", regionX, regionZ, exception);
        }
    }

    /** 读回一个 region 的底行种子,int[32][16];文件不存在/损坏返回 null。纯磁盘读,渲染线程可调。 */
    int[][] readBottomRows(int regionX, int regionZ) {
        Path path = seamPath(regionX, regionZ);
        if (path == null || !path.startsWith(root) || !Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(path)))) {
            if (in.readInt() != SEAM_MAGIC || in.readInt() != SEAM_COLS) {
                return null;
            }
            int[][] rows = new int[SEAM_COLS][SEAM_ROW_LEN];
            for (int col = 0; col < SEAM_COLS; col++) {
                for (int i = 0; i < SEAM_ROW_LEN; i++) {
                    rows[col][i] = in.readInt();
                }
            }
            return rows;
        } catch (java.nio.file.NoSuchFileException ignored) {
            // 文件不存在/被并发删除(clearall 或 isRegularFile 检查后的 TOCTOU 竞态)是常态,不是错误 → 静默。
            return null;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Failed to read market web map seam rows for region {},{}", regionX, regionZ, exception);
            return null;
        }
    }

    boolean writeSquareTilePixels(String dimensionId, int zoom, int tileX, int tileZ, int[] argbPixels) {
        Path pngPath = squareTilePath(dimensionId, zoom, tileX, tileZ);
        if (pngPath == null
                || !pngPath.startsWith(root)
                || argbPixels == null
                || argbPixels.length != MarketWebMapTileCoordinate.BASE_TILE_SIZE * MarketWebMapTileCoordinate.BASE_TILE_SIZE) {
            return false;
        }
        byte[] pngBytes = encodeSquarePng(argbPixels);
        if (!isValidPngBytes(pngBytes)) {
            return false;
        }
        try {
            Files.createDirectories(pngPath.getParent());
            if (isSamePng(pngPath, pngBytes)) {
                return true;
            }
            writeBytesAtomically(pngPath, pngBytes);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to write market web square map tile {}", pngPath, exception);
            return false;
        }
    }

    private boolean writeTileAndMetadata(String dimensionId,
                                         int tileX,
                                         int tileZ,
                                         int[] argbPixels,
                                         MarketWebMapTileMetadata metadata) {
        Path pngPath = tilePath(dimensionId, tileX, tileZ);
        Path metadataPath = metadataPath(dimensionId, tileX, tileZ);
        if (pngPath == null || metadataPath == null || !pngPath.startsWith(root) || !metadataPath.startsWith(root)) {
            return false;
        }
        byte[] pngBytes = encodePng(MarketWebMapConstants.TILE_SIZE, MarketWebMapConstants.TILE_SIZE, argbPixels);
        if (!isValidPngBytes(pngBytes)) {
            return false;
        }
        try {
            Files.createDirectories(pngPath.getParent());
            writeBytesAtomically(pngPath, pngBytes);
            boolean ok = writeMetadata(metadataPath, metadata);
            if (ok) {
                TILE_WRITE_EPOCH.incrementAndGet(); // 写成功 → bump epoch,前端据此刷新缓存破坏参数
            }
            return ok;
        } catch (IOException exception) {
            LOGGER.warn("Failed to merge market web map tile {}", pngPath, exception);
            return false;
        }
    }

    private boolean deleteTileAndMetadata(String dimensionId, int tileX, int tileZ) {
        Path pngPath = tilePath(dimensionId, tileX, tileZ);
        Path metadataPath = metadataPath(dimensionId, tileX, tileZ);
        if (pngPath == null || metadataPath == null || !pngPath.startsWith(root) || !metadataPath.startsWith(root)) {
            return false;
        }
        try {
            Files.deleteIfExists(pngPath);
            Files.deleteIfExists(metadataPath);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to delete market web map tile cache {},{}", tileX, tileZ, exception);
            return false;
        }
    }

    private boolean writeMetadata(Path path, MarketWebMapTileMetadata metadata) {
        if (path == null || metadata == null || !path.startsWith(root)) {
            return false;
        }
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(toJson(metadata), writer);
            }
            moveReplace(tmp, path);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to write market web map tile metadata {}", path, exception);
            return false;
        }
    }

    private MarketWebMapTileMetadata readMetadataFile(Path path, String dimensionId, int tileX, int tileZ) {
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject json = parsed.getAsJsonObject();
            JsonArray chunkSources = json.getAsJsonArray("chunkSources");
            JsonArray chunkRendererVersions = json.getAsJsonArray("chunkRendererVersions");
            int globalRendererVersion = json.has("rendererVersion") ? Math.max(0, json.get("rendererVersion").getAsInt()) : 0;
            MarketWebMapTileQuality[] sources = new MarketWebMapTileQuality[MarketWebMapTileMetadata.CHUNK_SOURCE_COUNT];
            int[] rendererVersions = new int[MarketWebMapTileMetadata.CHUNK_SOURCE_COUNT];
            for (int i = 0; i < sources.length; i++) {
                JsonElement element = chunkSources != null && i < chunkSources.size() ? chunkSources.get(i) : null;
                sources[i] = element == null ? MarketWebMapTileQuality.UNKNOWN : MarketWebMapTileQuality.fromId(element.getAsString());
                JsonElement rendererElement = chunkRendererVersions != null && i < chunkRendererVersions.size() ? chunkRendererVersions.get(i) : null;
                rendererVersions[i] = rendererElement == null ? globalRendererVersion : Math.max(0, rendererElement.getAsInt());
            }
            long updatedAtMillis = json.has("updatedAtMillis") ? json.get("updatedAtMillis").getAsLong() : 0L;
            String metadataDimension = json.has("dimensionId") ? json.get("dimensionId").getAsString() : dimensionId;
            int metadataTileX = json.has("tileX") ? json.get("tileX").getAsInt() : tileX;
            int metadataTileZ = json.has("tileZ") ? json.get("tileZ").getAsInt() : tileZ;
            return new MarketWebMapTileMetadata(metadataDimension, metadataTileX, metadataTileZ, updatedAtMillis, sources, rendererVersions);
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private JsonObject toJson(MarketWebMapTileMetadata metadata) {
        JsonObject json = new JsonObject();
        json.addProperty("version", METADATA_VERSION);
        json.addProperty("dimensionId", metadata.dimensionId());
        json.addProperty("lod", "lod_1");
        json.addProperty("tileX", metadata.tileX());
        json.addProperty("tileZ", metadata.tileZ());
        json.addProperty("updatedAtMillis", metadata.updatedAtMillis());
        json.addProperty("rendererVersion", metadata.rendererVersion());
        MarketWebMapTileQuality[] sources = metadata.copySources();
        int[] rendererVersions = metadata.copyRendererVersions();
        MarketWebMapTileQuality best = MarketWebMapTileQuality.UNKNOWN;
        JsonArray array = new JsonArray();
        JsonArray rendererArray = new JsonArray();
        for (int i = 0; i < sources.length; i++) {
            MarketWebMapTileQuality source = sources[i];
            MarketWebMapTileQuality quality = source == null ? MarketWebMapTileQuality.UNKNOWN : source;
            if (quality.priority() > best.priority()) {
                best = quality;
            }
            array.add(quality.id());
            rendererArray.add(Math.max(0, rendererVersions[i]));
        }
        json.addProperty("quality", best.id());
        json.add("chunkSources", array);
        json.add("chunkRendererVersions", rendererArray);
        return json;
    }

    private static int defaultRendererVersion(MarketWebMapTileQuality quality) {
        MarketWebMapTileQuality safe = quality == null ? MarketWebMapTileQuality.UNKNOWN : quality;
        return safe == MarketWebMapTileQuality.SERVER_LOADED_CHUNK || safe == MarketWebMapTileQuality.SERVER_REGION_SCAN
                ? RENDER_VERSION
                : 0;
    }

    private static boolean canOverwriteChunk(MarketWebMapTileQuality incoming,
                                             MarketWebMapTileQuality existing,
                                             int incomingRendererVersion,
                                             int existingRendererVersion) {
        MarketWebMapTileQuality safeIncoming = incoming == null ? MarketWebMapTileQuality.UNKNOWN : incoming;
        if (safeIncoming.canOverwrite(existing)) {
            return true;
        }
        MarketWebMapTileQuality safeExisting = existing == null ? MarketWebMapTileQuality.UNKNOWN : existing;
        if (safeIncoming != safeExisting) {
            return false;
        }
        return (safeIncoming == MarketWebMapTileQuality.SERVER_LOADED_CHUNK
                || safeIncoming == MarketWebMapTileQuality.SERVER_REGION_SCAN)
                && incomingRendererVersion > Math.max(0, existingRendererVersion);
    }

    private static boolean isStaleServerSource(MarketWebMapTileQuality source, int rendererVersion) {
        return (source == MarketWebMapTileQuality.SERVER_LOADED_CHUNK
                || source == MarketWebMapTileQuality.SERVER_REGION_SCAN)
                && Math.max(0, rendererVersion) < RENDER_VERSION;
    }

    private static int nativeImageRgbaToArgb(int rgba) {
        return (rgba & 0xFF000000)
                | ((rgba & 0x000000FF) << 16)
                | (rgba & 0x0000FF00)
                | ((rgba & 0x00FF0000) >>> 16);
    }

    private static byte[] writePngBytes(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            byte[] bytes = output.toByteArray();
            return isValidPngBytes(bytes) ? bytes : new byte[0];
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    private Path tilePath(String dimensionId, int tileX, int tileZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return null;
        }
        return root.resolve("overworld")
                .resolve("lod_1")
                .resolve(tileX + "_" + tileZ + ".png")
                .normalize();
    }

    private Path metadataPath(String dimensionId, int tileX, int tileZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return null;
        }
        return root.resolve("overworld")
                .resolve("lod_1")
                .resolve(tileX + "_" + tileZ + ".json")
                .normalize();
    }

    private Path squareTilePath(String dimensionId, int zoom, int tileX, int tileZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId) || zoom < 0 || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return null;
        }
        return root.resolve("overworld")
                .resolve("square")
                .resolve(Integer.toString(zoom))
                .resolve(tileX + "_" + tileZ + ".png")
                .normalize();
    }

    private int clearSquareTiles() {
        Path dir = root.resolve("overworld").resolve("square").normalize();
        if (!dir.startsWith(root) || !Files.exists(dir)) {
            return 0;
        }
        int[] cleared = {0};
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (!path.startsWith(root)) {
                    return;
                }
                try {
                    if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".png")) {
                        cleared[0]++;
                    }
                    Files.deleteIfExists(path);
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                    // 删目录瞬间有并发写入的残留文件 → 目录非空。非致命:文件本身会在下次清理/重渲覆盖,降为 debug 不刷屏。
                    LOGGER.debug("Market web square map dir not empty (concurrent write), skipping {}", path);
                } catch (IOException exception) {
                    LOGGER.warn("Failed to delete market web square map tile {}", path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear market web square map tile cache under {}", dir, exception);
        }
        return cleared[0];
    }

    private TileName parseTileName(Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }
        String name = path.getFileName().toString();
        int extensionLength;
        if (name.endsWith(".png")) {
            extensionLength = 4;
        } else if (name.endsWith(".json")) {
            extensionLength = 5;
        } else {
            return null;
        }
        String base = name.substring(0, name.length() - extensionLength);
        int split = base.lastIndexOf('_');
        if (split <= 0 || split >= base.length() - 1) {
            return null;
        }
        try {
            return new TileName(Integer.parseInt(base.substring(0, split)), Integer.parseInt(base.substring(split + 1)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Optional<int[]> decodeArgb(byte[] pngBytes) {
        if (!isValidPngBytes(pngBytes)) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (image == null || image.getWidth() != MarketWebMapConstants.TILE_SIZE || image.getHeight() != MarketWebMapConstants.TILE_SIZE) {
                return Optional.empty();
            }
            int[] pixels = new int[MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE];
            image.getRGB(0, 0, MarketWebMapConstants.TILE_SIZE, MarketWebMapConstants.TILE_SIZE, pixels, 0, MarketWebMapConstants.TILE_SIZE);
            return Optional.of(pixels);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Optional<int[]> decodeSquareArgb(byte[] pngBytes) {
        if (!isValidPngBytes(pngBytes)) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (image == null
                    || image.getWidth() != MarketWebMapTileCoordinate.BASE_TILE_SIZE
                    || image.getHeight() != MarketWebMapTileCoordinate.BASE_TILE_SIZE) {
                return Optional.empty();
            }
            int[] pixels = new int[MarketWebMapTileCoordinate.BASE_TILE_SIZE * MarketWebMapTileCoordinate.BASE_TILE_SIZE];
            image.getRGB(0, 0,
                    MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                    MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                    pixels,
                    0,
                    MarketWebMapTileCoordinate.BASE_TILE_SIZE);
            return Optional.of(pixels);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static byte[] encodeSquarePng(int[] argbPixels) {
        if (argbPixels == null || argbPixels.length != MarketWebMapTileCoordinate.BASE_TILE_SIZE * MarketWebMapTileCoordinate.BASE_TILE_SIZE) {
            return new byte[0];
        }
        BufferedImage image = new BufferedImage(
                MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0,
                MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                argbPixels,
                0,
                MarketWebMapTileCoordinate.BASE_TILE_SIZE);
        return writePngBytes(image);
    }

    private static boolean isSamePng(Path path, byte[] pngBytes) throws IOException {
        return Files.isRegularFile(path)
                && Files.size(path) == pngBytes.length
                && Arrays.equals(Files.readAllBytes(path), pngBytes);
    }

    private static boolean isValidPngBytes(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length <= 8 || pngBytes.length > MarketWebMapConstants.MAX_TILE_BYTES) {
            return false;
        }
        return (pngBytes[0] & 0xFF) == 0x89
                && pngBytes[1] == 0x50
                && pngBytes[2] == 0x4E
                && pngBytes[3] == 0x47;
    }

    private static void writeBytesAtomically(Path path, byte[] bytes) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        moveReplace(tmp, path);
    }

    private static void moveReplace(Path tmp, Path path) throws IOException {
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record TileName(int tileX, int tileZ) {
    }
}
