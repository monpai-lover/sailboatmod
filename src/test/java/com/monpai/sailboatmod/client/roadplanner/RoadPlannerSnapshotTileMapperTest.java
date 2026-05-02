package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerSnapshotTileMapperTest {
    @Test
    void mapsLodPixelsToTileLocalBlockPixels() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world",
                "minecraft:overworld",
                1L,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT,
                0,
                0,
                8,
                MapLod.LOD_4,
                2,
                2,
                new int[]{0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444});

        List<RoadPlannerSnapshotTileMapper.TilePixel> pixels = RoadPlannerSnapshotTileMapper.map(packet);

        assertEquals(64, pixels.size());
        assertTrue(pixels.stream().anyMatch(pixel -> pixel.tileX() == -1 && pixel.tileZ() == -1 && pixel.localX() == 252 && pixel.localZ() == 252 && pixel.argb() == 0xFF111111));
        assertTrue(pixels.stream().anyMatch(pixel -> pixel.tileX() == 0 && pixel.tileZ() == 0 && pixel.localX() == 3 && pixel.localZ() == 3 && pixel.argb() == 0xFF444444));
    }

    @Test
    void ignoresPacketsWithMismatchedPixelCount() {
        RoadMapSnapshotSyncPacket packet = new RoadMapSnapshotSyncPacket(
                UUID.randomUUID(),
                "world",
                "minecraft:overworld",
                1L,
                RoadMapSnapshotRequestPacket.Purpose.VIEWPORT,
                0,
                0,
                8,
                MapLod.LOD_4,
                2,
                2,
                new int[]{0xFF111111});

        assertTrue(RoadPlannerSnapshotTileMapper.map(packet).isEmpty());
    }
}
