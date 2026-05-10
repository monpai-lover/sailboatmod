package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

@FunctionalInterface
public interface RoadPlannerTileSyncReceiver {
    void applyMapTileSync(RoadPlannerMapTileSyncPacket packet);

    static boolean dispatch(Object candidate, RoadPlannerMapTileSyncPacket packet) {
        if (candidate instanceof RoadPlannerTileSyncReceiver receiver) {
            receiver.applyMapTileSync(packet);
            return true;
        }
        return false;
    }
}
