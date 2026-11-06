package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.client.screen.ClaimMapViewport;

/** Screen-coordinate capture shared by the map element's left, right and middle gestures. */
public final class AirportMapInputCapture {
    public enum MotionKind { IGNORED, MOVED, CANCELLED }
    public record Motion(MotionKind kind, double deltaX, double deltaY) {}
    private int button = -1;
    private double lastX, lastY;
    public boolean active() { return button >= 0; }
    public int button() { return button; }
    public boolean begin(double x, double y, int pressedButton, ClaimMapViewport viewport) {
        if (active() || pressedButton < 0 || pressedButton > 2 || !inside(x,y,viewport)) return false;
        button=pressedButton; lastX=x; lastY=y; return true;
    }
    public Motion drag(double x, double y, int draggedButton, ClaimMapViewport viewport) {
        if (!active() || draggedButton!=button) return new Motion(MotionKind.IGNORED,0,0);
        if (!inside(x,y,viewport)) { cancel(); return new Motion(MotionKind.CANCELLED,0,0); }
        Motion motion=new Motion(MotionKind.MOVED,x-lastX,y-lastY);lastX=x;lastY=y;return motion;
    }
    public boolean release(int releasedButton) {
        if (!active() || releasedButton!=button) return false;
        cancel(); return true;
    }
    public void cancel() { button=-1; }
    private static boolean inside(double x,double y,ClaimMapViewport viewport) {
        return viewport!=null && Double.isFinite(x) && Double.isFinite(y) && viewport.contains(x,y);
    }
}
