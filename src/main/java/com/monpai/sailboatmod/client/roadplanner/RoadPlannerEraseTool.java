package com.monpai.sailboatmod.client.roadplanner;

public class RoadPlannerEraseTool {
    public boolean eraseNode(RoadPlannerLinePlan plan, int nodeIndex, boolean protectStartNode) {
        if (plan == null) {
            return false;
        }
        if (protectStartNode && nodeIndex == 0) {
            return false;
        }
        return plan.removeNodeAt(nodeIndex);
    }
}
