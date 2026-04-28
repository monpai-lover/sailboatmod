package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

public class RoadPlannerMapCanvas {
    private final RoadPlannerVanillaLayout.Rect rect;
    private final RoadPlannerMapComponent component;
    private final RoadPlannerMapView view;
    private final RoadPlannerTileManager tileManager;
    private final RoadPlannerHeightSampler heightSampler;

    public RoadPlannerMapCanvas(RoadPlannerVanillaLayout.Rect rect, RoadPlannerMapComponent component) {
        this(rect, component, null, null, (x, z) -> 64);
    }

    public RoadPlannerMapCanvas(RoadPlannerVanillaLayout.Rect rect,
                                RoadPlannerMapComponent component,
                                RoadPlannerMapView view,
                                RoadPlannerTileManager tileManager) {
        this(rect, component, view, tileManager, (x, z) -> 64);
    }

    public RoadPlannerMapCanvas(RoadPlannerVanillaLayout.Rect rect,
                                RoadPlannerMapComponent component,
                                RoadPlannerMapView view,
                                RoadPlannerTileManager tileManager,
                                RoadPlannerHeightSampler heightSampler) {
        this.rect = rect;
        this.component = component;
        this.view = view;
        this.tileManager = tileManager;
        this.heightSampler = heightSampler == null ? (x, z) -> 64 : heightSampler;
    }

    static RoadPlannerMapCanvas forTest(RoadPlannerVanillaLayout.Rect rect, RoadPlannerMapView view, RoadPlannerHeightSampler sampler) {
        return new RoadPlannerMapCanvas(rect,
                new RoadPlannerMapComponent(
                        RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1),
                        new RoadMapViewport(rect.x(), rect.y(), rect.width(), rect.height())
                ),
                view,
                null,
                sampler);
    }

    public boolean contains(double mouseX, double mouseY) {
        return rect.contains(mouseX, mouseY);
    }

    public BlockPos mouseToWorld(double mouseX, double mouseY) {
        if (view != null) {
            RoadPlannerMapLayout.Rect mapRect = mapRect();
            return heightSampler.blockPosAt(view.screenToWorldX(mouseX, mapRect), view.screenToWorldZ(mouseY, mapRect));
        }
        return component.guiToWorldXZ(mouseX, mouseY);
    }

    public RoadPlannerMapInteractionResult rightClickGraph(RoadPlannerClientState state,
                                                          RoadNetworkGraph graph,
                                                          double worldX,
                                                          double worldZ,
                                                          int mouseX,
                                                          int mouseY) {
        return new RoadPlannerMapInteraction(graph).rightClickRoadLine(state, worldX, worldZ, mouseX, mouseY, 8.0D);
    }

    public void render(GuiGraphics graphics, Font font) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xFF111820);
        if (tileManager != null && view != null) {
            renderTiles(graphics);
        }
    }

    public void renderPlaceholder(GuiGraphics graphics, Font font) {
        render(graphics, font);
    }

    private void renderTiles(GuiGraphics graphics) {
        tileManager.refreshWorldContext();
        RoadPlannerMapLayout.Rect mapRect = mapRect();
        double tileScreenSize = Math.max(16.0D, RoadPlannerTile.TILE_SIZE_BLOCKS * view.scale());
        int minWorldX = view.screenToWorldX(rect.x(), mapRect);
        int maxWorldX = view.screenToWorldX(rect.right(), mapRect);
        int minWorldZ = view.screenToWorldZ(rect.y(), mapRect);
        int maxWorldZ = view.screenToWorldZ(rect.bottom(), mapRect);
        int startTileX = Math.floorDiv(Math.min(minWorldX, maxWorldX), RoadPlannerTile.TILE_SIZE_BLOCKS) - 1;
        int endTileX = Math.floorDiv(Math.max(minWorldX, maxWorldX), RoadPlannerTile.TILE_SIZE_BLOCKS) + 1;
        int startTileZ = Math.floorDiv(Math.min(minWorldZ, maxWorldZ), RoadPlannerTile.TILE_SIZE_BLOCKS) - 1;
        int endTileZ = Math.floorDiv(Math.max(minWorldZ, maxWorldZ), RoadPlannerTile.TILE_SIZE_BLOCKS) + 1;
        for (int tileZ = startTileZ; tileZ <= endTileZ; tileZ++) {
            for (int tileX = startTileX; tileX <= endTileX; tileX++) {
                RoadPlannerTile tile = tileManager.getOrCreateTile(tileX, tileZ);
                int screenX = view.worldToScreenX(tileX * RoadPlannerTile.TILE_SIZE_BLOCKS, mapRect);
                int screenZ = view.worldToScreenZ(tileZ * RoadPlannerTile.TILE_SIZE_BLOCKS, mapRect);
                tile.render(graphics, screenX, screenZ, (int) Math.ceil(tileScreenSize) + 1);
            }
        }
    }

    private void renderAnchorMarker(GuiGraphics graphics, Font font) {
        int centerX = rect.x() + rect.width() / 2;
        int centerY = rect.y() + rect.height() / 2;
        graphics.fill(centerX - 5, centerY - 5, centerX + 5, centerY + 5, RoadPlannerMapTheme.NODE);
        graphics.drawString(font, "起点", centerX + 10, centerY - 4, RoadPlannerMapTheme.TEXT, false);
    }

    private RoadPlannerMapLayout.Rect mapRect() {
        return new RoadPlannerMapLayout.Rect(rect.x(), rect.y(), rect.width(), rect.height());
    }
}
