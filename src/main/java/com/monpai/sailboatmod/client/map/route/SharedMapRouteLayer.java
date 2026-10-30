package com.monpai.sailboatmod.client.map.route;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapLayout;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerTileManager;
import com.monpai.sailboatmod.map.route.MapRouteEntry;
import com.monpai.sailboatmod.map.route.MapRouteKind;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.maproute.MapRouteSnapshotPacket;
import com.monpai.sailboatmod.network.packet.maproute.MapRouteViewportRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared registered-route display for the planner and both territory maps. */
public final class SharedMapRouteLayer {
    public static final double HIT_TOLERANCE_PIXELS = 6.0;
    private static final Map<UUID, WeakReference<SharedMapRouteLayer>> OPEN = new HashMap<>();
    private final SharedMapRouteState state;
    private MapRouteEntry hovered;

    public SharedMapRouteLayer() { this(UUID.randomUUID()); }

    public SharedMapRouteLayer(UUID sessionId) {
        state = new SharedMapRouteState(sessionId);
        register();
    }

    public UUID sessionId() { return state.sessionId(); }
    public boolean inspectionEnabled() { return state.inspectionEnabled(); }
    public void setInspectionEnabled(boolean enabled) { state.setInspectionEnabled(enabled); }
    public boolean visible(MapRouteKind kind) { return state.visible(kind); }
    public void setVisible(MapRouteKind kind, boolean enabled) {
        state.setVisible(kind, enabled);
        if (hovered != null && !state.visible(hovered.kind())) hovered = null;
    }
    public Optional<MapRouteEntry> selected() { return state.selected(); }
    public Optional<MapRouteEntry> hoveredRoute() { return Optional.ofNullable(hovered); }
    public boolean truncated() { return state.truncated(); }

    private void register() {
        OPEN.entrySet().removeIf(entry -> entry.getValue().get() == null);
        OPEN.put(state.sessionId(), new WeakReference<>(this));
    }

    /** Called on the client thread by the payload handler. */
    public static void accept(MapRouteSnapshotPacket packet) {
        if (packet == null) return;
        WeakReference<SharedMapRouteLayer> reference = OPEN.get(packet.sessionId());
        SharedMapRouteLayer layer = reference == null ? null : reference.get();
        if (layer == null) return;
        layer.refreshWorldContext();
        layer.state.accept(packet.sessionId(), packet.requestId(), packet.worldId(), packet.dimensionId(), packet.snapshot());
    }

    /** Also used while a settings dialog covers a retained map, so dimension changes clear its details. */
    public void refreshWorldContext() {
        if (state.worldId().isBlank() && state.dimensionId().isBlank()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            clear();
            return;
        }
        String dimension = minecraft.level.dimension().location().toString();
        RoadPlannerTileManager manager = RoadPlannerTileManager.sharedDefault();
        manager.refreshWorldContext();
        if (!dimension.equals(state.dimensionId()) || !manager.worldId().equals(state.worldId())) {
            state.request(manager.worldId(), dimension, null, System.currentTimeMillis());
            hovered = null;
        }
    }

    /** Logout invalidates every live screen, including a retained parent behind the options dialog. */
    public static void clearAll() {
        for (WeakReference<SharedMapRouteLayer> reference : OPEN.values()) {
            SharedMapRouteLayer layer = reference.get();
            if (layer != null) layer.clear();
        }
        OPEN.clear();
    }

    public void clear() {
        state.clear();
        hovered = null;
    }

    public void render(GuiGraphics graphics, Font font, RoadPlannerMapView view, RoadPlannerMapLayout.Rect rect,
                       String worldId, String dimensionId, int mouseX, int mouseY) {
        register();
        state.request(worldId, dimensionId, SharedMapRouteProjection.viewport(view, rect), System.currentTimeMillis())
                .ifPresent(request -> ModNetwork.CHANNEL.sendToServer(new MapRouteViewportRequestPacket(request.sessionId(),
                        request.requestId(), request.worldId(), request.dimensionId(), request.viewport())));
        List<MapRouteEntry> routes = state.visibleRoutes();
        hovered = SharedMapRouteProjection.hit(routes, view, rect, mouseX, mouseY, HIT_TOLERANCE_PIXELS).orElse(null);
        String selectedId = selected().map(MapRouteEntry::id).orElse("");
        graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
        try {
            for (MapRouteEntry route : routes) if (!route.id().equals(selectedId)) drawRoute(graphics, route, view, rect, false);
            selected().ifPresent(route -> drawRoute(graphics, route, view, rect, true));
            drawLegend(graphics, font, rect);
            drawDetails(graphics, font, rect);
            if (hovered != null) drawHover(graphics, font, rect, mouseX, mouseY, hovered.name());
        } finally {
            graphics.disableScissor();
        }
    }

