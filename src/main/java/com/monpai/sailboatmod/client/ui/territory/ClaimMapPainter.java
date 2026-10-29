package com.monpai.sailboatmod.client.ui.territory;

import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.client.screen.ClaimWorldMapView;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Territory overlay drawing shared by the nation and town maps: tinted claims with borders where the owner changes,
 * chunk markers, the loading bars and the owner label under the cursor. Same colours and rules as the previous
 * screens; only the owner key differs (nation id for nations, town or nation id for towns).
 */
public final class ClaimMapPainter {
    /** Looks up the claim drawn at a chunk (from the latest data or the overlay cache). */
    @FunctionalInterface
    public interface ClaimLookup {
        NationOverviewClaim find(int chunkX, int chunkZ);
    }

    private ClaimMapPainter() {
    }

    public static void drawClaims(GuiGraphics g, ClaimWorldMapView view, ClaimMapViewport viewport,
                                  Collection<NationOverviewClaim> known, ClaimLookup lookup,
                                  Function<NationOverviewClaim, String> ownerKey) {
        for (FillRect fill : planClaims(known, view.visibleChunkBounds(viewport), viewport,
                claim -> view.chunkScreenRect(claim.chunkX(), claim.chunkZ(), viewport), lookup, ownerKey)) {
            g.fill(fill.x1, fill.y1, fill.x2, fill.y2, fill.color);
        }
    }

    /** Work depends on known claims, never the number of unknown world chunks visible at far zoom. */
    public static List<FillRect> planClaims(Collection<NationOverviewClaim> known,
                                            ClaimWorldMapView.ChunkBounds bounds, ClaimMapViewport viewport,
                                            Function<NationOverviewClaim, ClaimWorldMapView.ScreenRect> project,
                                            ClaimLookup lookup, Function<NationOverviewClaim, String> ownerKey) {
        List<FillRect> fills = new ArrayList<>();
        for (NationOverviewClaim claim : known) {
            if (claim == null || claim.chunkX() < bounds.minChunkX() || claim.chunkX() > bounds.maxChunkX()
                    || claim.chunkZ() < bounds.minChunkZ() || claim.chunkZ() > bounds.maxChunkZ()) continue;
            var rect = project.apply(claim);
            int x1 = Math.max(viewport.x(), rect.x()), x2 = Math.min(viewport.right(), rect.right());
            int y1 = Math.max(viewport.y(), rect.y()), y2 = Math.min(viewport.bottom(), rect.bottom());
            if (x1 >= x2 || y1 >= y2) continue;
            fills.add(new FillRect(x1, y1, x2, y2, 0x66000000 | claim.primaryColorRgb()));
            int border = 0xFF000000 | claim.secondaryColorRgb();
            String owner = ownerKey.apply(claim);
            int x = claim.chunkX(), z = claim.chunkZ();
            if (!sameOwner(lookup, ownerKey, owner, x, z - 1)) fills.add(new FillRect(x1, y1, x2, y1 + 1, border));
            if (!sameOwner(lookup, ownerKey, owner, x, z + 1)) fills.add(new FillRect(x1, y2 - 1, x2, y2, border));
            if (!sameOwner(lookup, ownerKey, owner, x - 1, z)) fills.add(new FillRect(x1, y1, x1 + 1, y2, border));
            if (!sameOwner(lookup, ownerKey, owner, x + 1, z)) fills.add(new FillRect(x2 - 1, y1, x2, y2, border));
        }
        return List.copyOf(fills);
    }

    public record FillRect(int x1, int y1, int x2, int y2, int color) { }

    private static boolean sameOwner(ClaimLookup lookup, Function<NationOverviewClaim, String> ownerKey, String owner, int x, int z) {
        NationOverviewClaim claim = lookup.find(x, z);
        return claim != null && owner.equals(ownerKey.apply(claim));
    }

    public static void drawMarker(GuiGraphics g, ClaimWorldMapView view, ClaimMapViewport viewport, int chunkX, int chunkZ, int color) {
        if (chunkX == Integer.MIN_VALUE || chunkZ == Integer.MIN_VALUE) {
            return;
        }
        ClaimWorldMapView.ScreenRect rect = view.chunkScreenRect(chunkX, chunkZ, viewport);
        int x1 = Math.max(viewport.x(), rect.x());
        int y1 = Math.max(viewport.y(), rect.y());
        int x2 = Math.min(viewport.right(), rect.right());
        int y2 = Math.min(viewport.bottom(), rect.bottom());
        if (x1 >= x2 || y1 >= y2) {
            return;
        }
        outline(g, x1, y1, Math.max(x1 + 1, x2) - 1, Math.max(y1 + 1, y2) - 1, color);
    }

