package com.monpai.sailboatmod.client.ui.airport;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.monpai.sailboatmod.client.airport.AirportMapInputCapture;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import java.util.Objects;

/** Airport-only LDLib map host; terrain cache and draft ownership stay in the screen. */
public final class AirportMapElement extends UIElement {
    public interface Host {
        void paintMap(GuiGraphics graphics, ClaimMapViewport viewport, int mouseX, int mouseY);
        void zoomMap(double mouseX,double mouseY,double factor,ClaimMapViewport viewport);
        void panMap(double deltaX,double deltaY);
        void panFinished();
        boolean pressMap(double mouseX,double mouseY,int button,ClaimMapViewport viewport,boolean shift);
        void dragMap(double mouseX,double mouseY,int button,ClaimMapViewport viewport);
        void releaseMap(int button);
        default void cancelMapInput() {}
    }
    private final Host host;
    private final AirportMapInputCapture capture=new AirportMapInputCapture();
    public AirportMapElement(Host host) {
        this.host=Objects.requireNonNull(host);
        addClass("sm-minimap");layout(l -> l.paddingAll(1));
        addEventListener(UIEvents.MOUSE_WHEEL,event -> {
            ClaimMapViewport viewport=viewport();
            if (!Double.isFinite(event.x)||!Double.isFinite(event.y)||!Double.isFinite(event.deltaY)
                    ||event.deltaY==0||!viewport.contains(event.x,event.y)) return;
            cancelCapture();
            host.zoomMap(event.x,event.y,event.deltaY>0?1.2D:1D/1.2D,viewport);
            event.stopPropagation();
        });
        addEventListener(UIEvents.MOUSE_DOWN,event -> {
            ClaimMapViewport viewport=viewport();
            if (!capture.begin(event.x,event.y,event.button,viewport)) return;
            try {
                if (event.button!=2 && !host.pressMap(event.x,event.y,event.button,viewport,Screen.hasShiftDown())) {
                    capture.cancel();return;
                }
                event.stopPropagation();
            } catch (RuntimeException ex) { cancelCapture();throw ex; }
        });
        addEventListener(UIEvents.MOUSE_UP,event -> { if(release(event.button))event.stopPropagation(); });
    }
    public ClaimMapViewport viewport() {
        return new ClaimMapViewport(Math.round(getContentX()),Math.round(getContentY()),
                Math.max(1,Math.round(getContentWidth())),Math.max(1,Math.round(getContentHeight())));
    }
    public boolean isDragging() { return capture.active(); }
    public boolean dragTo(double x,double y,int button) {
        var motion=capture.drag(x,y,button,viewport());
        if(motion.kind()==AirportMapInputCapture.MotionKind.IGNORED)return false;
        if(motion.kind()==AirportMapInputCapture.MotionKind.CANCELLED) {
            if(button==2)host.panFinished();else host.cancelMapInput();
            return true;
        }
        try {
            if(button==2)host.panMap(motion.deltaX(),motion.deltaY());
            else host.dragMap(x,y,button,viewport());
        } catch(RuntimeException ex) {cancelCapture();throw ex;}
        return true;
    }
    /** Must be forwarded even if the cursor is outside the element. */
    public boolean release(int button) {
        if(!capture.release(button))return false;
        if(button==2)host.panFinished();else host.releaseMap(button);
        return true;
    }
    public void cancelCapture() {
        if(!capture.active())return;
        int button=capture.button();capture.cancel();
        if(button==2)host.panFinished();else host.cancelMapInput();
    }
    @Override public void drawContents(GUIContext context) {
        super.drawContents(context);
        ClaimMapViewport viewport=viewport();if(viewport.width()<4||viewport.height()<4)return;
        GuiGraphics graphics=context.graphics;
        graphics.enableScissor(viewport.x(),viewport.y(),viewport.right(),viewport.bottom());
        try {host.paintMap(graphics,viewport,context.mouseX,context.mouseY);}
        finally {graphics.disableScissor();}
    }
}
