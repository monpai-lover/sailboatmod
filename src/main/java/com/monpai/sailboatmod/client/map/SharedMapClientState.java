package com.monpai.sailboatmod.client.map;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.map.RenderedChunkIndex;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

public final class SharedMapClientState {
    private static final SharedMapClientState DEFAULT = new SharedMapClientState(null, true);

    private final RenderedChunkIndex renderedChunks = new RenderedChunkIndex();
    private final RoadPlannerTileManager injectedTileManager;
    private final boolean useDefaultTileManager;

    public SharedMapClientState(RoadPlannerTileManager injectedTileManager) {
        this(injectedTileManager, injectedTileManager != null);
    }

    private SharedMapClientState(RoadPlannerTileManager injectedTileManager, boolean useDefaultTileManager) {
        this.injectedTileManager = injectedTileManager;
        this.useDefaultTileManager = useDefaultTileManager;
    }

    public static SharedMapClientState defaultState() {
        return DEFAULT;
    }

    public int applyTileDelta(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        int applied = 0;
        RoadPlannerTileManager manager = tileManager();
        if (manager != null) {
            if ((!packet.worldId().isBlank() && !manager.worldId().equals(packet.worldId()))
                    || (!packet.dimensionId().isBlank() && !manager.dimensionId().equals(packet.dimensionId()))) return 0;
            applied = manager.applyTileSync(packet);
        }
        int[] pixels = packet.argbPixels();
        boolean[] coverage = packet.coverageMask();
        if (pixels.length == 256 * 256 && coverage.length == pixels.length) {
            for (int i = 0; i < coverage.length; i++) coverage[i] &= (pixels[i] >>> 24) != 0;
            renderedChunks.markTile(packet.dimensionId(), packet.tileX(), packet.tileZ(), coverage);
        }
        return applied;
    }

    public boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        return renderedChunks.isRendered(dimensionId, chunkX, chunkZ);
    }

    public int applyTerrainPatch(String worldId, String dimensionId, int tileX, int tileZ,
                                 int offsetX, int offsetZ, int width, int height, int[] pixels, boolean prediction) {
        return applyTerrainPatch(worldId, dimensionId, tileX, tileZ, offsetX, offsetZ, width, height, pixels, prediction, 1);
    }

    public int applyTerrainPatch(String worldId, String dimensionId, int tileX, int tileZ,
                                 int offsetX, int offsetZ, int width, int height, int[] pixels, boolean prediction, int blocksPerPixel) {
        RoadPlannerTileManager manager = tileManager();
        if (manager == null || !manager.worldId().equals(worldId) || !manager.dimensionId().equals(dimensionId)) return 0;
        if (manager.applyTerrainPatch(worldId, dimensionId, tileX, tileZ, offsetX, offsetZ, width, height, pixels, prediction, blocksPerPixel) == 0) return 0;
        if (!prediction && blocksPerPixel == 1) {
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    if ((pixels[z * width + x] >>> 24) != 0) {
                        renderedChunks.markChunk(dimensionId, tileX * 16 + (offsetX + x) / 16,
                                tileZ * 16 + (offsetZ + z) / 16);
                    }
                }
            }
        }
        return 1;
    }

    public boolean hasAnyRenderedChunkInTile(String dimensionId, int tileX, int tileZ) {
        return renderedChunks.hasAnyRenderedChunkInTile(dimensionId, tileX, tileZ);
    }

    public void clearAll() {
        renderedChunks.clearAll();
    }

    private RoadPlannerTileManager tileManager() {
        if (injectedTileManager != null) {
            return injectedTileManager;
        }
        if (!useDefaultTileManager) {
            return null;
        }
        try {
            return RoadPlannerTileManager.sharedDefault();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
