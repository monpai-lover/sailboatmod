package com.monpai.sailboatmod.roadplanner.map;

public record RoadMapCacheKey(String worldId,
                              String dimensionId,
                              int regionCenterX,
                              int regionCenterZ,
                              int regionSize,
                              MapLod lod) {
    public RoadMapCacheKey {
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        lod = lod == null ? MapLod.LOD_4 : lod;
    }
}
