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
import net.minecraft.world.level.ChunkPos;
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

    @Test
    void jobMarksOnlyCoveredChunkPixelsInTileSyncMask() {
        RoadMapRoutePreloadPlan plan = new RoadMapRoutePreloadPlan(
                RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE,
                List.of(new ChunkPos(0, 0)),
                1,
                1);
        RoadPlannerMapPreloadJob job = new RoadPlannerMapPreloadJob(
                UUID.randomUUID(),
                1L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                plan);
        List<RoadPlannerMapTileSyncPacket> packets = new ArrayList<>();

        job.advance(1, this::loadSnapshot, packets::add);

        boolean[] mask = packets.get(0).coverageMask();
        assertEquals(RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS, mask.length);
        assertEquals(true, mask[0]);
        assertEquals(true, mask[15 * RoadMapTileSpec.TILE_PIXELS + 15]);
        assertEquals(false, mask[16]);
        assertEquals(false, mask[16 * RoadMapTileSpec.TILE_PIXELS]);
    }

    @Test
    void builtRoadRefreshJobEmitsAllLodsWithRefreshPurpose() {
        RoadMapRoutePreloadPlan plan = new RoadMapRoutePreloadPlan(
                RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY,
                List.of(new ChunkPos(0, 0), new ChunkPos(1, 0)),
                2,
                2);
        RoadPlannerMapPreloadJob job = new RoadPlannerMapPreloadJob(
                UUID.randomUUID(),
                7L,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                "",
                "minecraft:overworld",
                plan);
        List<RoadPlannerMapTileSyncPacket> packets = new ArrayList<>();

        int processed = job.advance(1, this::loadSnapshot, packets::add);

        assertEquals(1, processed);
        assertEquals(List.of(MapLod.LOD_1, MapLod.LOD_2, MapLod.LOD_4, MapLod.LOD_8),
                packets.stream().map(RoadPlannerMapTileSyncPacket::lod).toList());
        assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH, packets.get(0).purpose());
    }

    @Test
    void builtRoadRefreshMarksEntireTileCoveredSoStaleBlackPixelsAreReplaced() {
        RoadMapRoutePreloadPlan plan = new RoadMapRoutePreloadPlan(
                RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY,
                List.of(new ChunkPos(0, 0)),
                1,
                1);
        RoadPlannerMapPreloadJob job = new RoadPlannerMapPreloadJob(
                UUID.randomUUID(),
                8L,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                "",
                "minecraft:overworld",
                plan);
        List<RoadPlannerMapTileSyncPacket> packets = new ArrayList<>();

        job.advance(1, this::loadSnapshot, packets::add);

        boolean[] lod1Mask = packets.get(0).coverageMask();
        boolean[] lod4Mask = packets.get(2).coverageMask();
        assertEquals(RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS, lod1Mask.length);
        assertEquals(true, lod1Mask[0]);
        assertEquals(true, lod1Mask[lod1Mask.length - 1]);
        assertEquals(true, lod4Mask[0]);
        assertEquals(true, lod4Mask[lod4Mask.length - 1]);
    }

    private RoadMapSnapshot loadSnapshot(RoadPlannerTileKey key) {
        return new RoadMapSnapshot(
                1L,
                RoadMapRegion.centeredOn(new BlockPos(128, 0, 128), RoadMapTileSpec.TILE_BLOCKS, MapLod.LOD_1),
                List.of(),
                new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS]);
    }
}
