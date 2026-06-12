package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

public final class RoadPlannerClientMapTileCache {
    private RoadPlannerClientMapTileCache() {
    }

    public static void applyToDefaultCache(RoadPlannerMapTileSyncPacket packet) {
        SharedMapClientState.defaultState().applyTileDelta(packet);
    }
}
