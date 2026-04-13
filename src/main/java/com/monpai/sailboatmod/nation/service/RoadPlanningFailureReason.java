package com.monpai.sailboatmod.nation.service;

public enum RoadPlanningFailureReason {
    NONE("NONE", "message.sailboatmod.road_planner.failure.none"),
    BLOCKED_BY_CORE_BUFFER("BLOCKED_BY_CORE_BUFFER", "message.sailboatmod.road_planner.failure.blocked_by_core_buffer"),
    NO_CONTINUOUS_GROUND_ROUTE("NO_CONTINUOUS_GROUND_ROUTE", "message.sailboatmod.road_planner.failure.no_continuous_ground_route"),
    SEARCH_BUDGET_EXCEEDED("SEARCH_BUDGET_EXCEEDED", "message.sailboatmod.road_planner.failure.search_budget_exceeded"),
    TARGET_NOT_ATTACHABLE("TARGET_NOT_ATTACHABLE", "message.sailboatmod.road_planner.failure.target_not_attachable");

    private final String reasonCode;
    private final String translationKey;

    RoadPlanningFailureReason(String reasonCode, String translationKey) {
        this.reasonCode = reasonCode;
        this.translationKey = translationKey;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String translationKey() {
        return translationKey;
    }
}
