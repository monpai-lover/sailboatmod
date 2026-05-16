package com.monpai.sailboatmod.client.screen;

public record ClaimMapViewport(int x, int y, int width, int height) {
    public ClaimMapViewport {
        width = Math.max(1, width);
        height = Math.max(1, height);
    }

    public static ClaimMapViewport scrolled(int logicalX, int logicalY, int pageScroll, int width, int height) {
        return new ClaimMapViewport(logicalX, logicalY - pageScroll, width, height);
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(double screenX, double screenY) {
        return screenX >= x && screenX < right() && screenY >= y && screenY < bottom();
    }

    public ClaimMapViewport intersection(int left, int top, int right, int bottom) {
        int clippedX = Math.max(x, left);
        int clippedY = Math.max(y, top);
        int clippedRight = Math.min(right(), right);
        int clippedBottom = Math.min(bottom(), bottom);
        if (clippedX >= clippedRight || clippedY >= clippedBottom) {
            return null;
        }
        return new ClaimMapViewport(clippedX, clippedY, clippedRight - clippedX, clippedBottom - clippedY);
    }
}