    /** Only an explicitly enabled inspection mode consumes a left click in the map. */
    public boolean inspectClick(double mouseX, double mouseY, RoadPlannerMapView view, RoadPlannerMapLayout.Rect rect) {
        if (!inspectionEnabled() || mouseX < rect.x() || mouseX >= rect.right() || mouseY < rect.y() || mouseY >= rect.bottom()) return false;
        state.select(SharedMapRouteProjection.hit(state.visibleRoutes(), view, rect, mouseX, mouseY, HIT_TOLERANCE_PIXELS)
                .map(MapRouteEntry::id).orElse(""));
        return true;
    }

    private void drawRoute(GuiGraphics graphics, MapRouteEntry route, RoadPlannerMapView view,
                           RoadPlannerMapLayout.Rect rect, boolean selected) {
        float width = selected ? 4 : hovered != null && hovered.id().equals(route.id()) ? 3 : 2;
        List<SharedMapRouteProjection.Segment> segments = SharedMapRouteProjection.segments(route, view, rect);
        for (var segment : segments) drawLine(graphics, segment, width + 2, 0xF0182027);
        for (var segment : segments) drawLine(graphics, segment, width, color(route.kind()));
        if (selected) for (var segment : segments) drawLine(graphics, segment, 1, 0xFFF8FBFF);
    }

    /** One bounded quad per segment keeps far views independent of on-screen line length. */
    private static void drawLine(GuiGraphics graphics, SharedMapRouteProjection.Segment line, float width, int color) {
        double dx = (double) line.x2() - line.x1(), dy = (double) line.y2() - line.y1();
        double length = Math.hypot(dx, dy);
        if (length == 0) {
            int half = Math.max(1, (int) Math.ceil(width / 2));
            graphics.fill(line.x1() - half, line.y1() - half, line.x1() + half, line.y1() + half, color);
            return;
        }
        float offsetX = (float) (-dy / length * width / 2), offsetY = (float) (dx / length * width / 2);
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        vertices.addVertex(matrix, line.x1() + offsetX, line.y1() + offsetY, 0).setColor(color);
        vertices.addVertex(matrix, line.x2() + offsetX, line.y2() + offsetY, 0).setColor(color);
        vertices.addVertex(matrix, line.x2() - offsetX, line.y2() - offsetY, 0).setColor(color);
        vertices.addVertex(matrix, line.x1() - offsetX, line.y1() - offsetY, 0).setColor(color);
    }

