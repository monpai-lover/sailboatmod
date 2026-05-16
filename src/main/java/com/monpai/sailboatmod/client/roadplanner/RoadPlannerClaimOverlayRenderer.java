package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoadPlannerClaimOverlayRenderer {
    public enum BorderSide {
        NORTH(0, -1),
        SOUTH(0, 1),
        WEST(-1, 0),
        EAST(1, 0);

        private final int dx;
        private final int dz;

        BorderSide(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    private final List<RoadPlannerClaimOverlay> overlays;
    private final List<RoadPlannerClaimOverlay> canonicalOverlays;
    private final Map<Long, RoadPlannerClaimOverlay> overlaysByChunk;

    public RoadPlannerClaimOverlayRenderer(Collection<RoadPlannerClaimOverlay> overlays) {
        this.overlays = overlays == null ? List.of() : List.copyOf(overlays);
        List<RoadPlannerClaimOverlay> canonical = new ArrayList<>();
        this.overlaysByChunk = new HashMap<>();
        for (RoadPlannerClaimOverlay overlay : this.overlays) {
            long key = chunkKey(overlay.chunkX(), overlay.chunkZ());
            if (!overlaysByChunk.containsKey(key)) {
                overlaysByChunk.put(key, overlay);
                canonical.add(overlay);
            }
        }
        this.canonicalOverlays = List.copyOf(canonical);
    }

    public List<RoadPlannerClaimOverlay> overlays() {
        return overlays;
    }

    public Optional<RoadPlannerClaimOverlay> claimAtWorld(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        return Optional.ofNullable(overlaysByChunk.get(chunkKey(chunkX, chunkZ)));
    }

    public void render(GuiGraphics graphics, RoadPlannerMapView view, RoadPlannerMapLayout.Rect map) {
        for (RoadPlannerClaimOverlay overlay : canonicalOverlays) {
            int x1 = view.worldToScreenX(overlay.chunkX() << 4, map);
            int z1 = view.worldToScreenZ(overlay.chunkZ() << 4, map);
            int x2 = view.worldToScreenX((overlay.chunkX() + 1) << 4, map);
            int z2 = view.worldToScreenZ((overlay.chunkZ() + 1) << 4, map);
            int left = Math.min(x1, x2);
            int right = Math.max(x1, x2);
            int top = Math.min(z1, z2);
            int bottom = Math.max(z1, z2);
            graphics.fill(left, top, right, bottom, fillColor(overlay));
            int border = borderColor(overlay);
            if (visibleBorder(overlay, BorderSide.NORTH)) {
                graphics.fill(left, top, right, top + 1, border);
            }
            if (visibleBorder(overlay, BorderSide.SOUTH)) {
                graphics.fill(left, bottom - 1, right, bottom, border);
            }
            if (visibleBorder(overlay, BorderSide.WEST)) {
                graphics.fill(left, top, left + 1, bottom, border);
            }
            if (visibleBorder(overlay, BorderSide.EAST)) {
                graphics.fill(right - 1, top, right, bottom, border);
            }
        }
    }

    public void renderTooltip(GuiGraphics graphics,
                              Font font,
                              RoadPlannerMapView view,
                              RoadPlannerMapLayout.Rect map,
                              int mouseX,
                              int mouseY) {
        int worldX = view.screenToWorldX(mouseX, map);
        int worldZ = view.screenToWorldZ(mouseY, map);
        claimAtWorld(worldX, worldZ).ifPresent(overlay -> graphics.renderTooltip(font,
                List.of(
                        Component.literal(roleLabel(overlay) + ": " + overlay.townName()),
                        Component.literal("国家: " + (overlay.nationName().isBlank() ? "-" : overlay.nationName())),
                        Component.literal("区块: " + overlay.chunkX() + ", " + overlay.chunkZ())
                ), Optional.empty(), mouseX, mouseY));
    }

    public static int fillColor(RoadPlannerClaimOverlay overlay) {
        int rgb = overlay.role() == RoadPlannerClaimOverlay.Role.DESTINATION ? 0xFF3333
                : overlay.role() == RoadPlannerClaimOverlay.Role.START ? 0x40D878 : overlay.primaryColorRgb();
        return 0x55000000 | (rgb & 0x00FFFFFF);
    }

    public static int borderColor(RoadPlannerClaimOverlay overlay) {
        int rgb = overlay.role() == RoadPlannerClaimOverlay.Role.DESTINATION ? 0xFF0000
                : overlay.role() == RoadPlannerClaimOverlay.Role.START ? 0x00FF80 : overlay.secondaryColorRgb();
        return 0xCC000000 | (rgb & 0x00FFFFFF);
    }

    boolean visibleBorderForTest(RoadPlannerClaimOverlay overlay, BorderSide side) {
        return visibleBorder(overlay, side);
    }

    private boolean visibleBorder(RoadPlannerClaimOverlay overlay, BorderSide side) {
        RoadPlannerClaimOverlay neighbor = overlaysByChunk.get(chunkKey(
                overlay.chunkX() + side.dx,
                overlay.chunkZ() + side.dz
        ));
        return neighbor == null || !ownerKey(overlay).equals(ownerKey(neighbor));
    }

    private static String ownerKey(RoadPlannerClaimOverlay overlay) {
        String role = overlay.role().name();
        if (!overlay.townId().isBlank()) {
            return role + ":town:" + overlay.townId();
        }
        if (!overlay.nationId().isBlank()) {
            return role + ":nation:" + overlay.nationId();
        }
        return role + ":blank:" + overlay.chunkX() + ":" + overlay.chunkZ();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static String roleLabel(RoadPlannerClaimOverlay overlay) {
        return switch (overlay.role()) {
            case START -> "起点Town";
            case DESTINATION -> "终点Town";
            case OTHER -> "Town";
        };
    }
}
