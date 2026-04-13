package com.monpai.sailboatmod.nation.service;

public final class RoadPlanningDebugLogger {
    private RoadPlanningDebugLogger() {
    }

    public static String failure(String stage,
                                 RoadPlanningRequestContext context,
                                 RoadPlanningFailureReason reason,
                                 String detail) {
        return stage
                + " requestId=" + context.requestId()
                + " planner=" + context.plannerKind()
                + " stage=" + stage
                + " reason=" + reason.reasonCode()
                + " source=" + context.sourcePos()
                + " target=" + context.targetPos()
                + " detail=" + detail;
    }
}
