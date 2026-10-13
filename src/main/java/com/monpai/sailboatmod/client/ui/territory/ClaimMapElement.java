package com.monpai.sailboatmod.client.ui.territory;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Hosts a claim map (nation or town territory) inside an LDLib2 layout. Drawing and map state stay with the owning
 * screen; this element supplies the viewport (its own content box, in screen coordinates) and routes input:
 * wheel zooms around the cursor, left click selects a chunk (Shift extends an area), middle drag pans.
 */
public final class ClaimMapElement extends UIElement {
    public interface Host {
        void paintMap(GuiGraphics graphics, ClaimMapViewport viewport, int mouseX, int mouseY);

        void zoomMap(double mouseX, double mouseY, double factor, ClaimMapViewport viewport);

        void clickMap(double mouseX, double mouseY, ClaimMapViewport viewport, boolean extendArea);

        void panMap(double deltaX, double deltaY);

        /** Middle drag ended (released, or the cursor left the map): refresh the territory around the new centre. */
        void panFinished();
    }

    private final Host host;
    private boolean dragging;
    private double lastX;
    private double lastY;

    public ClaimMapElement(Host host) {
        this.host = host;
        addClass("sm-minimap");
        layout(l -> l.paddingAll(1));
        addEventListener(UIEvents.MOUSE_WHEEL, event -> {
            ClaimMapViewport viewport = viewport();
            if (viewport.contains(event.x, event.y)) {
                host.zoomMap(event.x, event.y, event.deltaY > 0 ? 1.2D : 0.833333D, viewport);
                event.stopPropagation();
            }
        });
        addEventListener(UIEvents.MOUSE_DOWN, event -> {
            ClaimMapViewport viewport = viewport();
            if (!viewport.contains(event.x, event.y)) {
                return;
            }
            if (event.button == 0) {
                host.clickMap(event.x, event.y, viewport, Screen.hasShiftDown());
                event.stopPropagation();
            } else if (event.button == 2) {
                dragging = true;
                lastX = event.x;
                lastY = event.y;
                event.stopPropagation();
            }
        });
    }

    /** The map area in screen coordinates. */
    public ClaimMapViewport viewport() {
        return new ClaimMapViewport(Math.round(getContentX()), Math.round(getContentY()),
                Math.max(1, Math.round(getContentWidth())), Math.max(1, Math.round(getContentHeight())));
    }

    public boolean isDragging() {
        return dragging;
    }

    /** Forwarded from the screen's mouseDragged so a pan keeps following the cursor. */
    public boolean dragTo(double mouseX, double mouseY, int button) {
        if (!dragging || button != 2) {
            return false;
        }
        if (!viewport().contains(mouseX, mouseY)) {
            dragging = false;
            host.panFinished();
            return true;
        }
        host.panMap(mouseX - lastX, mouseY - lastY);
        lastX = mouseX;
        lastY = mouseY;
        return true;
    }

    /** Forwarded from the screen's mouseReleased. */
    public boolean release(int button) {
        if (!dragging || button != 2) {
            return false;
        }
        dragging = false;
        host.panFinished();
        return true;
    }

    @Override
    public void drawContents(GUIContext context) {
        super.drawContents(context);
        ClaimMapViewport viewport = viewport();
        if (viewport.width() < 4 || viewport.height() < 4) {
            return;
        }
        GuiGraphics g = context.graphics;
        g.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        host.paintMap(g, viewport, context.mouseX, context.mouseY);
        g.disableScissor();
    }
}
