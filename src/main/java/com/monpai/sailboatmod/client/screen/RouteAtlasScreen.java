package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.monpai.sailboatmod.client.map.route.RouteAtlasView;
import com.monpai.sailboatmod.client.map.route.SharedMapRouteLayer;
import com.monpai.sailboatmod.client.roadplanner.*;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.territory.ClaimMapElement;
import com.monpai.sailboatmod.map.route.MapRouteKind;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;

/** Read-only route map opened exclusively by the transport atlas item. */
public final class RouteAtlasScreen extends ThemedScreen {
    private final RouteAtlasView camera = new RouteAtlasView();
    private final SharedMapRouteLayer routes = new SharedMapRouteLayer();
    private final EnumMap<MapRouteKind, Button> filters = new EnumMap<>(MapRouteKind.class);
    private UIElement body;
    private ClaimMapElement map;

    public RouteAtlasScreen() {
        super(Component.translatable("screen.sailboatmod.route_atlas.title"));
        routes.setInspectionEnabled(true);
    }

    public static void open() { Minecraft.getInstance().setScreen(new RouteAtlasScreen()); }
    public SharedMapRouteLayer routeLayer() { return routes; }
    public RoadPlannerMapView mapView() { return camera.view(); }
    public RoadPlannerMapLayout.Rect mapRect() {
        ClaimMapViewport box = map == null ? new ClaimMapViewport(0, 0, 1, 1) : map.viewport();
        return new RoadPlannerMapLayout.Rect(box.x(), box.y(), box.width(), box.height());
    }

    @Override protected float windowWidth() { return 720; }

    @Override protected void init() {
        if (body != null) sizeBody();
        super.init();
    }

    private void sizeBody() { body.layout(l -> l.height(Math.max(120, height - 48))); }

    @Override protected UIElement buildBody() {
        body = Ui.column();
        sizeBody();
        UIElement toolbar = Ui.row();
        toolbar.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S).flexShrink(0));
        for (MapRouteKind kind : MapRouteKind.values()) {
            Button filter = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
                routes.setVisible(kind, !routes.visible(kind));
                refreshFilters();
            });
            filter.layout(l -> l.width(0).flexGrow(1).minWidth(0));
            filters.put(kind, filter);
            toolbar.addChild(filter);
        }
        toolbar.addChild(Ui.iconButton(new ItemStack(Items.COMPASS),
                Component.translatable("screen.sailboatmod.route_atlas.recenter"), this::recenter));
        map = new ClaimMapElement(new ClaimMapElement.Host() {
            @Override public void paintMap(GuiGraphics graphics, ClaimMapViewport box, int mouseX, int mouseY) {
                paint(graphics, box, mouseX, mouseY);
            }
            @Override public void zoomMap(double x, double y, double factor, ClaimMapViewport box) {
                mapView().zoomAround(x, y, factor, mapRect());
            }
            @Override public void clickMap(double x, double y, ClaimMapViewport box, boolean extend) {
                routes.inspectClick(x, y, mapView(), mapRect());
            }
            @Override public void panMap(double x, double y) { mapView().panByScreenDelta(x, y); }
            @Override public void panFinished() { }
        });
        map.layout(l -> l.widthPercent(100).height(0).flexGrow(1).flexShrink(1).minHeight(48));
        body.addChildren(toolbar, map, Ui.paragraph(Component.translatable("screen.sailboatmod.route_atlas.controls"), "sm-muted"));
        refreshFilters();
        return body;
    }

    @Override protected void afterInit() {
        updateContext();
        refreshFilters();
    }

    @Override protected void onUiTick() { updateContext(); }

    private void updateContext() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        RoadPlannerTileManager terrain = RoadPlannerTileManager.sharedDefault();
        terrain.refreshWorldContext();
        camera.updateContext(terrain.worldId(), mc.level.dimension().location().toString(), mc.player.getX(), mc.player.getZ());
        routes.refreshWorldContext();
        routes.setInspectionEnabled(true);
    }

    private void recenter() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) camera.recenter(mc.player.getX(), mc.player.getZ());
    }

    private void refreshFilters() {
        filters.forEach((kind, button) -> {
            button.setText(Component.empty()
                    .append(Component.literal(routes.visible(kind) ? "━ " : "– ").withColor(SharedMapRouteLayer.color(kind) & 0xFFFFFF))
                    .append(SharedMapRouteLayer.kindText(kind)));
            Ui.setClass(button, "sm-primary", routes.visible(kind));
        });
    }

    private void paint(GuiGraphics graphics, ClaimMapViewport box, int mouseX, int mouseY) {
        updateContext();
        RoadPlannerTileManager terrain = RoadPlannerTileManager.sharedDefault();
        new RoadPlannerMapCanvas(new RoadPlannerVanillaLayout.Rect(box.x(), box.y(), box.width(), box.height()),
                new RoadPlannerMapComponent(RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1),
                        new RoadMapViewport(box.x(), box.y(), box.width(), box.height())), mapView(), terrain).render(graphics, font);
        routes.render(graphics, font, mapView(), mapRect(), terrain.worldId(), terrain.dimensionId(), mouseX, mouseY);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int x = mapView().worldToScreenX(mc.player.getX(), mapRect());
            int y = mapView().worldToScreenZ(mc.player.getZ(), mapRect());
            graphics.fill(x - 3, y - 3, x + 4, y + 4, 0xFF101820);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
        }
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return map != null && map.dragTo(x, y, button) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return map != null && map.release(button) || super.mouseReleased(x, y, button);
    }

    @Override public void removed() {
        routes.clear();
        super.removed();
    }
}
