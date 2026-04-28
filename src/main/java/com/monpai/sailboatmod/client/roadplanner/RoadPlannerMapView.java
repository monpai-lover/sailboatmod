package com.monpai.sailboatmod.client.roadplanner;

public class RoadPlannerMapView {
    private static final double MIN_SCALE = 0.25D;
    private static final double MAX_SCALE = 8.0D;

    private double centerX;
    private double centerZ;
    private double scale;

    private RoadPlannerMapView(double centerX, double centerZ, double scale) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.scale = clampScale(scale);
    }

    public static RoadPlannerMapView centered(double centerX, double centerZ, double scale) {
        return new RoadPlannerMapView(centerX, centerZ, scale);
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public double scale() {
        return scale;
    }

    public int worldToScreenX(double worldX, RoadPlannerMapLayout.Rect map) {
        return (int) Math.round(map.x() + map.width() / 2.0D + (worldX - centerX) * scale);
    }

    public int worldToScreenZ(double worldZ, RoadPlannerMapLayout.Rect map) {
        return (int) Math.round(map.y() + map.height() / 2.0D + (worldZ - centerZ) * scale);
    }

    public int screenToWorldX(double screenX, RoadPlannerMapLayout.Rect map) {
        return (int) Math.round(centerX + (screenX - (map.x() + map.width() / 2.0D)) / scale);
    }

    public int screenToWorldZ(double screenY, RoadPlannerMapLayout.Rect map) {
        return (int) Math.round(centerZ + (screenY - (map.y() + map.height() / 2.0D)) / scale);
    }

    public void panByScreenDelta(double deltaX, double deltaY) {
        centerX -= deltaX / scale;
        centerZ -= deltaY / scale;
    }

    public void zoomAround(double screenX, double screenY, double factor, RoadPlannerMapLayout.Rect map) {
        int worldX = screenToWorldX(screenX, map);
        int worldZ = screenToWorldZ(screenY, map);
        scale = clampScale(scale * factor);
        centerX = worldX - (screenX - (map.x() + map.width() / 2.0D)) / scale;
        centerZ = worldZ - (screenY - (map.y() + map.height() / 2.0D)) / scale;
    }

    private static double clampScale(double value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }
}
