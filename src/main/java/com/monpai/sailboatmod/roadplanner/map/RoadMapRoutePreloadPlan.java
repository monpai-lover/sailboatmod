package com.monpai.sailboatmod.roadplanner.map;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileKey;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashSet;
import java.util.List;

public record RoadMapRoutePreloadPlan(CoverageMode coverageMode,
                                      List<ChunkPos> chunks,
                                      int pathChunkCount,
                                      int rectangleChunkCount) {
    public RoadMapRoutePreloadPlan {
        coverageMode = coverageMode == null ? CoverageMode.PATH_ONLY : coverageMode;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        pathChunkCount = Math.max(0, pathChunkCount);
        rectangleChunkCount = Math.max(0, rectangleChunkCount);
    }

    public List<RoadPlannerTileKey> tileKeys(String worldId, String dimensionId, MapLod lod) {
        LinkedHashSet<RoadPlannerTileKey> keys = new LinkedHashSet<>();
        MapLod safeLod = lod == null ? MapLod.LOD_1 : lod;
        for (ChunkPos chunk : chunks) {
            int tileX = Math.floorDiv(chunk.x, RoadMapTileSpec.TILE_BLOCKS / 16);
            int tileZ = Math.floorDiv(chunk.z, RoadMapTileSpec.TILE_BLOCKS / 16);
            keys.add(new RoadPlannerTileKey(worldId, dimensionId, safeLod, tileX, tileZ));
        }
        return List.copyOf(keys);
    }

    public enum CoverageMode {
        RECTANGLE,
        PATH_ONLY
    }
}
