package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

public final class RoadPlannerClientMapTileCache {
    private RoadPlannerClientMapTileCache() {
    }

    public static void applyToDefaultCache(RoadPlannerMapTileSyncPacket packet) {
        if (packet == null) {
            return;
        }
        try {
            RoadPlannerTileManager.sharedDefault().applyTileSync(packet);
        } catch (RuntimeException ignored) {
        }
    }
}