    private void drawLegend(GuiGraphics graphics, Font font, RoadPlannerMapLayout.Rect rect) {
        if (rect.width() < 12 || rect.height() < 17) return;
        int x = rect.x() + 5;
        int maxWidth = rect.width() - 10;
        int lineHeight = font.lineHeight + 2;
        List<LegendItem> legend = new ArrayList<>();
        int row = 0;
        int rowX = 0;
        int usedWidth = 0;
        for (MapRouteKind kind : MapRouteKind.values()) {
            String label = font.plainSubstrByWidth(kindText(kind).getString(), Math.max(0, maxWidth - 10));
            int itemWidth = 10 + font.width(label);
            if (rowX > 0 && rowX + itemWidth > maxWidth) {
                row++;
                rowX = 0;
            }
            legend.add(new LegendItem(kind, label, rowX, row));
            usedWidth = Math.max(usedWidth, rowX + itemWidth);
            rowX += itemWidth + 5;
        }
        int legendHeight = (row + 1) * lineHeight;
        int availableHeight = rect.height() - 8;
        if (legendHeight > availableHeight) return;
        List<Component> statuses = new ArrayList<>();
        if (truncated()) statuses.add(text("truncated"));
        int statusLineLimit = Math.max(0, (availableHeight - legendHeight - 2) / lineHeight);
        List<FormattedCharSequence> statusLines = new ArrayList<>();
        for (Component status : statuses) {
            for (FormattedCharSequence line : font.split(status, maxWidth)) {
                if (statusLines.size() == statusLineLimit) break;
                statusLines.add(line);
                usedWidth = Math.max(usedWidth, font.width(line));
            }
        }
        int statusHeight = statusLines.size() * lineHeight + (statusLines.isEmpty() ? 0 : 2);
        int bottom = rect.bottom() - 5;
        int y = bottom - legendHeight - statusHeight;
        graphics.fill(x - 2, y - 2, Math.min(rect.right() - 3, x + usedWidth + 2), bottom + 2, 0xBB111820);
        for (FormattedCharSequence line : statusLines) {
            graphics.drawString(font, line, x, y, 0xFFFFD478, false);
            y += lineHeight;
        }
        if (!statusLines.isEmpty()) y += 2;
        for (LegendItem item : legend) {
            int itemX = x + item.x();
            int itemY = y + item.row() * lineHeight;
            graphics.fill(itemX, itemY + 3, itemX + 7, itemY + 5, visible(item.kind()) ? color(item.kind()) : 0xFF68717A);
            graphics.drawString(font, item.label(), itemX + 10, itemY, visible(item.kind()) ? 0xFFF0F4F7 : 0xFF929BA3, false);
        }
    }

    private record LegendItem(MapRouteKind kind, String label, int x, int row) { }

    private void drawDetails(GuiGraphics graphics, Font font, RoadPlannerMapLayout.Rect rect) {
        Optional<MapRouteEntry> selected = selected();
        if (selected.isEmpty()) return;
        MapRouteEntry route = selected.get();
        int width = Math.min(220, Math.max(1, rect.width() - 12));
        int x = rect.right() - width - 6;
        int y = rect.y() + 6;
        List<Component> lines = detailLines(route);
        graphics.fill(x - 3, y - 3, x + width + 3, y + lines.size() * 10 + 2, 0xDC111820);
        for (int i = 0; i < lines.size(); i++) {
            String value = lines.get(i).getString();
            if (font.width(value) > width) value = font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
            graphics.drawString(font, value, x, y + i * 10, i == 0 ? color(route.kind()) : 0xFFF0F4F7, false);
        }
    }

    private static void drawHover(GuiGraphics graphics, Font font, RoadPlannerMapLayout.Rect rect, int mouseX, int mouseY, String name) {
        int available = Math.max(1, rect.width() - 10);
        String label = font.plainSubstrByWidth(name, available);
        int width = font.width(label);
        int x = Math.clamp(mouseX + 8, rect.x() + 3, Math.max(rect.x() + 3, rect.right() - width - 3));
        int y = Math.clamp(mouseY + 12, rect.y() + 3, Math.max(rect.y() + 3, rect.bottom() - 12));
        graphics.fill(x - 2, y - 2, x + width + 2, y + 10, 0xEE111820);
        graphics.drawString(font, label, x, y, 0xFFFFFFFF, false);
    }

    public static List<Component> detailLines(MapRouteEntry route) {
        return List.of(Component.literal(route.name()), text("kind", kindText(route.kind())),
                text("endpoints", route.fromName(), route.toName()), text("distance", distanceText(route.lengthBlocks())));
    }

    public static String distanceText(double blocks) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.ROOT);
        format.setMaximumFractionDigits(1);
        return format.format(blocks);
    }

    public static Component kindText(MapRouteKind kind) { return text(kind.name().toLowerCase(Locale.ROOT)); }
    public static Component text(String suffix, Object... arguments) { return Component.translatable("screen.sailboatmod.map_routes." + suffix, arguments); }
    public static int color(MapRouteKind kind) {
        return switch (kind) {
            case ROAD -> 0xFFFFD15C;
            case LAND -> 0xFF6CDD83;
            case SEA -> 0xFF5FDDF2;
        };
    }
}
