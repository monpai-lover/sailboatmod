package com.monpai.sailboatmod.road.config;

public class BridgeConfig {
    private int deckHeight = 5;
    private int pierInterval = 8;
    private int platformLength = 3;
    private int lightInterval = 8;
    private int mergeGap = 4;
    private int bridgeMinWaterDepth = 2;

    public int getDeckHeight() { return deckHeight; }
    public void setDeckHeight(int h) { this.deckHeight = h; }
    public int getPierInterval() { return pierInterval; }
    public void setPierInterval(int i) { this.pierInterval = i; }
    public int getPlatformLength() { return platformLength; }
    public void setPlatformLength(int l) { this.platformLength = l; }
    public int getLightInterval() { return lightInterval; }
    public void setLightInterval(int i) { this.lightInterval = i; }
    public int getMergeGap() { return mergeGap; }
    public void setMergeGap(int g) { this.mergeGap = g; }
    public int getBridgeMinWaterDepth() { return bridgeMinWaterDepth; }
    public void setBridgeMinWaterDepth(int d) { this.bridgeMinWaterDepth = d; }
}
