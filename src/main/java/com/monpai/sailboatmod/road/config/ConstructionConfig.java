package com.monpai.sailboatmod.road.config;

public class ConstructionConfig {
    private int tickSlowRate = 5;
    private int npcRate = 2;
    private int hammerRate = 1;
    private int hammerBatchSize = 4;

    public int getTickSlowRate() { return tickSlowRate; }
    public void setTickSlowRate(int r) { this.tickSlowRate = r; }
    public int getNpcRate() { return npcRate; }
    public void setNpcRate(int r) { this.npcRate = r; }
    public int getHammerRate() { return hammerRate; }
    public void setHammerRate(int r) { this.hammerRate = r; }
    public int getHammerBatchSize() { return hammerBatchSize; }
    public void setHammerBatchSize(int s) { this.hammerBatchSize = s; }
}
