package com.monpai.sailboatmod.construction;

public record RoadBridgeBudgetState(int contiguousBridgeColumns,
                                    int totalBridgeColumns,
                                    boolean accepted) {
    public static RoadBridgeBudgetState empty() {
        return new RoadBridgeBudgetState(0, 0, true);
    }

    public RoadBridgeBudgetState advance(boolean bridgeStep, int maxContiguousBridgeColumns, int maxTotalBridgeColumns) {
        int nextContiguous = bridgeStep ? contiguousBridgeColumns + 1 : 0;
        int nextTotal = bridgeStep ? totalBridgeColumns + 1 : totalBridgeColumns;
        boolean nextAccepted = nextContiguous <= maxContiguousBridgeColumns && nextTotal <= maxTotalBridgeColumns;
        return new RoadBridgeBudgetState(nextContiguous, nextTotal, nextAccepted);
    }
}
