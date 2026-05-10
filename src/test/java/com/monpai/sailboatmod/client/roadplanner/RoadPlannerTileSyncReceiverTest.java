package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileSyncReceiverTest {
    @Test
    void dispatchesTileSyncToGenericReceiverScreens() {
        AtomicInteger applied = new AtomicInteger();
        RoadPlannerTileSyncReceiver receiver = packet -> applied.incrementAndGet();
        RoadPlannerMapTileSyncPacket packet = new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world",
                "minecraft:overworld",
                MapLod.LOD_1,
                0,
                0,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE]);

        assertTrue(RoadPlannerTileSyncReceiver.dispatch(receiver, packet));
        assertEquals(1, applied.get());
    }

    @Test
    void ignoresObjectsThatDoNotReceiveTileSync() {
        assertFalse(RoadPlannerTileSyncReceiver.dispatch(new Object(), null));
    }
}
