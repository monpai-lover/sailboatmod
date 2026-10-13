package com.monpai.sailboatmod.client.ui.territory;

import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.client.screen.ClaimWorldMapView;
import com.monpai.sailboatmod.nation.menu.ClaimPreviewMapState;
import com.monpai.sailboatmod.nation.menu.NationOverviewClaim;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
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
                                  ClaimLookup lookup, Function<NationOverviewClaim, String> ownerKey) {
        ClaimWorldMapView.ChunkBounds bounds = view.visibleChunkBounds(viewport);
        for (int chunkZ = bounds.minChunkZ(); chunkZ <= bounds.maxChunkZ(); chunkZ++) {
            for (int chunkX = bounds.minChunkX(); chunkX <= bounds.maxChunkX(); chunkX++) {
                NationOverviewClaim claim = lookup.find(chunkX, chunkZ);
                if (claim == null) {
                    continue;
                }
                ClaimWorldMapView.ScreenRect rect = view.chunkScreenRect(chunkX, chunkZ, viewport);
                int x1 = Math.max(viewport.x(), rect.x());
                int x2 = Math.min(viewport.right(), rect.right());
                int y1 = Math.max(viewport.y(), rect.y());
                int y2 = Math.min(viewport.bottom(), rect.bottom());
                if (x1 >= x2 || y1 >= y2) {
                    continue;
                }
                g.fill(x1, y1, x2, y2, 0x66000000 | (claim.primaryColorRgb() & 0x00FFFFFF));
                int borderColor = 0xFF000000 | claim.secondaryColorRgb();
                String owner = ownerKey.apply(claim);
                if (!sameOwner(lookup, ownerKey, owner, chunkX, chunkZ - 1)) g.fill(x1, y1, x2, y1 + 1, borderColor);
                if (!sameOwner(lookup, ownerKey, owner, chunkX, chunkZ + 1)) g.fill(x1, y2 - 1, x2, y2, borderColor);
                if (!sameOwner(lookup, ownerKey, owner, chunkX - 1, chunkZ)) g.fill(x1, y1, x1 + 1, y2, borderColor);
                if (!sameOwner(lookup, ownerKey, owner, chunkX + 1, chunkZ)) g.fill(x2 - 1, y1, x2, y2, borderColor);
            }
        }
    }

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
        for (int z = Math.min(corner1Z, corner2Z); z <= Math.max(corner1Z, corner2Z); z++) {
            for (int x = Math.min(corner1X, corner2X); x <= Math.max(corner1X, corner2X); x++) {
                drawMarker(g, view, viewport, x, z, 0xAAFF8844);
            }
        }
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
