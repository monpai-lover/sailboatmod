package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerTileKey(String worldId, String dimensionId, int tileX, int tileZ) {
    public RoadPlannerTileKey {
        worldId = sanitize(worldId == null || worldId.isBlank() ? "unknown" : worldId);
        dimensionId = sanitize(dimensionId == null || dimensionId.isBlank() ? "overworld" : dimensionId);
    }

    public String fileName() {
        return tileX + "_" + tileZ + ".png";
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }
}
