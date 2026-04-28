package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerMapLayout(Rect map, Rect toolbar, Rect statusBar, Rect inspector) {
    public static RoadPlannerMapLayout compute(int screenWidth, int screenHeight) {
        int margin = Math.max(8, Math.min(18, screenWidth / 90));
        int statusHeight = Math.max(28, Math.min(40, screenHeight / 18));
        int toolbarHeight = 36;
        Rect map = new Rect(margin, toolbarHeight + margin, screenWidth - margin * 2, screenHeight - toolbarHeight - margin * 2 - statusHeight);
        Rect toolbar = new Rect(0, 0, screenWidth, toolbarHeight);
        Rect statusBar = new Rect(map.x(), map.bottom() + 2, map.width(), statusHeight - 2);
        Rect inspector = new Rect(map.right() - 260, map.y() + 8, 250, 34);
        return new RoadPlannerMapLayout(map, toolbar, statusBar, inspector);
    }

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX <= right() && pointY >= y && pointY <= bottom();
        }

        public RoadPlannerVanillaLayout.Rect asVanillaRect() {
            return new RoadPlannerVanillaLayout.Rect(x, y, width, height);
        }
    }
}