    /** Area selection between two corners (or the first corner alone). */
    public static void drawArea(GuiGraphics g, ClaimWorldMapView view, ClaimMapViewport viewport,
                                int corner1X, int corner1Z, int corner2X, int corner2Z) {
        if (corner1X == Integer.MIN_VALUE) {
            return;
        }
        if (corner2X == Integer.MIN_VALUE) {
            drawMarker(g, view, viewport, corner1X, corner1Z, 0xAAFF8844);
            return;
        }
        var first = view.chunkScreenRect(Math.min(corner1X, corner2X), Math.min(corner1Z, corner2Z), viewport);
        var last = view.chunkScreenRect(Math.max(corner1X, corner2X), Math.max(corner1Z, corner2Z), viewport);
        for (FillRect fill : areaBorder(first, last, viewport, 0xAAFF8844)) {
            g.fill(fill.x1, fill.y1, fill.x2, fill.y2, fill.color);
        }
    }

    /** One projected rectangle, clipped once, independently of the number of selected world chunks. */
    public static List<FillRect> areaBorder(ClaimWorldMapView.ScreenRect first, ClaimWorldMapView.ScreenRect last,
                                          ClaimMapViewport viewport, int color) {
        int x1 = Math.max(viewport.x(), Math.min(first.x(), last.x()));
        int y1 = Math.max(viewport.y(), Math.min(first.y(), last.y()));
        int x2 = Math.min(viewport.right(), Math.max(first.right(), last.right()));
        int y2 = Math.min(viewport.bottom(), Math.max(first.bottom(), last.bottom()));
        if (x1 >= x2 || y1 >= y2) return List.of();
        return List.of(new FillRect(x1, y1, x2, y1 + 1, color), new FillRect(x1, y2 - 1, x2, y2, color),
                new FillRect(x1, y1, x1 + 1, y2, color), new FillRect(x2 - 1, y1, x2, y2, color));
    }

    /** Two thin bars along the bottom: visible chunks (gold) and prefetch ring (blue). */
    public static void drawProgress(GuiGraphics g, ClaimMapViewport viewport, ClaimPreviewMapState state, int visibleWidth, int prefetchWidth) {
        if (state == null || !(state.loading() || state.hasPendingProgress())) {
            return;
        }
        int barX = viewport.x() + 1;
        int barW = viewport.width() - 2;
        int visibleY = viewport.bottom() - 5;
        int prefetchY = viewport.bottom() - 2;
        g.fill(barX, visibleY, barX + barW, visibleY + 1, 0x55313A40);
        g.fill(barX, prefetchY, barX + barW, prefetchY + 1, 0x55313A40);
        if (visibleWidth > 0) {
            g.fill(barX, visibleY, barX + Math.min(barW, visibleWidth), visibleY + 1, 0xD9D8B35A);
        }
        if (prefetchWidth > 0) {
            g.fill(barX, prefetchY, barX + Math.min(barW, prefetchWidth), prefetchY + 1, 0xAA6AA8C8);
        }
    }

    /** "Owner (claims)" label following the cursor over a claimed chunk. */
    public static void drawHoverLabel(GuiGraphics g, Font font, ClaimWorldMapView view, ClaimMapViewport viewport,
                                      int mouseX, int mouseY, ClaimLookup lookup, Function<NationOverviewClaim, String> ownerKey,
                                      Function<NationOverviewClaim, String> ownerName, Collection<NationOverviewClaim> nearby) {
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }
        ChunkPos hover = view.screenToChunk(mouseX, mouseY, viewport);
        NationOverviewClaim claim = lookup.find(hover.x, hover.z);
        if (claim == null) {
            return;
        }
        String label = ownerName.apply(claim);
        if (label == null || label.isBlank()) {
            return;
        }
        String owner = ownerKey.apply(claim);
        int count = 0;
        for (NationOverviewClaim other : nearby) {
            if (owner.equals(ownerKey.apply(other))) {
                count++;
            }
        }
        String text = label + "(" + count + ")";
        int tw = font.width(text);
        int tx = Math.max(viewport.x(), Math.min(mouseX - tw / 2, Math.max(viewport.x(), viewport.right() - tw)));
        int ty = Math.max(viewport.y(), Math.min(mouseY - 14, Math.max(viewport.y(), viewport.bottom() - 10)));
        g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 9, 0xCC000000);
        g.drawString(font, text, tx, ty, 0xFF000000 | claim.primaryColorRgb());
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
}
