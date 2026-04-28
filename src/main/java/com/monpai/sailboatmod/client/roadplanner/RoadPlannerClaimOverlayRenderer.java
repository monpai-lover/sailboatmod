package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class RoadPlannerClaimOverlayRenderer {
    private final List<RoadPlannerClaimOverlay> overlays;

    public RoadPlannerClaimOverlayRenderer(Collection<RoadPlannerClaimOverlay> overlays) {
        this.overlays = overlays == null ? List.of() : List.copyOf(overlays);
    }

    public List<RoadPlannerClaimOverlay> overlays() {
        return overlays;
    }

    public Optional<RoadPlannerClaimOverlay> claimAtWorld(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        return overlays.stream()
                .filter(overlay -> overlay.chunkX() == chunkX && overlay.chunkZ() == chunkZ)
                .findFirst();
    }

    public void render(GuiGraphics graphics, RoadPlannerMapView view, RoadPlannerMapLayout.Rect map) {
        for (RoadPlannerClaimOverlay overlay : overlays) {
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
            graphics.fill(left, top, right, top + 1, border);
            graphics.fill(left, bottom - 1, right, bottom, border);
            graphics.fill(left, top, left + 1, bottom, border);
            graphics.fill(right - 1, top, right, bottom, border);
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

    private static String roleLabel(RoadPlannerClaimOverlay overlay) {
        return switch (overlay.role()) {
            case START -> "起点Town";
            case DESTINATION -> "终点Town";
            case OTHER -> "Town";
        };
    }
}
