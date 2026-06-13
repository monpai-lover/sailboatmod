package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;

public final class RoadPlannerClientMapTileCache {
    private RoadPlannerClientMapTileCache() {
    }

    public static void applyToDefaultCache(RoadPlannerMapTileSyncPacket packet) {
        SharedMapClientState.defaultState().applyTileDelta(packet);
        // 网页地图瓦片改为完全由服务端取色生成（统一配色、消除客户端上传字节序导致的红水）。
        // 服务端的磁盘 region 监听会自动重渲所有已保存区块，因此不再需要客户端上传。
    }
}
