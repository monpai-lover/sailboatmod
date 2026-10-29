package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid;

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
        int bps = SharedMapTileGrid.forScale(view.scale(), rect.width(), rect.height());
        long worldSpan = SharedMapTileGrid.span(bps);
        double tileScreenSize = worldSpan * view.scale();
        int drawSize = (int) Math.ceil(tileScreenSize) + 1;
        int minWorldX = view.screenToWorldX(rect.x(), mapRect);
        int maxWorldX = view.screenToWorldX(rect.right(), mapRect);
        int minWorldZ = view.screenToWorldZ(rect.y(), mapRect);
        int maxWorldZ = view.screenToWorldZ(rect.bottom(), mapRect);
        int startTileX = SharedMapTileGrid.tileAt(Math.min(minWorldX, maxWorldX), bps) - 1;
        int endTileX = SharedMapTileGrid.tileAt(Math.max(minWorldX, maxWorldX), bps) + 1;
        int startTileZ = SharedMapTileGrid.tileAt(Math.min(minWorldZ, maxWorldZ), bps) - 1;
        int endTileZ = SharedMapTileGrid.tileAt(Math.max(minWorldZ, maxWorldZ), bps) + 1;
        List<TileRequest> requests = new ArrayList<>();
        for (int tileZ = startTileZ; tileZ <= endTileZ; tileZ++) {
            for (int tileX = startTileX; tileX <= endTileX; tileX++) {
                if (!SharedMapTileGrid.validTile(tileX, tileZ, bps)) continue;
                int screenX = view.worldToScreenX(tileX * (double) worldSpan, mapRect);
                int screenZ = view.worldToScreenZ(tileZ * (double) worldSpan, mapRect);
                requests.add(new TileRequest(tileX, tileZ, MapLod.LOD_1, screenX, screenZ, drawSize, bps));
            }
        }
        return List.copyOf(requests);
    }

    record TileRequest(int tileX, int tileZ, MapLod lod, int screenX, int screenZ, int screenSize, int blocksPerPixel) {
    }
}
