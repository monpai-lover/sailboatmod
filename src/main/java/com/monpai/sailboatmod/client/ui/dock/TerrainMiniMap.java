package com.monpai.sailboatmod.client.ui.dock;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Top-down terrain map around a dock or post station with its berth zone, the selected route and nearby vehicles.
 *
 * <p>Players who may manage the terminal drag a rectangle to set the zone; the result is reported in block offsets
 * through {@link ZoneListener} and the screen sends the same {@code SetDockZonePacket} as before. Terrain colours
 * are sampled once and refreshed every two seconds instead of every frame.</p>
 */
public final class TerrainMiniMap extends UIElement {
    @FunctionalInterface
    public interface ZoneListener {
        void onZone(int minX, int maxX, int minZ, int maxZ);
    }

    private static final int RADIUS = DockBlockEntity.MINIMAP_RADIUS;
    private static final long TERRAIN_REFRESH_MS = 2000;

    private BlockPos center = BlockPos.ZERO;
    private boolean landPalette;
    private int zoneMinX;
    private int zoneMaxX;
    private int zoneMinZ;
    private int zoneMaxZ;
    private List<Vec3> route = List.of();
    private List<String> boatNames = List.of();
    private List<Vec3> boatPositions = List.of();
    private boolean editable;
    private ZoneListener zoneListener = (a, b, c, d) -> { };

    private int[] terrain = new int[0];
    private int terrainW;
    private int terrainH;
    private BlockPos terrainCenter;
    private long terrainSampledAt;

    private boolean selecting;
    private int startPx;
    private int startPz;
    private int nowPx;
    private int nowPz;
    private String hoveredBoat;
    private long lastDrawnAt;

