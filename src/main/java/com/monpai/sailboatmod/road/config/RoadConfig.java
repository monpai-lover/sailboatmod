package com.monpai.sailboatmod.road.config;

public class RoadConfig {
    private final PathfindingConfig pathfinding = new PathfindingConfig();
    private final BridgeConfig bridge = new BridgeConfig();
    private final AppearanceConfig appearance = new AppearanceConfig();
    private final ConstructionConfig construction = new ConstructionConfig();

    public PathfindingConfig getPathfinding() { return pathfinding; }
    public BridgeConfig getBridge() { return bridge; }
    public AppearanceConfig getAppearance() { return appearance; }
    public ConstructionConfig getConstruction() { return construction; }
}
