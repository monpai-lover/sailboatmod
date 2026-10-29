package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;

public record RoadPlannerTileKey(String worldId, String dimensionId, MapLod lod, int tileX, int tileZ, int terrainBlocksPerPixel) {
    public RoadPlannerTileKey {
        worldId = sanitize(worldId == null || worldId.isBlank() ? "unknown" : worldId);
        dimensionId = sanitize(dimensionId == null || dimensionId.isBlank() ? "overworld" : dimensionId);
        lod = lod == null ? MapLod.LOD_1 : lod;
        if (terrainBlocksPerPixel != 0 && !com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid.validBps(terrainBlocksPerPixel)) throw new IllegalArgumentException("Invalid terrain grid");
    }

    public RoadPlannerTileKey(String worldId, String dimensionId, MapLod lod, int tileX, int tileZ) {
        this(worldId, dimensionId, lod, tileX, tileZ, 0);
    }

    public RoadPlannerTileKey(String worldId, String dimensionId, int tileX, int tileZ) {
        this(worldId, dimensionId, MapLod.LOD_1, tileX, tileZ, 0);
    }

    public String fileName() {
        return tileX + "_" + tileZ + ".png";
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }
}
