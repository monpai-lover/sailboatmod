package com.monpai.sailboatmod.client.map.route;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import java.util.Objects;

/** Atlas camera state survives layout refreshes but never carries a camera across worlds or dimensions. */
public final class RouteAtlasView {
    private RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, .5);
    private String world;
    private String dimension;

    public RoadPlannerMapView view() { return view; }

    public void updateContext(String world, String dimension, double playerX, double playerZ) {
        if (!Objects.equals(this.world, world) || !Objects.equals(this.dimension, dimension)) {
            this.world = world;
            this.dimension = dimension;
            view = RoadPlannerMapView.centered(playerX, playerZ, .5);
        }
    }

    public void recenter(double x, double z) {
        view = RoadPlannerMapView.centered(x, z, view.scale());
    }
}
