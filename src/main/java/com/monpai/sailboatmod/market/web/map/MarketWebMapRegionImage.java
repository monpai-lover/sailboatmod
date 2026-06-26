package com.monpai.sailboatmod.market.web.map;

import java.util.Arrays;

/**
 * A squaremap-style image for one Minecraft region file.
 *
 * <p>One region is 32x32 chunks, exactly one 512x512 base web tile. Rendering into this in memory
 * first avoids rewriting the same PNG for every individual chunk and prevents partially rewritten
 * seams from leaking into the browser.</p>
 */
public final class MarketWebMapRegionImage {
    public static final int CHUNKS_PER_REGION_AXIS = 32;
    public static final int SIZE = MarketWebMapTileCoordinate.BASE_TILE_SIZE;
    private static final int UNTOUCHED = Integer.MIN_VALUE;

    private final String dimensionId;
    private final int regionX;
    private final int regionZ;
    private final int[] pixels = new int[SIZE * SIZE];
    private final boolean[] chunks = new boolean[CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS];
    private int completedChunks;
    private boolean written;

    public MarketWebMapRegionImage(String dimensionId, int regionX, int regionZ) {
        this.dimensionId = dimensionId == null ? "" : dimensionId;
        this.regionX = regionX;
        this.regionZ = regionZ;
        Arrays.fill(pixels, UNTOUCHED);
    }

    public boolean putChunkPixels(int chunkX, int chunkZ, int[] chunkPixels) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || chunkPixels == null
                || chunkPixels.length != MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE) {
            return false;
        }
        int localChunkX = chunkX - regionX * CHUNKS_PER_REGION_AXIS;
        int localChunkZ = chunkZ - regionZ * CHUNKS_PER_REGION_AXIS;
        if (localChunkX < 0 || localChunkX >= CHUNKS_PER_REGION_AXIS
                || localChunkZ < 0 || localChunkZ >= CHUNKS_PER_REGION_AXIS) {
            return false;
        }
        int baseX = localChunkX * MarketWebMapConstants.CHUNK_SIZE;
        int baseZ = localChunkZ * MarketWebMapConstants.CHUNK_SIZE;
        for (int z = 0; z < MarketWebMapConstants.CHUNK_SIZE; z++) {
            int srcOffset = z * MarketWebMapConstants.CHUNK_SIZE;
            int dstOffset = (baseZ + z) * SIZE + baseX;
            System.arraycopy(chunkPixels, srcOffset, pixels, dstOffset, MarketWebMapConstants.CHUNK_SIZE);
        }
        int chunkIndex = localChunkZ * CHUNKS_PER_REGION_AXIS + localChunkX;
        if (!chunks[chunkIndex]) {
            chunks[chunkIndex] = true;
            completedChunks++;
        }
        written = false;
        return true;
    }

    public boolean markChunkSkipped(int chunkX, int chunkZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return false;
        }
        int localChunkX = chunkX - regionX * CHUNKS_PER_REGION_AXIS;
        int localChunkZ = chunkZ - regionZ * CHUNKS_PER_REGION_AXIS;
        if (localChunkX < 0 || localChunkX >= CHUNKS_PER_REGION_AXIS
                || localChunkZ < 0 || localChunkZ >= CHUNKS_PER_REGION_AXIS) {
            return false;
        }
        int chunkIndex = localChunkZ * CHUNKS_PER_REGION_AXIS + localChunkX;
        if (!chunks[chunkIndex]) {
            chunks[chunkIndex] = true;
            completedChunks++;
            written = false;
        }
        return true;
    }

    public boolean complete() {
        return completedChunks >= CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS;
    }

    public int completedChunks() {
        return completedChunks;
    }

    // 历史别名,委托 writeDirty。注意:writeDirty 现已支持部分写盘,本方法不再"仅 complete 才写"。
    public boolean writeIfComplete(MarketWebMapTileCache cache,
                                   MarketWebMapTileQuality quality,
                                   long nowMillis) {
        return writeDirty(cache, quality, nowMillis);
    }

    public boolean writeDirty(MarketWebMapTileCache cache,
                              MarketWebMapTileQuality quality,
                              long nowMillis) {
        // 允许【部分完成】的 region 增量写盘:只要有 ≥1 个 chunk 已就绪就 merge 进 base tile。
        // 不再要求整个 region 1024 chunk 全到齐(旧 !complete() 门禁在异步离线读模式下会让任何
        // 含长期 PENDING chunk 的 region 永不写图 → 底图全黑)。mergeTouchedPixels 只覆盖本批 touched
        // 像素、未渲染区保留磁盘旧值,故多次部分刷新是干净的渐进显示。
        if (cache == null || completedChunks <= 0) {
            return false;
        }
        if (written) {
            return true;
        }
        int[] base = cache.readSquareTilePixels(dimensionId, 0, regionX, regionZ);
        boolean changed = mergeTouchedPixels(base, pixels);
        boolean wrote = changed && cache.writeSquareTilePixels(dimensionId, 0, regionX, regionZ, base);
        // base tile 有改动就同步更新 zoom 金字塔(部分完成也更新,否则缩略级别永远落后仍全黑)。
        // writeZoomTile 内部逐像素 diff,反复部分刷新不会无谓重写未变的 zoom tile。
        if (wrote) {
            for (int zoom = 1; zoom <= MarketWebMapPyramidWriter.MAX_ZOOM; zoom++) {
                wrote |= writeZoomTile(cache, zoom, base);
            }
        }
        written = wrote;
        return wrote;
    }

    private boolean writeZoomTile(MarketWebMapTileCache cache, int zoom, int[] regionPixels) {
        if (regionPixels == null || regionPixels.length != pixels.length) {
            return false;
        }
        int scale = 1 << Math.max(0, zoom);
        int tileX = Math.floorDiv(regionX, scale);
        int tileZ = Math.floorDiv(regionZ, scale);
        int[] tile = cache.readSquareTilePixels(dimensionId, zoom, tileX, tileZ);
        int scaledSize = SIZE / scale;
        int baseX = Math.floorMod(regionX, scale) * scaledSize;
        int baseZ = Math.floorMod(regionZ, scale) * scaledSize;
        boolean changed = false;
        for (int z = 0; z < scaledSize; z++) {
            int sourceZ = z * scale;
            int dstOffset = (baseZ + z) * SIZE + baseX;
            for (int x = 0; x < scaledSize; x++) {
                int color = regionPixels[sourceZ * SIZE + x * scale];
                // 降采样跳过未触碰/透明黑像素:partial-flush 时 base 里大片是 UNTOUCHED 或未写过的 0x00000000,
                // 点采样若正好采中它们会把黑/无效值写进缩略瓦片 → 缩略层(放大前)出现大片黑块,而底层(放大后)正常。
                // 跳过 → 保留缩略层原有像素,直到该处底层真正渲染出有效颜色才更新。
                if (color == UNTOUCHED || (color & 0xFF000000) == 0) {
                    continue;
                }
                int index = dstOffset + x;
                if (tile[index] != color) {
                    tile[index] = color;
                    changed = true;
                }
            }
        }
        return changed && cache.writeSquareTilePixels(dimensionId, zoom, tileX, tileZ, tile);
    }

    private static boolean mergeTouchedPixels(int[] target, int[] source) {
        if (target == null || source == null || target.length != source.length) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < source.length; i++) {
            int color = source[i];
            if (color == UNTOUCHED) {
                continue;
            }
            if (target[i] != color) {
                target[i] = color;
                changed = true;
            }
        }
        return changed;
    }
}
