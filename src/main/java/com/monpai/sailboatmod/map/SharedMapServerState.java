package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicReference;

public final class SharedMapServerState {
    private static final AtomicReference<SharedMapServerState> ACTIVE = new AtomicReference<>();

    private final RenderedChunkIndex renderedChunks = new RenderedChunkIndex();

    public static void onServerStarted(MinecraftServer server) {
        if (server != null) {
            ACTIVE.set(new SharedMapServerState());
        }
    }

    public static void onServerStopped() {
        ACTIVE.set(null);
    }

    public static SharedMapServerState get() {
        return ACTIVE.get();
    }

    public static void setActiveForTest(SharedMapServerState state) {
        ACTIVE.set(state);
    }

    public static void clearActiveForTest() {
        ACTIVE.set(null);
    }

    public static void markRendered(RoadPlannerMapTileSyncPacket packet) {
        SharedMapServerState state = ACTIVE.get();
        if (state != null) {
            state.markTileDelta(packet);
        }
    }

    public static boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        SharedMapServerState state = ACTIVE.get();
        return state != null && state.isChunkRendered(dimensionId, chunkX, chunkZ);
    }

    public int markTileDelta(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return 0;
        }
        return renderedChunks.markTile(packet.dimensionId(), packet.tileX(), packet.tileZ(), packet.coverageMask());
    }

    public boolean isChunkRendered(String dimensionId, int chunkX, int chunkZ) {
        return renderedChunks.isRendered(dimensionId, chunkX, chunkZ);
    }

    public void clearAll() {
        renderedChunks.clearAll();
    }
}