    public TerrainMiniMap() {
        addClass("sm-minimap");
        layout(l -> l.paddingAll(1));
        addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (editable && event.button == 0 && begin(event.x, event.y)) {
                event.stopPropagation();
            }
        });
        addEventListener(UIEvents.MOUSE_MOVE, event -> dragTo(event.x, event.y));
        addEventListener(UIEvents.MOUSE_UP, event -> {
            if (event.button == 0) {
                release(event.x, event.y);
            }
        });
    }

    public TerrainMiniMap setCenter(BlockPos value) {
        center = value == null ? BlockPos.ZERO : value;
        return this;
    }

    /** Post stations use muted land colours; ports show bright water. */
    public TerrainMiniMap setLandPalette(boolean value) {
        if (landPalette != value) {
            landPalette = value;
            terrainCenter = null;
        }
        return this;
    }

    public TerrainMiniMap setZone(int minX, int maxX, int minZ, int maxZ) {
        zoneMinX = minX;
        zoneMaxX = maxX;
        zoneMinZ = minZ;
        zoneMaxZ = maxZ;
        return this;
    }

    public TerrainMiniMap setRoute(List<Vec3> waypoints) {
        route = waypoints == null ? List.of() : waypoints;
        return this;
    }

    public TerrainMiniMap setBoats(List<String> names, List<Vec3> positions) {
        boatNames = names == null ? List.of() : names;
        boatPositions = positions == null ? List.of() : positions;
        return this;
    }

    public TerrainMiniMap setEditable(boolean value) {
        editable = value;
        if (!value) {
            selecting = false;
        }
        return this;
    }

    public TerrainMiniMap onZoneSelected(ZoneListener listener) {
        zoneListener = listener;
        return this;
    }

    /** Name of the vehicle under the mouse, for the screen to show as a tooltip. */
    public String hoveredBoat() {
        // Not drawn this frame (its page is hidden): nothing is hovered.
        return System.currentTimeMillis() - lastDrawnAt > 100 ? null : hoveredBoat;
    }

    public boolean isSelecting() {
        return selecting;
    }

    // ---------------------------------------------------------------- zone dragging

    private boolean begin(double mouseX, double mouseY) {
        int w = mapWidth();
        int h = mapHeight();
        double localX = mouseX - getContentX();
        double localY = mouseY - getContentY();
        if (w < 2 || h < 2 || localX < 0 || localY < 0 || localX >= w || localY >= h) {
            return false;
        }
        selecting = true;
        startPx = MiniMapProjection.clamp((int) localX, 0, w - 1);
        startPz = MiniMapProjection.clamp((int) localY, 0, h - 1);
        nowPx = startPx;
        nowPz = startPz;
        return true;
    }

    /** Follows the mouse while a zone is being dragged; the screen forwards drags that leave the map. */
    public boolean dragTo(double mouseX, double mouseY) {
        if (!selecting) {
            return false;
        }
        nowPx = MiniMapProjection.clamp((int) (mouseX - getContentX()), 0, mapWidth() - 1);
        nowPz = MiniMapProjection.clamp((int) (mouseY - getContentY()), 0, mapHeight() - 1);
        return true;
    }

    /** Ends a drag and reports the zone; returns false when no drag was in progress. */
    public boolean release(double mouseX, double mouseY) {
        if (!selecting) {
            return false;
        }
        dragTo(mouseX, mouseY);
        selecting = false;
        int[] zone = MiniMapProjection.zoneFromDrag(startPx, startPz, nowPx, nowPz, mapWidth(), mapHeight(), RADIUS);
        zoneListener.onZone(zone[0], zone[1], zone[2], zone[3]);
        return true;
    }

    private int mapWidth() {
        return Math.max(0, Math.round(getContentWidth()));
    }

    private int mapHeight() {
        return Math.max(0, Math.round(getContentHeight()));
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void drawContents(GUIContext context) {
        super.drawContents(context);
        GuiGraphics g = context.graphics;
        lastDrawnAt = System.currentTimeMillis();
        int x = Math.round(getContentX());
        int y = Math.round(getContentY());
        int w = mapWidth();
        int h = mapHeight();
        if (w < 2 || h < 2) {
            return;
        }
        drawTerrain(g, x, y, w, h);

        int rx1 = x + MiniMapProjection.toPixel(zoneMinX, w, RADIUS);
        int rx2 = x + MiniMapProjection.toPixel(zoneMaxX, w, RADIUS);
        int rz1 = y + MiniMapProjection.toPixel(zoneMinZ, h, RADIUS);
        int rz2 = y + MiniMapProjection.toPixel(zoneMaxZ, h, RADIUS);
        g.fill(Math.min(rx1, rx2), Math.min(rz1, rz2), Math.max(rx1, rx2) + 1, Math.max(rz1, rz2) + 1, 0x3345D7A8);
        outline(g, rx1, rz1, rx2, rz2, 0xFF56DDB4);

        drawRoute(g, x, y, w, h);
        drawBoats(g, x, y, w, h, context.mouseX, context.mouseY);

        if (selecting) {
            outline(g, x + startPx, y + startPz, x + nowPx, y + nowPz, 0xFFFFE28A);
        }
    }

    private void drawTerrain(GuiGraphics g, int x, int y, int w, int h) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (terrainW != w || terrainH != h || !center.equals(terrainCenter) || now - terrainSampledAt > TERRAIN_REFRESH_MS) {
            sampleTerrain(level, w, h);
            terrainSampledAt = now;
        }
        // Draw horizontal runs of equal colour as one quad.
        for (int py = 0; py < h; py++) {
            int runStart = 0;
            int runColor = terrain[py * w];
            for (int px = 1; px <= w; px++) {
                int color = px < w ? terrain[py * w + px] : 0;
                if (px == w || color != runColor) {
                    g.fill(x + runStart, y + py, x + px, y + py + 1, runColor);
                    runStart = px;
                    runColor = color;
                }
            }
        }
    }

    private void sampleTerrain(ClientLevel level, int w, int h) {
        terrain = new int[w * h];
        terrainW = w;
        terrainH = h;
        terrainCenter = center;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int py = 0; py < h; py++) {
            int wz = center.getZ() + MiniMapProjection.sampleOffset(py, h, RADIUS);
            for (int px = 0; px < w; px++) {
                int wx = center.getX() + MiniMapProjection.sampleOffset(px, w, RADIUS);
                int wy = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                pos.set(wx, wy, wz);
                int color;
                if (wy >= level.getMinBuildHeight() && level.getFluidState(pos).is(FluidTags.WATER)) {
                    color = landPalette ? 0xFF27415A : 0xFF2F8FBF;
                } else {
                    MapColor mapColor = level.getBlockState(pos).getMapColor(level, pos);
                    int base = mapColor == null ? 0x55606A : mapColor.col;
                    if (landPalette) {
                        int r = (((base >> 16) & 0xFF) + 0x76) / 2;
                        int gr = (((base >> 8) & 0xFF) + 0x95) / 2;
                        int b = ((base & 0xFF) + 0x62) / 2;
                        base = (r << 16) | (gr << 8) | b;
                    }
                    color = 0xFF000000 | (base & 0xFFFFFF);
                }
                terrain[py * w + px] = color;
            }
        }
    }

    private void drawRoute(GuiGraphics g, int x, int y, int w, int h) {
        if (route.isEmpty()) {
            return;
        }
        int prevX = routeX(route.get(0), x, w);
        int prevZ = routeZ(route.get(0), y, h);
        for (int i = 1; i < route.size(); i++) {
            int cx = routeX(route.get(i), x, w);
            int cz = routeZ(route.get(i), y, h);
            line(g, prevX, prevZ, cx, cz, 0xFFEFD36A);
            prevX = cx;
            prevZ = cz;
        }
        dot(g, routeX(route.get(0), x, w), routeZ(route.get(0), y, h), 0xFF6BFF95);
        Vec3 last = route.get(route.size() - 1);
        dot(g, routeX(last, x, w), routeZ(last, y, h), 0xFFFF6F6F);
    }

    private void drawBoats(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        hoveredBoat = null;
        int bestDistance = Integer.MAX_VALUE;
        int count = Math.min(boatNames.size(), boatPositions.size());
        for (int i = 0; i < count; i++) {
            Vec3 boat = boatPositions.get(i);
            int bx = routeX(boat, x, w);
            int bz = routeZ(boat, y, h);
            int dx = mouseX - bx;
            int dz = mouseY - bz;
            int distance = dx * dx + dz * dz;
            boolean hovered = distance <= 9;
            dot(g, bx, bz, hovered ? 0xFFFFD166 : 0xFFFFFFFF);
            if (hovered && distance < bestDistance) {
                bestDistance = distance;
                hoveredBoat = boatNames.get(i);
            }
        }
    }

    private int routeX(Vec3 point, int x, int w) {
        return x + MiniMapProjection.toPixel((int) Math.round(point.x - center.getX()), w, RADIUS);
    }

    private int routeZ(Vec3 point, int y, int h) {
        return y + MiniMapProjection.toPixel((int) Math.round(point.z - center.getZ()), h, RADIUS);
    }

    private static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        g.fill(minX, minY, maxX + 1, minY + 1, color);
        g.fill(minX, maxY, maxX + 1, maxY + 1, color);
        g.fill(minX, minY, minX + 1, maxY + 1, color);
        g.fill(maxX, minY, maxX + 1, maxY + 1, color);
    }

    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int px = x1 + (x2 - x1) * i / steps;
            int py = y1 + (y2 - y1) * i / steps;
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static void dot(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 1, y - 1, x + 2, y + 2, color);
    }
}
