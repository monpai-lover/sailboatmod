package com.monpai.sailboatmod.client.map;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedMapClientStateTest {
    @Test
    void applyingTilePacketMarksCoveredChunks() {
        SharedMapClientState state = new SharedMapClientState(null);

        int applied = state.applyTileDelta(packet("minecraft:overworld", 0, 0, firstChunkOnlyMask()));

        assertTrue(applied >= 0);
        assertTrue(state.isRendered("minecraft:overworld", 0, 0));
        assertFalse(state.isRendered("minecraft:overworld", 1, 0));
    }

    @Test
    void clearAllDropsRenderedIndex() {
        SharedMapClientState state = new SharedMapClientState(null);
        state.applyTileDelta(packet("minecraft:overworld", 0, 0, null));

        state.clearAll();

        assertFalse(state.isRendered("minecraft:overworld", 0, 0));
    }

    private static RoadPlannerMapTileSyncPacket packet(String dimensionId, int tileX, int tileZ, boolean[] mask) {
        return new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                dimensionId,
                MapLod.LOD_1,
                tileX,
                tileZ,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
                mask
        );
    }

    private static boolean[] firstChunkOnlyMask() {
        boolean[] mask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                mask[y * RoadMapTileSpec.TILE_PIXELS + x] = true;
            }
        }
        return mask;
    }
}
