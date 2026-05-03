package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;

import java.util.ArrayList;
import java.util.List;

final class RoadPlannerMapTileRenderPlanner {
    private RoadPlannerMapTileRenderPlanner() {
    }

    static List<TileRequest> plan(RoadPlannerVanillaLayout.Rect rect, RoadPlannerMapView view) {
        if (rect == null || view == null) {
            return List.of();
        }
        RoadPlannerMapLayout.Rect mapRect = new RoadPlannerMapLayout.Rect(rect.x(), rect.y(), rect.width(), rect.height());
        double tileScreenSize = Math.max(16.0D, RoadPlannerTile.TILE_SIZE_BLOCKS * view.scale());
        int drawSize = (int) Math.ceil(tileScreenSize) + 1;
        int minWorldX = view.screenToWorldX(rect.x(), mapRect);
        int maxWorldX = view.screenToWorldX(rect.right(), mapRect);
        int minWorldZ = view.screenToWorldZ(rect.y(), mapRect);
        int maxWorldZ = view.screenToWorldZ(rect.bottom(), mapRect);
        int startTileX = Math.floorDiv(Math.min(minWorldX, maxWorldX), RoadPlannerTile.TILE_SIZE_BLOCKS) - 1;
        int endTileX = Math.floorDiv(Math.max(minWorldX, maxWorldX), RoadPlannerTile.TILE_SIZE_BLOCKS) + 1;
        int startTileZ = Math.floorDiv(Math.min(minWorldZ, maxWorldZ), RoadPlannerTile.TILE_SIZE_BLOCKS) - 1;
        int endTileZ = Math.floorDiv(Math.max(minWorldZ, maxWorldZ), RoadPlannerTile.TILE_SIZE_BLOCKS) + 1;
        List<TileRequest> requests = new ArrayList<>((endTileX - startTileX + 1) * (endTileZ - startTileZ + 1));
        for (int tileZ = startTileZ; tileZ <= endTileZ; tileZ++) {
            for (int tileX = startTileX; tileX <= endTileX; tileX++) {
                int screenX = view.worldToScreenX(tileX * RoadPlannerTile.TILE_SIZE_BLOCKS, mapRect);
                int screenZ = view.worldToScreenZ(tileZ * RoadPlannerTile.TILE_SIZE_BLOCKS, mapRect);
                requests.add(new TileRequest(tileX, tileZ, MapLod.LOD_1, screenX, screenZ, drawSize));
            }
        }
        return List.copyOf(requests);
    }

    record TileRequest(int tileX, int tileZ, MapLod lod, int screenX, int screenZ, int screenSize) {
    }
}
