package com.monpai.sailboatmod.road.config;

public class AppearanceConfig {
    private int defaultWidth = 3;
    private int landLightInterval = 24;
    private boolean tunnelEnabled = false;
    private int roadClearHeight = 4;
    private int tunnelClearHeight = 5;
    private int tunnelLightInterval = 8;

    public int getDefaultWidth() { return defaultWidth; }
    public void setDefaultWidth(int w) { this.defaultWidth = w; }
    public int getLandLightInterval() { return landLightInterval; }
    public void setLandLightInterval(int i) { this.landLightInterval = i; }
    public boolean isTunnelEnabled() { return tunnelEnabled; }
    public void setTunnelEnabled(boolean e) { this.tunnelEnabled = e; }
    public int getRoadClearHeight() { return roadClearHeight; }
    public void setRoadClearHeight(int h) { this.roadClearHeight = h; }
    public int getTunnelClearHeight() { return tunnelClearHeight; }
    public void setTunnelClearHeight(int h) { this.tunnelClearHeight = h; }
    public int getTunnelLightInterval() { return tunnelLightInterval; }
    public void setTunnelLightInterval(int i) { this.tunnelLightInterval = i; }
}
