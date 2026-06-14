package com.monpai.sailboatmod.market.web.map;

import java.util.Arrays;

public final class MarketWebMapTileMetadata {
    static final int CHUNK_SOURCE_COUNT = MarketWebMapConstants.CHUNKS_PER_TILE_AXIS * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS;

    private final String dimensionId;
    private final int tileX;
    private final int tileZ;
    private final long updatedAtMillis;
    private final MarketWebMapTileQuality[] chunkSources;
    private final int[] chunkRendererVersions;

    MarketWebMapTileMetadata(String dimensionId,
                             int tileX,
                             int tileZ,
                             long updatedAtMillis,
                             MarketWebMapTileQuality[] chunkSources) {
        this(dimensionId, tileX, tileZ, updatedAtMillis, chunkSources, null);
    }

    MarketWebMapTileMetadata(String dimensionId,
                             int tileX,
                             int tileZ,
                             long updatedAtMillis,
                             MarketWebMapTileQuality[] chunkSources,
                             int[] chunkRendererVersions) {
        this.dimensionId = dimensionId == null ? "" : dimensionId;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.updatedAtMillis = updatedAtMillis;
        this.chunkSources = normalizeSources(chunkSources, MarketWebMapTileQuality.UNKNOWN);
        this.chunkRendererVersions = normalizeVersions(chunkRendererVersions);
    }

    static MarketWebMapTileMetadata filled(String dimensionId,
                                           int tileX,
                                           int tileZ,
                                           long updatedAtMillis,
                                           MarketWebMapTileQuality quality) {
        MarketWebMapTileQuality[] sources = new MarketWebMapTileQuality[CHUNK_SOURCE_COUNT];
        Arrays.fill(sources, quality == null ? MarketWebMapTileQuality.UNKNOWN : quality);
        return new MarketWebMapTileMetadata(dimensionId, tileX, tileZ, updatedAtMillis, sources);
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int tileX() {
        return tileX;
    }

    public int tileZ() {
        return tileZ;
    }

    public long updatedAtMillis() {
        return updatedAtMillis;
    }

    public int rendererVersion() {
        int best = 0;
        for (int version : chunkRendererVersions) {
            best = Math.max(best, version);
        }
        return best;
    }

    public MarketWebMapTileQuality sourceAt(int localChunkX, int localChunkZ) {
        if (localChunkX < 0
                || localChunkX >= MarketWebMapConstants.CHUNKS_PER_TILE_AXIS
                || localChunkZ < 0
                || localChunkZ >= MarketWebMapConstants.CHUNKS_PER_TILE_AXIS) {
            return MarketWebMapTileQuality.UNKNOWN;
        }
        return chunkSources[index(localChunkX, localChunkZ)];
    }

    public int rendererVersionAt(int localChunkX, int localChunkZ) {
        if (localChunkX < 0
                || localChunkX >= MarketWebMapConstants.CHUNKS_PER_TILE_AXIS
                || localChunkZ < 0
                || localChunkZ >= MarketWebMapConstants.CHUNKS_PER_TILE_AXIS) {
            return 0;
        }
        return chunkRendererVersions[index(localChunkX, localChunkZ)];
    }

    MarketWebMapTileMetadata withSource(int localChunkX,
                                        int localChunkZ,
                                        MarketWebMapTileQuality quality,
                                        long nowMillis) {
        return withSource(localChunkX, localChunkZ, quality, nowMillis, rendererVersionAt(localChunkX, localChunkZ));
    }

    MarketWebMapTileMetadata withSource(int localChunkX,
                                        int localChunkZ,
                                        MarketWebMapTileQuality quality,
                                        long nowMillis,
                                        int rendererVersion) {
        MarketWebMapTileQuality[] copy = Arrays.copyOf(chunkSources, chunkSources.length);
        int[] versionCopy = Arrays.copyOf(chunkRendererVersions, chunkRendererVersions.length);
        copy[index(localChunkX, localChunkZ)] = quality == null ? MarketWebMapTileQuality.UNKNOWN : quality;
        versionCopy[index(localChunkX, localChunkZ)] = Math.max(0, rendererVersion);
        return new MarketWebMapTileMetadata(dimensionId, tileX, tileZ, nowMillis, copy, versionCopy);
    }

    MarketWebMapTileQuality[] copySources() {
        return Arrays.copyOf(chunkSources, chunkSources.length);
    }

    int[] copyRendererVersions() {
        return Arrays.copyOf(chunkRendererVersions, chunkRendererVersions.length);
    }

    private static int index(int localChunkX, int localChunkZ) {
        return localChunkZ * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS + localChunkX;
    }

    private static MarketWebMapTileQuality[] normalizeSources(MarketWebMapTileQuality[] input,
                                                              MarketWebMapTileQuality fallback) {
        MarketWebMapTileQuality[] sources = new MarketWebMapTileQuality[CHUNK_SOURCE_COUNT];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = input != null && i < input.length && input[i] != null ? input[i] : fallback;
        }
        return sources;
    }

    private static int[] normalizeVersions(int[] input) {
        int[] versions = new int[CHUNK_SOURCE_COUNT];
        for (int i = 0; i < versions.length; i++) {
            versions[i] = input != null && i < input.length ? Math.max(0, input[i]) : 0;
        }
        return versions;
    }
}
