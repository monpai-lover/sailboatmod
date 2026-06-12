package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedMapServerStateTest {
    @Test
    void activeStateCanBeInstalledAndClearedForTests() {
        SharedMapServerState state = new SharedMapServerState();
        SharedMapServerState.setActiveForTest(state);

        try {
            assertNotNull(SharedMapServerState.get());
        } finally {
            SharedMapServerState.clearActiveForTest();
        }

        assertFalse(SharedMapServerState.isRendered("minecraft:overworld", 0, 0));
    }

    @Test
    void markingTilePacketUpdatesRenderedIndex() {
        SharedMapServerState state = new SharedMapServerState();
        state.markTileDelta(packet());

        assertTrue(state.isChunkRendered("minecraft:overworld", 0, 0));
    }

    private static RoadPlannerMapTileSyncPacket packet() {
        return new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                0,
                0,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS],
                null
        );
    }
}
