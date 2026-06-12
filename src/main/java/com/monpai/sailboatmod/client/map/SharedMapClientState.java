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
            applied = manager.applyTileSync(packet);
        }
        renderedChunks.markTile(packet.dimensionId(), packet.tileX(), packet.tileZ(), packet.coverageMask());
        return applied;
    }

    public boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        return renderedChunks.isRendered(dimensionId, chunkX, chunkZ);
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
