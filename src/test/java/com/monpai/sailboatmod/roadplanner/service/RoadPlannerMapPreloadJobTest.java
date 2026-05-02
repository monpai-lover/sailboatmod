package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlanner;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerMapPreloadJobTest {
    @Test
    void jobEmitsTilePacketsForAllLodsAndAdvancesProgress() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 0)));
        RoadPlannerMapPreloadJob job = new RoadPlannerMapPreloadJob(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                plan);
        List<RoadPlannerMapTileSyncPacket> packets = new ArrayList<>();

        int processed = job.advance(1, this::loadSnapshot, packets::add);

        assertEquals(1, processed);
        assertEquals(4, packets.size());
        assertEquals(MapLod.LOD_1, packets.get(0).lod());
        assertEquals(MapLod.LOD_2, packets.get(1).lod());
        assertEquals(MapLod.LOD_4, packets.get(2).lod());
        assertEquals(MapLod.LOD_8, packets.get(3).lod());
    }

    private RoadMapSnapshot loadSnapshot(RoadPlannerTileKey key) {
        return new RoadMapSnapshot(
                1L,
                RoadMapRegion.centeredOn(new BlockPos(128, 0, 128), RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1),
                List.of(),
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS]);
    }
}
