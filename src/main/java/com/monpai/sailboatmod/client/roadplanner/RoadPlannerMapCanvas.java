package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.List;

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

    List<RoadPlannerMapTileRenderPlanner.TileRequest> tileRequestsForTest() {
        return tileRequestsForRender();
    }

    private void renderTiles(GuiGraphics graphics) {
        tileManager.refreshWorldContext();
        for (RoadPlannerMapTileRenderPlanner.TileRequest request : tileRequestsForRender()) {
            RoadPlannerTile tile = tileManager.resolveRenderableTile(request.tileX(), request.tileZ(), request.lod());
            tile.render(graphics, request.screenX(), request.screenZ(), request.screenSize());
        }
    }

    private List<RoadPlannerMapTileRenderPlanner.TileRequest> tileRequestsForRender() {
        return RoadPlannerMapTileRenderPlanner.plan(rect, view);
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
