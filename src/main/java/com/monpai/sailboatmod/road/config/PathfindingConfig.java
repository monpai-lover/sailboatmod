package com.monpai.sailboatmod.road.config;

public class PathfindingConfig {
    public enum Algorithm { BASIC_ASTAR, BIDIRECTIONAL_ASTAR, GRADIENT_DESCENT, POTENTIAL_FIELD }
    public enum SamplingPrecision { NORMAL, HIGH, ULTRA_HIGH }

    private Algorithm algorithm = Algorithm.POTENTIAL_FIELD;
    private int maxSteps = 10000;
    private SamplingPrecision samplingPrecision = SamplingPrecision.NORMAL;
    private double elevationWeight = 2.0;
    private double biomeWeight = 1.0;
    private double stabilityWeight = 1.0;
    private double waterDepthWeight = 1.0;
    private double nearWaterCost = 50.0;
    private double deviationWeight = 0.5;
    private double heuristicWeight = 1.0;
    private int aStarStep = 8;
    private int threadPoolSize = 2;

    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public SamplingPrecision getSamplingPrecision() { return samplingPrecision; }
    public void setSamplingPrecision(SamplingPrecision p) { this.samplingPrecision = p; }
    public double getElevationWeight() { return elevationWeight; }
    public void setElevationWeight(double w) { this.elevationWeight = w; }
    public double getBiomeWeight() { return biomeWeight; }
    public void setBiomeWeight(double w) { this.biomeWeight = w; }
    public double getStabilityWeight() { return stabilityWeight; }
    public void setStabilityWeight(double w) { this.stabilityWeight = w; }
    public double getWaterDepthWeight() { return waterDepthWeight; }
    public void setWaterDepthWeight(double w) { this.waterDepthWeight = w; }
    public double getNearWaterCost() { return nearWaterCost; }
    public void setNearWaterCost(double c) { this.nearWaterCost = c; }
    public double getDeviationWeight() { return deviationWeight; }
    public void setDeviationWeight(double w) { this.deviationWeight = w; }
    public double getHeuristicWeight() { return heuristicWeight; }
    public void setHeuristicWeight(double w) { this.heuristicWeight = w; }
    public int getAStarStep() { return aStarStep; }
    public void setAStarStep(int step) { this.aStarStep = step; }
    public int getThreadPoolSize() { return threadPoolSize; }
    public void setThreadPoolSize(int size) { this.threadPoolSize = size; }
}
